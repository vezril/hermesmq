package me.cference.hermesmq.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite

/** Tests that cluster activation flips the provider and keeps the split-brain
  * resolver configured.
  */
final class ClusterConfigSpec extends AnyFunSuite:

  private val activated = ClusterConfig.activate(ConfigFactory.load())

  test("activate sets the cluster provider") {
    assert(activated.getString("pekko.actor.provider") == "cluster")
  }

  test("the base config keeps the default (local) provider so tests do not cluster") {
    assert(ConfigFactory.load().getString("pekko.actor.provider") != "cluster")
  }

  test("a split-brain resolver downing provider is configured") {
    assert(activated.getString("pekko.cluster.downing-provider-class").contains("SplitBrainResolver"))
  }

  test("artery remoting is configured") {
    assert(activated.getString("pekko.remote.artery.transport") == "tcp")
    assert(activated.hasPath("pekko.remote.artery.canonical.port"))
  }
