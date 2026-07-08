package me.cference.hermesmq.domain

/** Why a command was refused when validated against current aggregate state.
  * Distinct from [[ValidationError]], which is a failure to build a value type.
  */
enum Rejection:
  case TopicAlreadyExists
  case TopicNotFound
  case SubscriptionAlreadyExists
  case SubscriptionNotFound
  case UnknownAckId(ackId: AckId)
