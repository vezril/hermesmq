package me.cference.hermesmq.domain

import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.collection.mutable

/** Tests the immutable `Message` envelope. */
final class MessageSpec extends AnyFunSuite:

  private val id = MessageId.from("m-1").toOption.get
  private val now = Instant.parse("2026-07-07T00:00:00Z")

  test("a message carries its id, payload, attributes and publish time") {
    val msg = Message.from(id, "hello".getBytes, Map("k" -> "v"), now).toOption.get
    assert(msg.id == id)
    assert(msg.payload == "hello".getBytes.toVector)
    assert(msg.attributes == Map("k" -> "v"))
    assert(msg.publishTime == now)
  }

  test("an empty payload is rejected") {
    assert(Message.from(id, Array.emptyByteArray, Map.empty, now).isLeft)
  }

  test("attributes are not affected by later mutation of the source map") {
    val source = mutable.Map("k" -> "v")
    val msg = Message.from(id, "x".getBytes, source, now).toOption.get
    source("k") = "changed"
    source("new") = "added"
    assert(msg.attributes == Map("k" -> "v"))
  }

  test("payload is not affected by later mutation of the source array") {
    val bytes = "abc".getBytes
    val msg = Message.from(id, bytes, Map.empty, now).toOption.get
    bytes(0) = 'z'.toByte
    assert(msg.payload == "abc".getBytes.toVector)
  }

  test("a message has no expireTime by default and is never expired") {
    val msg = Message.from(id, "x".getBytes, Map.empty, now).toOption.get
    assert(msg.expireTime.isEmpty)
    assert(!msg.expired(now.plusSeconds(10_000)))
  }

  test("a message with an expireTime is expired at or after that instant") {
    val expireAt = now.plusSeconds(60)
    val msg = Message.from(id, "x".getBytes, Map.empty, now, expireTime = Some(expireAt)).toOption.get
    assert(msg.expireTime == Some(expireAt))
    assert(!msg.expired(now))                    // before
    assert(!msg.expired(expireAt.minusSeconds(1)))
    assert(msg.expired(expireAt))                // at
    assert(msg.expired(expireAt.plusSeconds(1))) // after
  }

  test("a message has no idempotency key by default") {
    val msg = Message.from(id, "x".getBytes, Map.empty, now).toOption.get
    assert(msg.idempotencyKey.isEmpty)
  }

  test("a message carries a supplied idempotency key") {
    val msg = Message.from(id, "x".getBytes, Map.empty, now, idempotencyKey = Some("abc")).toOption.get
    assert(msg.idempotencyKey == Some("abc"))
  }

  test("an empty-string idempotency key normalises to None") {
    val msg = Message.from(id, "x".getBytes, Map.empty, now, idempotencyKey = Some("")).toOption.get
    assert(msg.idempotencyKey.isEmpty)
  }
