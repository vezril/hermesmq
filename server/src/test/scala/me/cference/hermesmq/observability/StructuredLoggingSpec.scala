package me.cference.hermesmq.observability

import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.{Level, LoggerContext}
import net.logstash.logback.encoder.LogstashEncoder
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.slf4j.Logger.ROOT_LOGGER_NAME
import spray.json.*

import scala.jdk.CollectionConverters.*

/** Tests the JSON log encoding (shared constellation field schema) and the
  * `LOG_FORMAT`-driven appender selection in `logback.xml`.
  */
final class StructuredLoggingSpec extends AnyFunSuite with Matchers:

  test("an ERROR event encodes as one-line JSON with the shared field schema and service=hermesmq") {
    val ctx     = new LoggerContext()
    val encoder = new LogstashEncoder()
    encoder.setContext(ctx)
    encoder.setCustomFields("""{"service":"hermesmq"}""")
    encoder.start()

    val logger = ctx.getLogger("me.cference.hermesmq.Sample")
    val event  = new LoggingEvent("fqcn", logger, Level.ERROR, "boom", new RuntimeException("kaboom"), Array.empty)
    event.setThreadName("worker-1")
    event.setMDCPropertyMap(java.util.Map.of("tenant", "acme"))

    val line = new String(encoder.encode(event), "UTF-8")
    line.count(_ == '\n') should be <= 1 // single-line JSON
    val json = line.parseJson.asJsObject

    json.fields("level") shouldBe JsString("ERROR")
    json.fields("service") shouldBe JsString("hermesmq")
    json.fields("logger_name") shouldBe JsString("me.cference.hermesmq.Sample")
    json.fields("thread_name") shouldBe JsString("worker-1")
    json.fields("message") shouldBe JsString("boom")
    json.fields.contains("@timestamp") shouldBe true
    json.fields.contains("stack_trace") shouldBe true // exception rendered
    json.fields("tenant") shouldBe JsString("acme")    // MDC as a top-level field
  }

  private def rootAppenderName(logFormat: Option[String]): String =
    val ctx = new LoggerContext()
    logFormat.foreach(v => ctx.putProperty("LOG_FORMAT", v))
    val configurator = new JoranConfigurator()
    configurator.setContext(ctx)
    configurator.doConfigure(getClass.getResource("/logback.xml"))
    ctx.getLogger(ROOT_LOGGER_NAME).iteratorForAppenders().asScala.toList.map(_.getName).head

  test("logback.xml selects the text appender by default") {
    rootAppenderName(None) shouldBe "text"
  }

  test("logback.xml selects the json appender when LOG_FORMAT=json") {
    rootAppenderName(Some("json")) shouldBe "json"
  }
