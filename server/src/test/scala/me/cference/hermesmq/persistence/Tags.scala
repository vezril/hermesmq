package me.cference.hermesmq.persistence

import org.scalatest.Tag

/** Tags a test as a PostgreSQL integration test. Excluded from the default
  * `sbt test` run (see build.sbt) so CI needs no database; run explicitly with
  * Docker available.
  */
object PostgresIT extends Tag("me.cference.hermesmq.persistence.PostgresIT")
