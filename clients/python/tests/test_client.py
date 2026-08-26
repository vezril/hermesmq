"""Contract tests for the Python HermesMQ client against the in-process stub."""

from __future__ import annotations

import pytest

from hermesmq_client import HermesClient, HermesClientError
from tests.conftest import StubServer


class TestTopics:
    def test_create_topic(self, client: HermesClient, stub: StubServer) -> None:
        client.create_topic("orders", labels={"team": "payments"})
        assert stub.last_request["body"] == {"topicId": "orders", "labels": {"team": "payments"}}

    def test_create_duplicate_raises_with_status(self, client: HermesClient) -> None:
        with pytest.raises(HermesClientError) as e:
            client.create_topic("dup")
        assert e.value.status == 409

    def test_get_topic(self, client: HermesClient) -> None:
        topic = client.get_topic("orders")
        assert topic is not None
        assert topic.topic_id == "orders"
        assert topic.labels == {"team": "payments"}

    def test_get_missing_topic_returns_none(self, client: HermesClient) -> None:
        assert client.get_topic("ghost") is None

    def test_update_and_delete_topic(self, client: HermesClient, stub: StubServer) -> None:
        client.update_topic("orders", {"team": "core"})
        assert stub.last_request["body"] == {"labels": {"team": "core"}}
        client.delete_topic("orders")

    def test_list_topics(self, client: HermesClient) -> None:
        topics = client.list_topics()
        assert [t.topic_id for t in topics] == ["orders"]
        assert topics[0].published_total == 42


class TestPublishAndConsume:
    def test_publish_returns_id_and_dedup_flag(self, client: HermesClient) -> None:
        result = client.publish("orders", "hello", attributes={"k": "v"})
        assert result.message_id == "m-123"
        assert result.deduplicated is False

    def test_publish_options_forwarded(self, client: HermesClient, stub: StubServer) -> None:
        client.publish(
            "orders", "hello", ttl_seconds=60, idempotency_key="key-9", producer_id="ingest-1"
        )
        body = stub.last_request["body"]
        assert body["ttlSeconds"] == 60
        assert body["idempotencyKey"] == "key-9"
        assert body["producerId"] == "ingest-1"

    def test_deduplicated_publish_surfaced(self, client: HermesClient) -> None:
        result = client.publish("orders", "hello", idempotency_key="idem-1")
        assert result.message_id == "m-orig"
        assert result.deduplicated is True

    def test_publish_to_missing_topic_raises(self, client: HermesClient) -> None:
        with pytest.raises(HermesClientError) as e:
            client.publish("ghost", "x")
        assert e.value.status == 404

    def test_create_subscription(self, client: HermesClient, stub: StubServer) -> None:
        client.create_subscription("s1", "orders")
        assert stub.last_request["body"] == {"subscriptionId": "s1", "topicId": "orders"}

    def test_delete_subscription(self, client: HermesClient) -> None:
        client.delete_subscription("s1")

    def test_delete_missing_subscription_raises_404(self, client: HermesClient) -> None:
        with pytest.raises(HermesClientError) as e:
            client.delete_subscription("ghost")
        assert e.value.status == 404

    def test_pull(self, client: HermesClient, stub: StubServer) -> None:
        messages = client.pull("s1", max_messages=10, consumer_id="worker-1")
        assert [m.ack_id for m in messages] == ["a1"]
        assert messages[0].payload == "hello"
        assert messages[0].attributes == {"k": "v"}
        assert stub.last_request["body"] == {"max": 10, "consumerId": "worker-1"}

    def test_ack_reports_unknown(self, client: HermesClient) -> None:
        result = client.ack("s1", ["a1"])
        assert result.acknowledged == ["a1"]
        assert result.unknown == ["a-stale"]

    def test_modify_ack_deadline(self, client: HermesClient, stub: StubServer) -> None:
        result = client.modify_ack_deadline("s1", ["a1"], ack_deadline_seconds=30)
        assert result.modified == ["a1"]
        assert result.unknown == ["a-stale"]
        assert stub.last_request["body"] == {"ackIds": ["a1"], "ackDeadlineSeconds": 30}

    def test_list_subscriptions(self, client: HermesClient) -> None:
        subs = client.list_subscriptions()
        assert [s.subscription_id for s in subs] == ["s1"]
        assert subs[0].backlog == 3


class TestObservabilityAndAuth:
    def test_health(self, client: HermesClient) -> None:
        health = client.health()
        assert health.status == "UP"
        assert health.service == "hermesmq"

    def test_no_auth_headers_by_default(self, client: HermesClient, stub: StubServer) -> None:
        client.health()
        assert stub.last_request["authorization"] == ""
        assert stub.last_request["x_api_key"] == ""

    def test_bearer_token(self, base_url: str, stub: StubServer) -> None:
        with HermesClient(base_url, token="t1") as c:
            c.health()
        assert stub.last_request["authorization"] == "Bearer t1"

    def test_api_key(self, base_url: str, stub: StubServer) -> None:
        with HermesClient(base_url, api_key="k1") as c:
            c.health()
        assert stub.last_request["x_api_key"] == "k1"
