## 1. Dependency

- [x] 1.1 Add `net.logstash.logback % logstash-logback-encoder % 8.0` to the server module's `libraryDependencies` in `build.sbt` (with a version val), and confirm `sbt server/compile` resolves it

## 2. JSON logging config (TDD)

- [x] 2.1 Add a failing `StructuredLoggingSpec`: rendering an ERROR event (with a throwable and an MDC entry) through a `LogstashEncoder` configured with `service=hermesmq` produces one-line JSON carrying `level=ERROR`, `service=hermesmq`, `logger_name`, `thread_name`, `message`, a `stack_trace` field, and the MDC key as a top-level field
- [x] 2.2 Add a failing config-toggle case: loading `logback.xml` into a fresh `LoggerContext` selects the `text` appender by default and the `json` appender when `LOG_FORMAT=json`
- [x] 2.3 Rewrite `server/src/main/resources/logback.xml` with a `json` appender (`LogstashEncoder`, `<customFields>{"service":"hermesmq"}</customFields>`) and a `text` appender (the existing console pattern), selected by `<property name="LOG_APPENDER" value="${LOG_FORMAT:-text}"/>` + `<appender-ref ref="${LOG_APPENDER}"/>`; make section-2 tests green

## 3. Container default

- [x] 3.1 Set `dockerEnvVars := Map("LOG_FORMAT" -> "json")` in the server module's Docker settings so the shipped image logs JSON while local `sbt run` stays text
- [x] 3.2 Verify via `sbt server/Docker/stage` that the generated Dockerfile sets `ENV LOG_FORMAT=json` (image logs JSON; no env locally = text)

## 4. Regression & docs

- [x] 4.1 Run the full suite (`sbt test`) and confirm no regressions (log-format change is inert to existing tests)
- [x] 4.2 Document logging in the README: `LOG_FORMAT` (json in the image / text locally), the shared field schema, and `service=hermesmq`
