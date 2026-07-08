package me.cference.hermesmq.grpc

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration.*

/** Tests the demand-driven leasing source: it emits leased batches, backpressures
  * (leases only as demanded), throttles idle polls, and completes when the source is gone.
  */
final class MessageStreamSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers with ScalaFutures:

  private given org.apache.pekko.actor.ActorSystem = system.classicSystem
  private given scala.concurrent.ExecutionContext  = system.executionContext

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 5.seconds, interval = 50.millis)

  "MessageStream.leased" should {
    "emit leased elements from each pull" in {
      def pull(max: Int): Future[Option[List[String]]] = Future.successful(Some(List("a1", "a2")))
      val got = MessageStream.leased(pull, batch = 10, pollInterval = 50.millis).take(2).runWith(Sink.seq).futureValue
      got shouldBe Seq("a1", "a2")
    }

    "complete when a pull returns None (subscription gone)" in {
      def pull(max: Int): Future[Option[List[String]]] = Future.successful(None)
      MessageStream.leased(pull, 10, 50.millis).runWith(Sink.seq).futureValue shouldBe empty
    }

    "backpressure: lease only as far as downstream demand" in {
      val calls = AtomicInteger(0)
      def pull(max: Int): Future[Option[List[String]]] =
        Future.successful(Some(List(s"m${calls.incrementAndGet()}")))
      // Take just 1 element; with batch=1 the source should pull ~once, not drain a backlog.
      MessageStream.leased(pull, batch = 1, pollInterval = 50.millis).take(1).runWith(Sink.seq).futureValue shouldBe Seq("m1")
      calls.get() should be <= 2 // one satisfying pull, at most one in-flight — not an unbounded drain
    }

    "throttle idle polling on an empty source rather than busy-looping" in {
      val calls = AtomicInteger(0)
      def pull(max: Int): Future[Option[List[String]]] =
        Future.successful { calls.incrementAndGet(); Some(Nil) }
      // Run for a short window well under a few poll intervals; polls should be bounded.
      val done = MessageStream.leased(pull, 10, pollInterval = 100.millis).takeWithin(250.millis).runWith(Sink.seq)
      done.futureValue shouldBe empty
      calls.get() should be <= 5 // ~2-3 polls in 250ms, definitely not a spin
    }
  }
