package me.cference.hermesmq.cluster

import com.typesafe.config.ConfigFactory
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit

/** Config for single-node cluster sharding tests: cluster provider on an
  * ephemeral remoting port, in-memory journal with event serialization off.
  */
object ShardingTestConfig:
  val config = ConfigFactory
    .parseString("""
      pekko.actor.provider = cluster
      pekko.actor.allow-java-serialization = on
      pekko.remote.artery.transport = tcp
      pekko.remote.artery.canonical.hostname = "127.0.0.1"
      pekko.remote.artery.canonical.port = 0
      pekko.persistence.testkit.events.serialize = off
    """)
    .withFallback(EventSourcedBehaviorTestKit.config)
