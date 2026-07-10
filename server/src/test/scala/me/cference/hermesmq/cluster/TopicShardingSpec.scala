package me.cference.hermesmq.cluster

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.CommandReply
import me.cference.hermesmq.persistence.TopicEntityCommand
import me.cference.hermesmq.persistence.TopicSnapshot
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.Cluster
import org.apache.pekko.cluster.typed.Join
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpecLike

/** Verifies Topic entities are sharded and reachable via `EntityRef` in a
  * single-node cluster (the cluster-wide single-writer path).
  */
final class TopicShardingSpec
    extends ScalaTestWithActorTestKit(ShardingTestConfig.config)
    with AnyWordSpecLike
    with Matchers
    with Eventually:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(15, Seconds), interval = Span(100, Millis))

  private val cluster = Cluster(system)
  cluster.manager ! Join(cluster.selfMember.address)

  private val sharding = ClusterSharding(system)
  val _ = TopicSharding.init(sharding)

  override protected def beforeAll(): Unit =
    super.beforeAll()
    val _ = eventually { cluster.selfMember.status shouldBe MemberStatus.Up }

  private def tid(s: String) = TopicId.from(s).toOption.get

  "TopicSharding" should {
    "route commands for an id to its sharded entity (one writer cluster-wide)" in {
      val id    = tid("orders")
      val ref   = TopicSharding.entityRef(sharding, id)
      val probe = createTestProbe[CommandReply]()

      ref ! TopicEntityCommand.Submit(TopicCommand.CreateTopic(id), probe.ref)
      val _ = probe.expectMessage(CommandReply.Accepted)

      // A second command for the same id reaches the same entity → rejected.
      ref ! TopicEntityCommand.Submit(TopicCommand.CreateTopic(id), probe.ref)
      probe.expectMessage(CommandReply.Rejected(Rejection.TopicAlreadyExists))
    }

    "isolate different topic ids" in {
      val probe = createTestProbe[CommandReply]()
      TopicSharding.entityRef(sharding, tid("alpha")) ! TopicEntityCommand.Submit(TopicCommand.CreateTopic(tid("alpha")), probe.ref)
      val _ = probe.expectMessage(CommandReply.Accepted)
      TopicSharding.entityRef(sharding, tid("beta")) ! TopicEntityCommand.Submit(TopicCommand.CreateTopic(tid("beta")), probe.ref)
      probe.expectMessage(CommandReply.Accepted)
    }

    "preserve the Topic|<id> persistence id so existing journals recover" in {
      // Recovery-by-replay is proven in TopicEntitySpec; sharding reuses the same
      // EventSourcedBehavior, and this asserts the persistence id is unchanged.
      me.cference.hermesmq.persistence.TopicEntity.persistenceId(tid("orders")).id shouldBe "Topic|orders"
    }

    "serve a query through the sharded entity" in {
      val id    = tid("labeled")
      val ref   = TopicSharding.entityRef(sharding, id)
      val ack   = createTestProbe[CommandReply]()
      ref ! TopicEntityCommand.Submit(TopicCommand.CreateTopic(id, Map("k" -> "v")), ack.ref)
      val _ = ack.expectMessage(CommandReply.Accepted)

      val query = createTestProbe[Option[TopicSnapshot]]()
      ref ! TopicEntityCommand.Query(query.ref)
      query.expectMessage(Some(TopicSnapshot(id, Map("k" -> "v"))))
    }
  }
