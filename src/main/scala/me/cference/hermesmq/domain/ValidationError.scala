package me.cference.hermesmq.domain

/** A failure to construct a domain value from raw input. Distinct from
  * [[Rejection]], which describes an invalid command against valid state.
  */
final case class ValidationError(message: String)
