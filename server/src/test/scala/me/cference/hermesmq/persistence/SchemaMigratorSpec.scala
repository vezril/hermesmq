package me.cference.hermesmq.persistence

import org.scalatest.funsuite.AnyFunSuite

/** Unit tests for the schema migrator's resource loading — guards against the
  * bundled DDL going missing or being renamed. Applying it needs a database and
  * is covered by the tagged Postgres integration tests.
  */
final class SchemaMigratorSpec extends AnyFunSuite:

  test("the bundled schema DDL loads and defines the journal and read-model tables") {
    val ddl = SchemaMigrator.schemaDdl
    val _ = assert(ddl.nonEmpty)
    val _ = assert(ddl.contains("event_journal"))
    val _ = assert(ddl.contains("snapshot"))
    val _ = assert(ddl.contains("topic_stats"))
    assert(ddl.contains("expiring_messages"))
  }
