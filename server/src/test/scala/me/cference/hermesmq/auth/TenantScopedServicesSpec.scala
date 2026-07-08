package me.cference.hermesmq.auth

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.{CommandReply, TopicService, TopicSnapshot}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Tests that the tenant-scoped service wrappers qualify ids so tenants sharing an
  * external id are isolated, and unqualify on the way out.
  */
final class TenantScopedServicesSpec extends AnyWordSpec with Matchers:

  private val scope = new TenantScope(TenantScope.DefaultTenant)
  private val acme  = TenantId.from("acme").toOption.get
  private val beta  = TenantId.from("beta").toOption.get

  private def await[A](f: => Future[A]): A = Await.result(f, 3.seconds)

  /** Records the (entityId, command) it receives; query echoes the queried id. */
  private final class RecordingTopics extends TopicService:
    val submitted = new ConcurrentLinkedQueue[(String, TopicCommand)]()
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
      submitted.add((id.value, command)); Future.successful(CommandReply.Accepted)
    def query(id: TopicId): Future[Option[TopicSnapshot]] =
      Future.successful(Some(TopicSnapshot(id, Map.empty)))

  "TenantScopedTopicService" should {
    "qualify the entity id and the CreateTopic command's embedded id by tenant" in {
      val rec = RecordingTopics()
      await(TenantScopedTopicService(rec, scope, acme).submit(topic("orders"), TopicCommand.CreateTopic(topic("orders"), Map.empty)))
      val (entityId, cmd) = rec.submitted.asScala.head
      entityId shouldBe "acme~orders"
      cmd shouldBe TopicCommand.CreateTopic(topic("acme~orders"), Map.empty)
    }

    "isolate two tenants using the same external id" in {
      val rec = RecordingTopics()
      await(TenantScopedTopicService(rec, scope, acme).submit(topic("orders"), TopicCommand.DeleteTopic))
      await(TenantScopedTopicService(rec, scope, beta).submit(topic("orders"), TopicCommand.DeleteTopic))
      rec.submitted.asScala.map(_._1).toSet shouldBe Set("acme~orders", "beta~orders")
    }

    "unqualify the topic id in a queried snapshot" in {
      val rec  = RecordingTopics()
      val snap = await(TenantScopedTopicService(rec, scope, acme).query(topic("orders"))).get
      snap.topicId.value shouldBe "orders" // caller sees its external id, though internally acme~orders
    }

    "leave the default tenant's ids unqualified" in {
      val rec = RecordingTopics()
      await(TenantScopedTopicService(rec, scope, TenantScope.DefaultTenant).submit(topic("orders"), TopicCommand.DeleteTopic))
      rec.submitted.asScala.head._1 shouldBe "orders"
    }
  }

  private def topic(id: String): TopicId = TopicId.from(id).toOption.get
