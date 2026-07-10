package me.cference.hermesmq.cluster

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.CommandReply
import me.cference.hermesmq.persistence.SubscriptionEntityCommand
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

/** Verifies Subscription entities are sharded and reachable via `EntityRef`. */
final class SubscriptionShardingSpec
    extends ScalaTestWithActorTestKit(ShardingTestConfig.config)
    with AnyWordSpecLike
    with Matchers
    with Eventually:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(15, Seconds), interval = Span(100, Millis))

  private val cluster = Cluster(system)
  cluster.manager ! Join(cluster.selfMember.address)

  private val sharding = ClusterSharding(system)
  val _ = SubscriptionSharding.init(sharding)

  override protected def beforeAll(): Unit =
    super.beforeAll()
    val _ = eventually { cluster.selfMember.status shouldBe MemberStatus.Up }

  "SubscriptionSharding" should {
    "route commands for an id to its sharded entity (one writer cluster-wide)" in {
      val subId = SubscriptionId.from("s1").toOption.get
      val topic = TopicId.from("orders").toOption.get
      val ref   = SubscriptionSharding.entityRef(sharding, subId)
      val probe = createTestProbe[CommandReply]()

      ref ! SubscriptionEntityCommand.Submit(SubscriptionCommand.CreateSubscription(subId, topic), probe.ref)
      val _ = probe.expectMessage(CommandReply.Accepted)
      ref ! SubscriptionEntityCommand.Submit(SubscriptionCommand.CreateSubscription(subId, topic), probe.ref)
      probe.expectMessage(CommandReply.Rejected(Rejection.SubscriptionAlreadyExists))
    }
  }
