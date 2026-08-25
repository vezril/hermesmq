package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.CommandReply
import me.cference.hermesmq.persistence.TopicService
import me.cference.hermesmq.persistence.TopicSnapshot
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Tests the dead-letter routing effect: republish (with headers) or drop. */
final class DeadLetterRouterSpec extends AnyWordSpec with Matchers:

  private val sub     = SubscriptionId.from("sub-1").toOption.get
  private val ackId   = AckId.from("ack-1").toOption.get
  private val dlTopic = TopicId.from("dead-letters").toOption.get
  private val origId  = MessageId.from("m-1").toOption.get
  private val t0      = Instant.parse("2026-07-07T12:00:00Z")
  private val message = Message.from(origId, "body".getBytes, Map("k" -> "v"), t0).toOption.get

  private def await[A](f: => Future[A]): A = Await.result(f, 3.seconds)

  private final class CapturingTopics extends TopicService:
    val published = new ConcurrentLinkedQueue[(TopicId, Message)]()
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
      command match
        case TopicCommand.Publish(m) => published.add((id, m)); Future.successful(CommandReply.Accepted)
        case _                       => Future.successful(CommandReply.Accepted)
    def query(id: TopicId): Future[Option[TopicSnapshot]] = Future.successful(None)

  private def route(topics: TopicService, dlt: Option[TopicId], event: SubscriptionEvent) =
    await(DeadLetterRouter.route(topics, dlt, sub, event))

  "DeadLetterRouter.route" should {
    "republish an exhausted message to the configured topic with dead-letter headers, preserving the payload" in {
      val topics = CapturingTopics()
      val _ = route(topics, Some(dlTopic), SubscriptionEvent.MessageDeadLettered(ackId, message, 5))

      val (topic, republished) = topics.published.asScala.head
      val _ = topic shouldBe dlTopic
      val _ = new String(republished.payload.toArray) shouldBe "body"
      val _ = republished.attributes("x-dead-letter-subscription") shouldBe sub.value
      val _ = republished.attributes("x-delivery-attempts") shouldBe "5"
      val _ = republished.attributes("x-original-message-id") shouldBe origId.value
      republished.attributes("k") shouldBe "v" // original attributes retained
    }

    "drop the message (no republish) when no dead-letter topic is configured" in {
      val topics = CapturingTopics()
      val _ = route(topics, None, SubscriptionEvent.MessageDeadLettered(ackId, message, 5))
      topics.published.asScala shouldBe empty
    }

    "ignore non-dead-letter events" in {
      val topics = CapturingTopics()
      val _ = route(topics, Some(dlTopic), SubscriptionEvent.MessageAcknowledged(ackId))
      topics.published.asScala shouldBe empty
    }
  }
