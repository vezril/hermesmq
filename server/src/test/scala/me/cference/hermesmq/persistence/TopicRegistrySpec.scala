package me.cference.hermesmq.persistence

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

object TopicRegistrySpec:
  // In-memory journal, event serialization off (we don't test wire format here).
  val config = ConfigFactory
    .parseString("pekko.persistence.testkit.events.serialize = off")
    .withFallback(EventSourcedBehaviorTestKit.config)

/** Verifies the registry routes commands to one entity per topic id. */
final class TopicRegistrySpec
    extends ScalaTestWithActorTestKit(TopicRegistrySpec.config)
    with AnyWordSpecLike
    with Matchers:

  import TopicCommand.*

  private def tid(s: String) = TopicId.from(s).toOption.get

  private val registry = spawn(TopicRegistry())

  private def create(id: TopicId, replyTo: org.apache.pekko.actor.typed.ActorRef[CommandReply]): Unit =
    registry ! TopicRegistry.Route(id, TopicEntityCommand.Submit(CreateTopic(id), replyTo))

  "TopicRegistry" should {
    "route two commands for the same id to one entity (one writer)" in {
      val id    = tid("dup")
      val probe = createTestProbe[CommandReply]()
      create(id, probe.ref)
      probe.expectMessage(CommandReply.Accepted)
      // The second create hitting the SAME entity sees existing state → rejected.
      create(id, probe.ref)
      probe.expectMessage(CommandReply.Rejected(Rejection.TopicAlreadyExists))
    }

    "route different ids to different entities" in {
      val probe = createTestProbe[CommandReply]()
      create(tid("alpha"), probe.ref)
      probe.expectMessage(CommandReply.Accepted)
      create(tid("beta"), probe.ref)
      probe.expectMessage(CommandReply.Accepted)
    }

    "resolve a new id on demand" in {
      val probe = createTestProbe[CommandReply]()
      create(tid("fresh"), probe.ref)
      probe.expectMessage(CommandReply.Accepted)
    }
  }
