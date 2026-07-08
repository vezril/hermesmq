// ---------------------------------------------------------------------------
// HermesMQ — build definition
//
// Version is derived from Git tags by sbt-dynver (see project/plugins.sbt):
//   * on an annotated tag `vX.Y.Z`  -> clean release version `X.Y.Z`
//   * off-tag (e.g. on `development`) -> unique commit-derived pre-release,
//     which keeps every snapshot distinct in the immutable package registry.
// ---------------------------------------------------------------------------

ThisBuild / organization := "me.cference.hermesmq"
ThisBuild / scalaVersion := "3.3.4"

// Fail-fast, warnings-as-errors, and modern Scala 3 hygiene.
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-Wunused:all"
)

// Publish to GitHub Packages. The owner is resolved from the environment in CI
// (github.repository_owner) so it stays correct across forks/renames; it falls
// back to "vezril" for local `sbt publish`.
lazy val githubOwner = sys.env.getOrElse("GITHUB_REPOSITORY_OWNER", "vezril")

lazy val pekkoVersion         = "1.1.3"
lazy val pekkoHttpVersion     = "1.1.0"
lazy val pekkoJdbcVersion     = "1.1.0"
lazy val scalaTestVersion     = "3.2.19"
lazy val logbackVersion       = "1.5.16"
lazy val sprayJsonVersion     = "1.3.6"
lazy val postgresVersion      = "42.7.4"
lazy val testcontainersVersion = "1.20.4"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "hermesmq",
    Compile / mainClass := Some("me.cference.hermesmq.Main"),
    // Exclude PostgreSQL integration tests from the default `sbt test` run, so
    // CI needs no database. Opt in by setting -Dit=true:
    //   sbt -Dit=true "testOnly *PostgresPersistenceIntegrationSpec"
    Test / testOptions ++= {
      if (sys.props.get("it").contains("true")) Seq.empty
      else Seq(Tests.Argument(TestFrameworks.ScalaTest, "-l", "me.cference.hermesmq.persistence.PostgresIT"))
    },
    // --- Docker image settings (sbt-native-packager) ---
    dockerBaseImage    := "eclipse-temurin:21-jre",
    dockerExposedPorts := Seq(8080),
    // Publish to Docker Hub as docker.io/vezril/hermesmq. Username follows the
    // DOCKER_USERNAME secret in CI, defaulting to "vezril" locally.
    dockerRepository := Some("docker.io"),
    dockerUsername   := Some(sys.env.getOrElse("DOCKER_USERNAME", "vezril")),
    Docker / packageName := "hermesmq",
    // Only releases move the `latest` tag; the release workflow sets this env,
    // development snapshot pushes leave `latest` untouched.
    dockerUpdateLatest := sys.env.get("DOCKER_UPDATE_LATEST").contains("true"),
    // dynver snapshot versions contain '+', which is illegal in a Docker tag —
    // sanitize to '-' for the image tag only (publish versions are unchanged).
    Docker / version := version.value.replace('+', '-'),
    // Run as a non-root user (least privilege).
    Docker / daemonUser  := "hermes",
    dockerLabels := Map(
      "org.opencontainers.image.title"  -> "hermesmq",
      "org.opencontainers.image.source" -> "https://github.com/vezril/hermesmq"
    ),
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed"         % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream"              % pekkoVersion,
      "org.apache.pekko" %% "pekko-http"                % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json"     % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-persistence-typed"   % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-jdbc"    % pekkoJdbcVersion,
      "org.apache.pekko" %% "pekko-slf4j"               % pekkoVersion,
      "io.spray"         %% "spray-json"                % sprayJsonVersion,
      "org.postgresql"    % "postgresql"                % postgresVersion,
      "ch.qos.logback"    % "logback-classic"           % logbackVersion,
      "org.apache.pekko" %% "pekko-actor-testkit-typed"    % pekkoVersion         % Test,
      "org.apache.pekko" %% "pekko-http-testkit"           % pekkoHttpVersion     % Test,
      "org.apache.pekko" %% "pekko-persistence-testkit"    % pekkoVersion         % Test,
      "org.testcontainers" % "postgresql"                  % testcontainersVersion % Test,
      "org.scalatest"    %% "scalatest"                    % scalaTestVersion     % Test
    ),
    publishTo := Some(
      "GitHub Packages" at s"https://maven.pkg.github.com/$githubOwner/hermesmq"
    ),
    credentials += Credentials(
      "GitHub Package Registry",
      "maven.pkg.github.com",
      githubOwner,
      sys.env.getOrElse("GITHUB_TOKEN", "")
    )
  )
