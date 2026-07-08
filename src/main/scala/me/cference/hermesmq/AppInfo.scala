package me.cference.hermesmq

/** Static metadata about the service. Kept dependency-free so it also serves as
  * a plain-source compilation check and will back the health endpoint later.
  */
object AppInfo:
  val Name: String = "hermesmq"
