package me.cference.hermesmq.http

import org.scalatest.funsuite.AnyFunSuite

/** Tests the readiness composition: ready only when the HTTP server is bound
  * AND the persistence backend is reachable.
  */
final class ReadinessSpec extends AnyFunSuite:

  test("not ready before the server is bound") {
    val r = Readiness(persistenceHealthy = () => true)
    assert(!r.isReady)
  }

  test("ready when bound and persistence is healthy") {
    val r = Readiness(persistenceHealthy = () => true)
    r.markBound()
    assert(r.isReady)
  }

  test("not ready when persistence is unhealthy even if bound") {
    val r = Readiness(persistenceHealthy = () => false)
    r.markBound()
    assert(!r.isReady)
  }

  test("not ready after unbind (drain)") {
    val r = Readiness(persistenceHealthy = () => true)
    r.markBound()
    val _ = assert(r.isReady)
    r.markUnbound()
    assert(!r.isReady)
  }
