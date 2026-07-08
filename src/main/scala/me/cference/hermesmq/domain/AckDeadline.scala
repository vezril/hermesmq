package me.cference.hermesmq.domain

import scala.concurrent.duration.FiniteDuration

/** How long a delivered message may remain unacknowledged. Non-negative by
  * construction. Build via [[AckDeadline.from]].
  */
final case class AckDeadline private (duration: FiniteDuration)

object AckDeadline:

  def from(duration: FiniteDuration): Either[ValidationError, AckDeadline] =
    if duration.length >= 0 then Right(AckDeadline(duration))
    else Left(ValidationError(s"AckDeadline must be non-negative, was $duration"))
