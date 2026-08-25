# structured-logging

Make `correlationId` a named, first-class field of the log schema — the id the request-tracing
capability sets in the MDC — so a flow is followable in Loki across services by one field.

## MODIFIED Requirements

### Requirement: Constellation-wide log field schema

The JSON logs SHALL use the shared field shape so queries resolve identically across services:
`@timestamp`, `level`, `logger_name`, `thread_name`, `message`, `service`, `stack_trace` (on error),
and any MDC entries as top-level fields. On the JVM this SHALL be realized with
`logstash-logback-encoder`, and the `service` field SHALL be `hermesmq`.

The schema SHALL include **`correlationId`** (the shared Lexicon name) as a top-level field carried
via the MDC: any log line emitted while a correlation id is in context — a broker request, a publish,
a delivery — SHALL bear it, so one `correlationId` query resolves a flow across every service. No
encoder change is required (the encoder already promotes MDC entries to fields); the requirement is
that the id be present in the MDC on the hot paths.

#### Scenario: An error is emitted as parseable JSON
- **GIVEN** `LOG_FORMAT=json`
- **WHEN** the service logs an ERROR with an exception
- **THEN** the output is one JSON object with `level` = `ERROR`, the `service` name, the logger, the message, and a `stack_trace` field — extractable without regex over multi-line text

#### Scenario: Fields are consistent across services
- **GIVEN** two services' JSON logs
- **WHEN** they are queried in Loki by `service` and `level`
- **THEN** the same field names resolve in both — no per-service schema drift

#### Scenario: A correlated log line carries correlationId as a field
- **GIVEN** a request or delivery handled under `correlationId=req-42`
- **WHEN** the broker logs during it
- **THEN** the JSON line has a top-level `correlationId` field = `req-42`, queryable in Loki identically to the same field in other services' logs
