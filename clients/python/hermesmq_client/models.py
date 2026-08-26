"""Result dataclasses mirroring the HermesMQ REST API's response shapes."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class TopicInfo:
    """A topic's identity and label map (`GET /v1/topics/{id}`)."""

    topic_id: str
    labels: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class TopicStats:
    """A row of the topic stats listing (`GET /v1/topics`)."""

    topic_id: str
    published_total: int
    deleted: bool


@dataclass(frozen=True)
class SubscriptionStats:
    """A row of the subscription stats listing (`GET /v1/subscriptions`)."""

    subscription_id: str
    topic_id: str
    backlog: int
    oldest_unacked_age_seconds: int
    redelivered_total: int
    dead_lettered_total: int


@dataclass(frozen=True)
class PublishResult:
    """The assigned (or, when deduplicated, original) message id."""

    message_id: str
    deduplicated: bool


@dataclass(frozen=True)
class ReceivedMessage:
    """A message delivered by a pull (payload is UTF-8 text).

    ``correlation_id`` is the request-tracing id the producer set on publish,
    delivered verbatim by the broker (``None`` when the producer set none).
    """

    ack_id: str
    payload: str
    attributes: dict[str, str]
    publish_time: str
    correlation_id: str | None = None


@dataclass(frozen=True)
class AckResult:
    """Which ack ids the broker acknowledged vs no longer knew."""

    acknowledged: list[str]
    unknown: list[str]


@dataclass(frozen=True)
class ModifyAckDeadlineResult:
    """Which ack ids had their deadline modified vs were unknown."""

    modified: list[str]
    unknown: list[str]


@dataclass(frozen=True)
class HealthInfo:
    """Broker liveness as reported by `GET /health`."""

    status: str
    service: str
    version: str
