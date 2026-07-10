// Derives the project version from Git tags (SemVer, tag-driven releases).
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.0")

// Packages the service as a runnable app and a Docker image.
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")

// Statement coverage reporting (scoverage) — ungated report in CI.
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.2.2")

// Linting/refactoring rules (scalafix) — CI-gated via `scalafixAll --check`.
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.13.0")

// (gRPC codegen removed: the Hermes gRPC stubs now come from the Lexicon artifact
// io.codex %% lexicon-hermes-grpc — change adopt-lexicon-grpc-contracts.)
