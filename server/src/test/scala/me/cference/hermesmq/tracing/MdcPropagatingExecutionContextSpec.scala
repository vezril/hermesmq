package me.cference.hermesmq.tracing

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.MDC

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** The MDC-propagating EC: a value set on the submitting thread must be visible
  * on the worker thread, survive chained `Future` hops, and NOT leak onto a
  * pool thread once the task completes.
  */
final class MdcPropagatingExecutionContextSpec extends AnyWordSpec with Matchers with ScalaFutures:

  // Single-thread delegate so "does the value leak afterwards?" is observable
  // on the very same thread the task ran on.
  private def singleThreadEc(): (ExecutionContext, () => Unit) =
    val pool = Executors.newSingleThreadExecutor()
    (ExecutionContext.fromExecutor(pool), () => { val _ = pool.shutdownNow() })

  "MdcPropagatingExecutionContext" should {

    "carry the submit-time MDC onto the worker thread" in {
      val (delegate, close) = singleThreadEc()
      try
        val ec = MdcPropagatingExecutionContext(delegate)
        MDC.put(Correlation.MdcKey, "abc123")
        try Future(Option(MDC.get(Correlation.MdcKey)))(ec).futureValue shouldBe Some("abc123")
        finally MDC.remove(Correlation.MdcKey)
      finally close()
    }

    "keep the value across chained Future hops" in {
      val (delegate, close) = singleThreadEc()
      try
        given ec: ExecutionContext = MdcPropagatingExecutionContext(delegate)
        MDC.put(Correlation.MdcKey, "deep")
        try
          Future(())
            .map(_ => ())
            .flatMap(_ => Future(()))
            .map(_ => Option(MDC.get(Correlation.MdcKey)))
            .futureValue shouldBe Some("deep")
        finally MDC.remove(Correlation.MdcKey)
      finally close()
    }

    "not leak the value onto the worker thread after the task completes" in {
      val (delegate, close) = singleThreadEc()
      try
        val ec = MdcPropagatingExecutionContext(delegate)
        MDC.put(Correlation.MdcKey, "tenant-a")
        try { val _ = Future(())(ec).futureValue }
        finally MDC.remove(Correlation.MdcKey)
        // A later task with NO id set must see a clean MDC on that reused thread.
        Future(Option(MDC.get(Correlation.MdcKey)))(ec).futureValue shouldBe None
      finally close()
    }
  }
