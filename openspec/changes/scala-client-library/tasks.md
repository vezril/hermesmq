## 1. Modularize the build (domain / server / client)

- [x] 1.1 Restructure `build.sbt` into a multi-project build: `domain` (pure, no IO deps), `server` (current service; owns JavaAppPackaging/Docker settings), `client` (depends on `domain` + pekko-http client), and a root that aggregates all and sets `publish / skip := true`
- [x] 1.2 Move `me.cference.hermesmq.domain.**` sources + tests to the `domain` module (keep package names)
- [x] 1.3 Move the remaining sources + tests (config, http, persistence, delivery, `Main`, `AppInfo`) to the `server` module; move Docker/native-packager settings to `server`
- [x] 1.4 Verify `sbt compile` and full `sbt test` are green across all modules (no behavior change); confirm `server` still builds its Docker image (`sbt server/Docker/publishLocal`)
- [x] 1.5 Confirm the dependency graph: `server` and `client` depend on `domain`, `client` does not depend on `server`, no cycles

## 2. Client — topic management (TDD)

- [x] 2.1 RED: `HermesClientSpec` binds the real `TopicAdminRoutes` (stub services) on an ephemeral port; assert `createTopic` + `getTopic` round-trip returns the labels; confirm red
- [x] 2.2 GREEN: implement client request/response models + JSON and `HermesClient.createTopic/getTopic/updateTopic/deleteTopic` over pekko-http; pass
- [x] 2.3 Edge: `getTopic` on a missing topic → `Future.successful(None)` (404 is a normal not-found); duplicate `createTopic` → failed `Future` with `HermesClientException(Conflict)`; green

## 3. Client — publish & consume (TDD)

- [x] 3.1 RED: extend the spec (binding `PubSubRoutes` with stub services) — `publish` returns a `MessageId`; `createSubscription`, `pull` (returns messages with ackId + payload), `ack` complete; confirm red
- [x] 3.2 GREEN: implement `publish`, `createSubscription`, `pull`, `ack`; pass
- [x] 3.3 Edge: publish to a missing topic and pull from a missing subscription → failed `Future` with the not-found status; green
- [x] 3.4 REFACTOR: extract shared request/response handling (status→result mapping); re-run green

## 4. Dependency-light client & release

- [x] 4.1 Assert (test or documented check) the `client` module's dependencies include `domain` + pekko-http and exclude pekko-persistence/pekko-projection/`server`
- [x] 4.2 Verify `sbt domain/publishLocal client/publishLocal` produce plain library jars (no Docker), and the root does not publish
- [x] 4.3 Update `release.yml` to publish the `domain` + `client` artifacts (in addition to the server image); update `ci.yml` snapshot publish to the library modules; keep the image steps
- [x] 4.4 Validate workflow YAML and that publishing stays gated behind tests

## 5. Documentation & final verification

- [x] 5.1 Update `README.md`: client dependency coordinates (`hermesmq-client`) and a Scala publish/consume example; note the multi-module layout
- [x] 5.2 Full `sbt test` green across modules
- [x] 5.3 Confirm every scenario in `scala-client`, the `project-scaffolding` delta, and the `ci-cd-pipeline` delta maps to a verified task
- [x] 5.4 Run `openspec validate scala-client-library`
