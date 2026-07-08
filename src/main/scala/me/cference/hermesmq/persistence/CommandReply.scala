package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.Rejection
import org.apache.pekko.actor.typed.ActorRef
import me.cference.hermesmq.domain.{SubscriptionCommand, TopicCommand}

/** Reply sent by a persistent entity once a command has been handled.
  * `Accepted` is sent only after the resulting event is durably journaled;
  * `Rejected` carries the domain [[Rejection]] and no event is persisted.
  */
enum CommandReply:
  case Accepted
  case Rejected(rejection: Rejection)

/** Entity-level command envelopes: a pure domain command plus the address to
  * reply to. The pure `decide` stays reply-agnostic; the reply address is added
  * only at the entity boundary.
  */
final case class TopicEntityCommand(command: TopicCommand, replyTo: ActorRef[CommandReply])

final case class SubscriptionEntityCommand(command: SubscriptionCommand, replyTo: ActorRef[CommandReply])
