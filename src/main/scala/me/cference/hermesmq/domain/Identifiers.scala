package me.cference.hermesmq.domain

/** Type-safe, non-empty identifiers. Each is a zero-overhead Scala 3 opaque type
  * over `String` with a validating smart constructor, so distinct id kinds are
  * not interchangeable and a blank value can never be constructed.
  */

private def nonBlank[A](raw: String, label: String)(wrap: String => A): Either[ValidationError, A] =
  if raw.trim.nonEmpty then Right(wrap(raw))
  else Left(ValidationError(s"$label must not be blank"))

opaque type TopicId = String
object TopicId:
  def from(raw: String): Either[ValidationError, TopicId] = nonBlank(raw, "TopicId")(identity)
  extension (id: TopicId) def value: String = id

opaque type SubscriptionId = String
object SubscriptionId:
  def from(raw: String): Either[ValidationError, SubscriptionId] = nonBlank(raw, "SubscriptionId")(identity)
  extension (id: SubscriptionId) def value: String = id

opaque type MessageId = String
object MessageId:
  def from(raw: String): Either[ValidationError, MessageId] = nonBlank(raw, "MessageId")(identity)
  extension (id: MessageId) def value: String = id

opaque type AckId = String
object AckId:
  def from(raw: String): Either[ValidationError, AckId] = nonBlank(raw, "AckId")(identity)
  extension (id: AckId) def value: String = id
