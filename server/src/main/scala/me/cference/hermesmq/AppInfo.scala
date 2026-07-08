package me.cference.hermesmq

/** Static metadata about the service. */
object AppInfo:
  val Name: String = "hermesmq"

  /** Running version. Resolved from the JAR manifest's `Implementation-Version`
    * (set to the sbt-dynver project version when packaged), falling back to
    * `"dev"` when running unpackaged (e.g. `sbt run` or tests).
    */
  val Version: String =
    Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")
