package me.cference.hermesmq.http

import java.util.concurrent.atomic.AtomicBoolean

/** Composite readiness: the service is ready only when the HTTP server is bound
  * AND the persistence backend is reachable. Liveness is independent of this.
  *
  * The bind flag is toggled by the HTTP server on bind/unbind; the persistence
  * check is supplied by the caller (a cheap, cached DB probe in production).
  */
final class Readiness(persistenceHealthy: () => Boolean):
  private val bound = new AtomicBoolean(false)

  def markBound(): Unit   = bound.set(true)
  def markUnbound(): Unit = bound.set(false)

  def isReady: Boolean = bound.get() && persistenceHealthy()
