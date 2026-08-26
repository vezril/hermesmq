"""The synchronous HermesMQ client (httpx-based).

Error model: non-2xx responses raise :class:`HermesClientError` carrying the
HTTP status and response body — except ``get_topic`` on 404, which returns
``None`` (reading a missing thing is normal, not exceptional). Optional
``token``/``api_key`` are sent as ``Authorization: Bearer`` / ``X-API-Key`` on
every request; both are omitted by default, matching an open broker.
"""

from __future__ import annotations

from types import TracebackType
from typing import Any, Self

import httpx

from hermesmq_client.models import (
    AckResult,
    HealthInfo,
    ModifyAckDeadlineResult,
    PublishResult,
    ReceivedMessage,
    SubscriptionStats,
    TopicInfo,
    TopicStats,
)


class HermesClientError(Exception):
    """Raised when the broker returns an unexpected/error status."""

    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"HermesMQ request failed with {status}: {body}")
        self.status = status
        self.body = body


class HermesClient:
    """A typed, synchronous client for the HermesMQ REST API.

    Usable as a context manager; ``close()`` releases the connection pool.
    """

    def __init__(
        self,
        base_url: str,
        *,
        token: str | None = None,
        api_key: str | None = None,
        timeout: float = 10.0,
    ) -> None:
        headers: dict[str, str] = {}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        if api_key is not None:
            headers["X-API-Key"] = api_key
        self._http = httpx.Client(
            base_url=base_url.rstrip("/"), headers=headers, timeout=timeout
        )

    # -- lifecycle ---------------------------------------------------------

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    # -- topics ------------------------------------------------------------

    def create_topic(self, topic_id: str, labels: dict[str, str] | None = None) -> None:
        resp = self._http.post("/v1/topics", json={"topicId": topic_id, "labels": labels or {}})
        self._expect(resp, 201)

    def get_topic(self, topic_id: str) -> TopicInfo | None:
        resp = self._http.get(f"/v1/topics/{topic_id}")
        if resp.status_code == 404:
            return None
        data = self._json(resp, 200)
        return TopicInfo(topic_id=data["topicId"], labels=data.get("labels", {}))

    def update_topic(self, topic_id: str, labels: dict[str, str]) -> None:
        resp = self._http.patch(f"/v1/topics/{topic_id}", json={"labels": labels})
        self._expect(resp, 200)

    def delete_topic(self, topic_id: str) -> None:
        resp = self._http.delete(f"/v1/topics/{topic_id}")
        self._expect(resp, 204)

    def list_topics(self) -> list[TopicStats]:
        data = self._json(self._http.get("/v1/topics"), 200)
        return [
            TopicStats(
                topic_id=t["topicId"],
                published_total=t["publishedTotal"],
                deleted=t.get("deleted", False),
            )
            for t in data
        ]

    # -- publish -----------------------------------------------------------

    def publish(
        self,
        topic_id: str,
        payload: str,
        *,
        attributes: dict[str, str] | None = None,
        ttl_seconds: int | None = None,
        idempotency_key: str | None = None,
        producer_id: str | None = None,
        correlation_id: str | None = None,
    ) -> PublishResult:
        body: dict[str, Any] = {"payload": payload, "attributes": attributes or {}}
        if ttl_seconds is not None:
            body["ttlSeconds"] = ttl_seconds
        if idempotency_key is not None:
            body["idempotencyKey"] = idempotency_key
        if producer_id is not None:
            body["producerId"] = producer_id
        # The REST publish adopts the X-Correlation-Id header as the message's
        # correlation id (request-tracing); delivered verbatim to consumers.
        headers = {"X-Correlation-Id": correlation_id} if correlation_id else None
        data = self._json(
            self._http.post(f"/v1/topics/{topic_id}/messages", json=body, headers=headers),
            202,
            201,
        )
        return PublishResult(
            message_id=data["messageId"], deduplicated=data.get("deduplicated", False)
        )

    # -- subscriptions -----------------------------------------------------

    def create_subscription(self, subscription_id: str, topic_id: str) -> None:
        resp = self._http.post(
            "/v1/subscriptions", json={"subscriptionId": subscription_id, "topicId": topic_id}
        )
        self._expect(resp, 201)

    def delete_subscription(self, subscription_id: str) -> None:
        resp = self._http.delete(f"/v1/subscriptions/{subscription_id}")
        self._expect(resp, 204)

    def list_subscriptions(self) -> list[SubscriptionStats]:
        data = self._json(self._http.get("/v1/subscriptions"), 200)
        return [
            SubscriptionStats(
                subscription_id=s["subscriptionId"],
                topic_id=s["topicId"],
                backlog=s["backlog"],
                oldest_unacked_age_seconds=s["oldestUnackedAgeSeconds"],
                redelivered_total=s["redeliveredTotal"],
                dead_lettered_total=s["deadLetteredTotal"],
            )
            for s in data
        ]

    # -- consume -----------------------------------------------------------

    def pull(
        self,
        subscription_id: str,
        *,
        max_messages: int = 10,
        consumer_id: str | None = None,
    ) -> list[ReceivedMessage]:
        body: dict[str, Any] = {"max": max_messages}
        if consumer_id is not None:
            body["consumerId"] = consumer_id
        resp = self._http.post(f"/v1/subscriptions/{subscription_id}/pull", json=body)
        data = self._json(resp, 200)
        return [
            ReceivedMessage(
                ack_id=m["ackId"],
                payload=m["payload"],
                attributes=m.get("attributes", {}),
                publish_time=m["publishTime"],
                correlation_id=m.get("correlationId"),
            )
            for m in data["messages"]
        ]

    def ack(self, subscription_id: str, ack_ids: list[str]) -> AckResult:
        data = self._json(
            self._http.post(f"/v1/subscriptions/{subscription_id}/ack", json={"ackIds": ack_ids}),
            200,
        )
        return AckResult(acknowledged=data["acknowledged"], unknown=data["unknown"])

    def modify_ack_deadline(
        self, subscription_id: str, ack_ids: list[str], *, ack_deadline_seconds: int
    ) -> ModifyAckDeadlineResult:
        data = self._json(
            self._http.post(
                f"/v1/subscriptions/{subscription_id}/modifyAckDeadline",
                json={"ackIds": ack_ids, "ackDeadlineSeconds": ack_deadline_seconds},
            ),
            200,
        )
        return ModifyAckDeadlineResult(modified=data["modified"], unknown=data["unknown"])

    # -- observability -----------------------------------------------------

    def health(self) -> HealthInfo:
        data = self._json(self._http.get("/health"), 200)
        return HealthInfo(status=data["status"], service=data["service"], version=data["version"])

    # -- plumbing ----------------------------------------------------------

    def _expect(self, resp: httpx.Response, *ok: int) -> None:
        if resp.status_code not in ok:
            raise HermesClientError(resp.status_code, resp.text)

    def _json(self, resp: httpx.Response, *ok: int) -> Any:
        self._expect(resp, *ok)
        return resp.json()
