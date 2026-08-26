package me.cference.hermesmq.tracing

import io.codex.lexicon.CorrelationNames

import java.security.SecureRandom

/** Correlation-id vocabulary and minting for the request-tracing capability.
  *
  * The names are the shared Lexicon constants ([[CorrelationNames]]) so every
  * constellation service agrees on the same field/header/metadata vocabulary.
  * HermesMQ's boundary policy is **adopt-or-mint**: its callers are trusted
  * internal services, so an inbound id is adopted; a request without one gets a
  * fresh id minted so the broker's own handling is always traceable. (Message
  * correlation is different — the broker only ever *adopts* the producer's id
  * onto the message, never mints one; see the request-tracing spec.)
  */
object Correlation:

  /** SLF4J MDC key → a top-level field on every JSON log line via the Logstash encoder. */
  val MdcKey: String = CorrelationNames.LogField

  /** HTTP/1.x header (title-case) carrying the id on requests and responses. */
  val HttpHeader: String = CorrelationNames.HttpHeader

  /** gRPC metadata / HTTP-2 header (must be lower-case). */
  val MetadataKey: String = CorrelationNames.GrpcMeta

  private val Rng      = SecureRandom()
  private val Alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
  private val Length   = 12

  /** A fresh, short, URL-safe token. Uniqueness and log-friendliness are all that matter. */
  def mint(): String =
    LazyList.continually(Alphabet(Rng.nextInt(Alphabet.length))).take(Length).mkString

  /** Adopt a non-empty inbound id, otherwise mint one (the boundary policy). */
  def adoptOrMint(inbound: Option[String]): String =
    inbound.filter(_.nonEmpty).getOrElse(mint())
