package me.cference.hermesmq.http

import me.cference.hermesmq.config.ServiceConfig
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.BeforeAndAfterAll

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Await
import scala.concurrent.duration.*

/** Boots a real HTTP binding on an ephemeral port and exercises startup,
  * readiness wiring, a live request, and clean port release.
  */
final class HttpServerSpec extends AnyWordSpec with Matchers with ScalaFutures with BeforeAndAfterAll:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(100, Millis))

  private val system: ActorSystem[Nothing] =
    ActorSystem(Behaviors.empty[Nothing], "http-server-spec")

  override def afterAll(): Unit =
    system.terminate()
    Await.result(system.whenTerminated, 10.seconds)

  "HttpServer.start" should {
    "bind on an ephemeral port, mark ready, serve /health, then release the port on unbind" in {
      val readiness = new AtomicBoolean(false)
      val binding = HttpServer
        .start(system, ServiceConfig("127.0.0.1", 0), version = "boot-test", readiness = readiness)
        .futureValue

      val port = binding.localAddress.getPort
      readiness.get() shouldBe true

      val response = Http()(system)
        .singleRequest(HttpRequest(uri = s"http://127.0.0.1:$port/health"))
        .futureValue
      response.status shouldBe StatusCodes.OK
      response.discardEntityBytes(system)

      binding.unbind().futureValue

      // Port is released: a plain server socket can now bind it.
      val socket = new ServerSocket(port)
      socket.close()
      succeed
    }

    "fail fast (not hang) when the port is already in use, leaving readiness false" in {
      val firstReady = new AtomicBoolean(false)
      val first = HttpServer
        .start(system, ServiceConfig("127.0.0.1", 0), version = "boot-test", readiness = firstReady)
        .futureValue
      val busyPort = first.localAddress.getPort
      try
        val secondReady = new AtomicBoolean(false)
        val result =
          HttpServer.start(system, ServiceConfig("127.0.0.1", busyPort), version = "boot-test", readiness = secondReady)
        whenReady(result.failed) { ex =>
          ex shouldBe a[Throwable]
        }
        secondReady.get() shouldBe false
      finally first.unbind().futureValue
    }
  }
