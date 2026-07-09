package me.cference.hermesmq.domain

import java.time.Instant

/** An immutable message envelope. Payload and attributes are held as immutable
  * copies, so a caller cannot mutate a message after it is constructed. Build
  * via [[Message.from]], which validates the payload.
  *
  * @param id             unique message identifier
  * @param payload        message body (non-empty), stored immutably
  * @param attributes     string metadata, stored immutably
  * @param publishTime    when the message was published
  * @param expireTime     when the message expires (TTL); `None` = never expires
  * @param idempotencyKey optional producer dedup key; `None` = no deduplication
  */
final case class Message private (
    id: MessageId,
    payload: Vector[Byte],
    attributes: Map[String, String],
    publishTime: Instant,
    expireTime: Option[Instant],
    idempotencyKey: Option[String]
):
  /** True when this message has a TTL that has been reached at `now`. */
  def expired(now: Instant): Boolean = expireTime.exists(!_.isAfter(now))

object Message:

  /** Construct a validated message. The `payload` array and `attributes` map are
    * defensively copied, so later mutation of the caller's inputs has no effect.
    * An empty payload is rejected — a broker message must carry a body. An empty
    * idempotency key is normalised to `None` (treated as no key).
    */
  def from(
      id: MessageId,
      payload: Array[Byte],
      attributes: collection.Map[String, String],
      publishTime: Instant,
      expireTime: Option[Instant] = None,
      idempotencyKey: Option[String] = None
  ): Either[ValidationError, Message] =
    if payload.isEmpty then Left(ValidationError("Message payload must not be empty"))
    else Right(Message(id, payload.toVector, attributes.toMap, publishTime, expireTime, idempotencyKey.filter(_.nonEmpty)))
