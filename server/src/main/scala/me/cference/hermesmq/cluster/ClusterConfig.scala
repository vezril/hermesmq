package me.cference.hermesmq.cluster

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory

import scala.jdk.CollectionConverters.*

/** Activates the Pekko cluster provider on top of the base config (which holds
  * the inert cluster settings), and applies `HERMESMQ_CLUSTER_SEEDS` overrides.
  * Keeping activation here means tests using the base config stay on the default
  * (local) provider and never form a cluster.
  */
object ClusterConfig:

  /** Return the config with the cluster provider active and env seed-node overrides applied. */
  def activate(base: Config): Config =
    val activated = ConfigFactory.parseString("pekko.actor.provider = cluster")
    val withSeeds = seedsFromEnv match
      case Some(seeds) => activated.withValue("pekko.cluster.seed-nodes", ConfigValueFactory.fromIterable(seeds.asJava))
      case None        => activated
    withSeeds.withFallback(base)

  private def seedsFromEnv: Option[List[String]] =
    sys.env
      .get("HERMESMQ_CLUSTER_SEEDS")
      .map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList)
      .filter(_.nonEmpty)
