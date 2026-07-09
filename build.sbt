// ---------------------------------------------------------------------------
// HermesMQ — multi-module build
//
//   domain  — pure value types & aggregates (no IO deps); shared
//   server  — the service (persistence, projection, HTTP, Main) + Docker image
//   client  — the Scala client library (typed REST wrapper)
//
// Version is derived from Git tags by sbt-dynver (project/plugins.sbt).
// ---------------------------------------------------------------------------

ThisBuild / organization := "me.cference.hermesmq"
ThisBuild / scalaVersion := "3.3.4"

ThisBuild / homepage := Some(url("https://github.com/vezril/hermesmq"))
ThisBuild / licenses := Seq("MIT" -> url("https://github.com/vezril/hermesmq/blob/main/LICENSE"))
ThisBuild / developers := List(
  Developer(
    id = "vezril",
    name = "Calvin Ference",
    email = "calvin.ference@proton.me",
    url = url("https://github.com/vezril")
  )
)

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-Wunused:all"
)

lazy val githubOwner = sys.env.getOrElse("GITHUB_REPOSITORY_OWNER", "vezril")

lazy val pekkoVersion           = "1.2.0"
lazy val pekkoHttpVersion       = "1.2.0"
lazy val pekkoJdbcVersion       = "1.1.0"       // no 1.2.x release; core evicted to 1.2.0
lazy val pekkoProjectionVersion = "1.1.0"       // no 1.2.x release; core evicted to 1.2.0
lazy val scalaTestVersion       = "3.2.19"
// The gRPC contract is sourced from the Lexicon (change adopt-lexicon-grpc-contracts);
// pinned exactly so a mismatch is a build error. This must be the-lexicon release
// that first ships `lexicon-hermes-grpc` (its add-hermes-grpc-contract, expected
// v0.4.0). Verified locally against the byte-identical publishLocal stubs.
lazy val lexiconVersion         = "0.5.0"
lazy val logbackVersion         = "1.5.16"
lazy val sprayJsonVersion       = "1.3.6"
lazy val postgresVersion        = "42.7.4"
lazy val testcontainersVersion  = "1.20.4"

// Publish library artifacts (domain, client) to GitHub Packages.
lazy val publishSettings = Seq(
  publishTo := Some("GitHub Packages" at s"https://maven.pkg.github.com/$githubOwner/hermesmq"),
  credentials += Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    githubOwner,
    sys.env.getOrElse("GITHUB_TOKEN", "")
  )
)

// Modules that are not published as libraries (root aggregate, server image).
lazy val noPublish = Seq(publish / skip := true, publishArtifact := false)

// Resolve the shared HermesMQ gRPC stubs (io.codex %% lexicon-hermes-grpc) from the
// Lexicon's GitHub Packages. Needs a GITHUB_TOKEN with read:packages locally + in CI.
ThisBuild / resolvers += "GitHub Packages — the-lexicon".at("https://maven.pkg.github.com/vezril/the-lexicon")
ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "vezril",
  sys.env.getOrElse("GITHUB_TOKEN", "")
)

// --- domain: pure value types & aggregates, no external dependencies ---------
lazy val domain = (project in file("domain"))
  .settings(publishSettings *)
  .settings(
    name := "hermesmq-domain",
    libraryDependencies += "org.scalatest" %% "scalatest" % scalaTestVersion % Test
  )

// --- server: the service + Docker image --------------------------------------
lazy val server = (project in file("server"))
  .dependsOn(domain)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(noPublish *)
  .settings(
    name := "hermesmq-server",
    Compile / mainClass := Some("me.cference.hermesmq.Main"),
    // The gRPC stubs (server power API + client) come from the Lexicon artifact;
    // no local pekko-grpc codegen runs here (the contract left this repo).
    // Exclude PostgreSQL integration tests from the default run (opt in: -Dit=true).
    Test / testOptions ++= {
      if (sys.props.get("it").contains("true")) Seq.empty
      else Seq(Tests.Argument(TestFrameworks.ScalaTest, "-l", "me.cference.hermesmq.persistence.PostgresIT"))
    },
    // --- Docker image (docker.io/calvinference/hermesmq) ---
    dockerBaseImage    := "eclipse-temurin:21-jre",
    dockerExposedPorts := Seq(8080, 8081),
    dockerRepository   := Some("docker.io"),
    dockerUsername     := Some(sys.env.getOrElse("DOCKER_USERNAME", "calvinference")),
    Docker / packageName := "hermesmq",
    dockerUpdateLatest := sys.env.get("DOCKER_UPDATE_LATEST").contains("true"),
    Docker / version   := version.value.replace('+', '-'),
    Docker / daemonUser := "hermes",
    dockerLabels := Map(
      "org.opencontainers.image.title"  -> "hermesmq",
      "org.opencontainers.image.source" -> "https://github.com/vezril/hermesmq"
    ),
    libraryDependencies ++= Seq(
      // Shared HermesMQ gRPC stubs (server power API + client) — the contract lives in the Lexicon.
      "io.codex"         %% "lexicon-hermes-grpc"           % lexiconVersion,
      "org.apache.pekko" %% "pekko-actor-typed"             % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-typed"           % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed"  % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream"                  % pekkoVersion,
      // Pin pekko-discovery to our Pekko version (pekko-grpc-runtime pulls an older one).
      "org.apache.pekko" %% "pekko-discovery"               % pekkoVersion,
      "org.apache.pekko" %% "pekko-http"                    % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json"         % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-persistence-typed"       % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-jdbc"        % pekkoJdbcVersion,
      "org.apache.pekko" %% "pekko-persistence-query"       % pekkoVersion,
      "org.apache.pekko" %% "pekko-projection-eventsourced" % pekkoProjectionVersion,
      "org.apache.pekko" %% "pekko-projection-jdbc"         % pekkoProjectionVersion,
      "org.apache.pekko" %% "pekko-slf4j"                   % pekkoVersion,
      "io.spray"         %% "spray-json"                    % sprayJsonVersion,
      "org.postgresql"    % "postgresql"                    % postgresVersion,
      "ch.qos.logback"    % "logback-classic"               % logbackVersion,
      "org.apache.pekko" %% "pekko-actor-testkit-typed"     % pekkoVersion          % Test,
      "org.apache.pekko" %% "pekko-http-testkit"            % pekkoHttpVersion      % Test,
      "org.apache.pekko" %% "pekko-persistence-testkit"     % pekkoVersion          % Test,
      "org.testcontainers" % "postgresql"                   % testcontainersVersion % Test,
      "org.scalatest"    %% "scalatest"                     % scalaTestVersion      % Test
    )
  )

// --- client: dependency-light typed REST client library ----------------------
lazy val client = (project in file("client"))
  .dependsOn(domain)
  .settings(publishSettings *)
  .settings(
    name := "hermesmq-client",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed"         % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream"              % pekkoVersion,
      "org.apache.pekko" %% "pekko-http"                % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json"     % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion     % Test,
      "org.apache.pekko" %% "pekko-http-testkit"        % pekkoHttpVersion % Test,
      "org.scalatest"    %% "scalatest"                 % scalaTestVersion % Test
    )
  )

// --- root: aggregate only, not published -------------------------------------
lazy val root = (project in file("."))
  .aggregate(domain, server, client)
  .settings(noPublish *)
  .settings(name := "hermesmq")
