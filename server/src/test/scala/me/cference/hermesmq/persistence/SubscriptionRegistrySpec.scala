package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/** Verifies the subscription registry routes to one entity per id. */
final class SubscriptionRegistrySpec
    extends ScalaTestWithActorTestKit(TopicRegistrySpec.config)
    with AnyWordSpecLike
    with Matchers:

  import SubscriptionCommand.*

  private def sid(s: String) = SubscriptionId.from(s).toOption.get
  private val topicId        = TopicId.from("orders").toOption.get

  private val registry = spawn(SubscriptionRegistry())

  private def create(id: SubscriptionId, replyTo: org.apache.pekko.actor.typed.ActorRef[CommandReply]): Unit =
    registry ! SubscriptionRegistry.Route(id, SubscriptionEntityCommand.Submit(CreateSubscription(id, topicId), replyTo))

  "SubscriptionRegistry" should {
    "route two commands for the same id to one entity (one writer)" in {
      val id    = sid("dup")
      val probe = createTestProbe[CommandReply]()
      create(id, probe.ref)
      probe.expectMessage(CommandReply.Accepted)
      create(id, probe.ref)
      probe.expectMessage(CommandReply.Rejected(Rejection.SubscriptionAlreadyExists))
    }

    "route different ids to different entities" in {
      val probe = createTestProbe[CommandReply]()
      create(sid("alpha"), probe.ref)
      probe.expectMessage(CommandReply.Accepted)
      create(sid("beta"), probe.ref)
      probe.expectMessage(CommandReply.Accepted)
    }
  }
