package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.Rejection
import org.apache.pekko.actor.typed.ActorRef
import me.cference.hermesmq.domain.{SubscriptionCommand, TopicCommand, TopicId}

/** Reply sent by a persistent entity once a command has been handled.
  * `Accepted` is sent only after the resulting event is durably journaled;
  * `Rejected` carries the domain [[Rejection]] and no event is persisted.
  */
enum CommandReply:
  case Accepted
  case Rejected(rejection: Rejection)

/** A read-only view of an active topic, returned by a query. */
final case class TopicSnapshot(topicId: TopicId, labels: Map[String, String])

/** Messages accepted by a [[TopicEntity]]: a write command (persist-then-reply)
  * or a non-persisting read query. The pure `decide` stays reply-agnostic; the
  * reply address is added only at the entity boundary.
  */
sealed trait TopicEntityCommand
object TopicEntityCommand:
  final case class Submit(command: TopicCommand, replyTo: ActorRef[CommandReply]) extends TopicEntityCommand
  final case class Query(replyTo: ActorRef[Option[TopicSnapshot]])                extends TopicEntityCommand

final case class SubscriptionEntityCommand(command: SubscriptionCommand, replyTo: ActorRef[CommandReply])
