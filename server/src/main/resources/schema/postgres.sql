-- HermesMQ persistence schema for PostgreSQL (pekko-persistence-jdbc, default/non-legacy).
-- Apply this before starting the service against a fresh database.

CREATE TABLE IF NOT EXISTS public.event_journal (
    ordering            BIGSERIAL,
    persistence_id      VARCHAR(255)          NOT NULL,
    sequence_number     BIGINT                NOT NULL,
    deleted             BOOLEAN DEFAULT FALSE NOT NULL,

    writer              VARCHAR(255)          NOT NULL,
    write_timestamp     BIGINT,
    adapter_manifest    VARCHAR(255),

    event_ser_id        INTEGER               NOT NULL,
    event_ser_manifest  VARCHAR(255)          NOT NULL,
    event_payload       BYTEA                 NOT NULL,

    meta_ser_id         INTEGER,
    meta_ser_manifest   VARCHAR(255),
    meta_payload        BYTEA,

    PRIMARY KEY (persistence_id, sequence_number)
);

CREATE UNIQUE INDEX IF NOT EXISTS event_journal_ordering_idx ON public.event_journal (ordering);

CREATE TABLE IF NOT EXISTS public.event_tag (
    event_id            BIGINT,
    tag                 VARCHAR(256),
    PRIMARY KEY (event_id, tag),
    CONSTRAINT fk_event_journal
        FOREIGN KEY (event_id)
        REFERENCES event_journal (ordering)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.snapshot (
    persistence_id          VARCHAR(255) NOT NULL,
    sequence_number         BIGINT       NOT NULL,
    created                 BIGINT       NOT NULL,

    snapshot_ser_id         INTEGER      NOT NULL,
    snapshot_ser_manifest   VARCHAR(255) NOT NULL,
    snapshot_payload        BYTEA        NOT NULL,

    meta_ser_id             INTEGER,
    meta_ser_manifest       VARCHAR(255),
    meta_payload            BYTEA,

    PRIMARY KEY (persistence_id, sequence_number)
);

-- Pekko Projection JDBC offset store (delivery projection resumes from here).
CREATE TABLE IF NOT EXISTS public.pekko_projection_offset_store (
    projection_name VARCHAR(255) NOT NULL,
    projection_key  VARCHAR(255) NOT NULL,
    current_offset  VARCHAR(255) NOT NULL,
    manifest        VARCHAR(32)  NOT NULL,
    mergeable       BOOLEAN      NOT NULL,
    last_updated    BIGINT       NOT NULL,
    PRIMARY KEY (projection_name, projection_key)
);
CREATE INDEX IF NOT EXISTS projection_name_index ON public.pekko_projection_offset_store (projection_name);

CREATE TABLE IF NOT EXISTS public.pekko_projection_management (
    projection_name VARCHAR(255) NOT NULL,
    projection_key  VARCHAR(255) NOT NULL,
    paused          BOOLEAN      NOT NULL,
    last_updated    BIGINT       NOT NULL,
    PRIMARY KEY (projection_name, projection_key)
);

-- Durable, cluster-shared topic->subscriptions read model (Feature 10b),
-- maintained by a projection over SubscriptionCreated events; queried by the
-- delivery fan-out so a message reaches subscriptions created on any node.
CREATE TABLE IF NOT EXISTS public.topic_subscriptions (
    topic_id        VARCHAR(255) NOT NULL,
    subscription_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (topic_id, subscription_id)
);

-- Durable, cluster-shared outstanding-lease read model (redelivery/ack-deadline),
-- maintained by a projection over subscription lease-lifecycle events; queried by
-- the redelivery sweeper to find overdue leases without scanning the entities.
CREATE TABLE IF NOT EXISTS public.outstanding_leases (
    subscription_id VARCHAR(255) NOT NULL,
    ack_id          VARCHAR(255) NOT NULL,
    deadline        TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (subscription_id, ack_id)
);
CREATE INDEX IF NOT EXISTS outstanding_leases_deadline_idx ON public.outstanding_leases (deadline);
