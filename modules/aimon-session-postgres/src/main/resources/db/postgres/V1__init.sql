-- aimon-session-postgres V1 initial schema.
-- Apply once per environment via:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f V1__init.sql
-- See docs/design/session/backends.md §3 / §7 for context.
-- The runtime never executes DDL; this file is operator-applied.
--
-- FROZEN. Every table, column and index name below is a contract with databases that were
-- migrated at some earlier deploy. Renaming one here and in the Java SQL constants at the same
-- time compiles, passes the suite (PostgresTestSupport re-applies this file to a fresh container,
-- so every tier agrees with itself) and then finds no rows in production. Java types may be
-- renamed freely -- these identifiers may not, without a migration that carries the data.
-- PostgresSchemaFreezeTest pins them; treat a failure there as a data-migration decision.

-- 분산 락의 권위 행. 한 conversation = 한 행. lease_expires_at < now()이면 stale.
CREATE TABLE IF NOT EXISTS conversation_lock (
    conversation_id   text        PRIMARY KEY,
    holder_id         text        NOT NULL,
    fencing_token     bigint      NOT NULL,
    acquired_at       timestamptz NOT NULL,
    lease_expires_at  timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS conversation_lock_lease_expires_at_idx
    ON conversation_lock (lease_expires_at);

-- 같은 conversation에 대한 fencing token을 monotonic하게 발급. 락 행이 사라져도 카운터는 유지된다.
CREATE TABLE IF NOT EXISTS conversation_lock_fence (
    conversation_id   text        PRIMARY KEY,
    next_token        bigint      NOT NULL DEFAULT 1
);

-- LISTEN/NOTIFY의 8000-byte payload 한도를 회피하기 위해, NOTIFY는 doorbell만 보내고
-- 실제 페이로드는 이 테이블에서 SELECT한다.
CREATE TABLE IF NOT EXISTS conversation_signal (
    id              bigserial   PRIMARY KEY,
    conversation_id text        NOT NULL,
    kind            text        NOT NULL,
    origin_node_id  text        NOT NULL,
    payload         jsonb       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS conversation_signal_fetch_idx
    ON conversation_signal (conversation_id, id);

CREATE INDEX IF NOT EXISTS conversation_signal_created_at_idx
    ON conversation_signal (created_at);

-- 우선순위 기반 mailbox. 한 conversation의 모든 메시지가 한 행씩 적재된다.
CREATE TABLE IF NOT EXISTS conversation_inbox (
    id               bigserial   PRIMARY KEY,
    conversation_id  text        NOT NULL,
    agent_ref        text        NOT NULL,
    priority         smallint    NOT NULL,
    payload          jsonb       NOT NULL,
    delivered_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS conversation_inbox_drain_idx
    ON conversation_inbox (conversation_id, priority, id);

-- 24h primary TTL은 Postgres가 자동 만료하지 않으므로 별도 sweeper가 expires_at 만료 행을 지운다.
CREATE TABLE IF NOT EXISTS idempotency_entry (
    key               text        PRIMARY KEY,
    conversation_id   text        NOT NULL,
    input_hash        text        NOT NULL,
    status            text        NOT NULL,
    holder_id         text,
    result_blob       jsonb,
    created_at        timestamptz NOT NULL,
    last_touched_at   timestamptz NOT NULL,
    expires_at        timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idempotency_entry_inflight_stale_idx
    ON idempotency_entry (status, last_touched_at);

CREATE INDEX IF NOT EXISTS idempotency_entry_expires_at_idx
    ON idempotency_entry (expires_at);

-- 백그라운드 subagent task 메타데이터. 한 task = 한 행. 어느 인스턴스에서 spawn 됐든
-- Task.list / status 질의가 이 공유 테이블을 통해 동일한 task 를 관찰한다 (design §5.3.2).
-- owner Principal 은 세 컬럼(type/id/display_name)으로 펼쳐 저장하고, terminal 전이 시 end_time 을 stamp 한다.
CREATE TABLE IF NOT EXISTS background_task (
    task_id            text        PRIMARY KEY,
    subagent_name      text        NOT NULL,
    description        text        NOT NULL,
    state              text        NOT NULL,
    start_time         timestamptz NOT NULL,
    end_time           timestamptz,
    output_offset      bigint      NOT NULL DEFAULT 0,
    owner_type         text,
    owner_id           text,
    owner_display_name text,
    context_id         text,
    last_heartbeat     timestamptz
);

CREATE INDEX IF NOT EXISTS background_task_state_idx
    ON background_task (state);

CREATE INDEX IF NOT EXISTS background_task_context_idx
    ON background_task (context_id);

-- 세션 레코드. 한 session = 한 행. 전사(transcript)와 side field 넷을 담는다.
-- 이 테이블만 conversation_ 이 아니라 session_ 으로 시작한다: 위 이름들은 이미 배포된 행과의 계약이지만
-- 이 테이블에는 그런 행이 없었다(PostgresSessionRecordStore 이전에는 레코드를 영속하는 코드 자체가 없었다).
-- 다만 첫 배포 이후로는 똑같이 얼어붙는다 — PostgresSchemaFreezeTest 가 핀으로 박아 둔다.
--
-- transcript 가 jsonb 가 아니라 text 인 이유: 도구 호출 입력은 모델이 만든 임의의 맵이라 문자열 안에
-- NUL(U+0000)이 섞일 수 있고, jsonb 는 그 문자를 담은 문자열을 거부한다(text 는 이스케이프된 형태로 저장).
-- 인코딩은 SessionRecordCodec 이 소유한다. side field 는 부분 원자 쓰기가 가능해야 하므로 네이티브로 둔다.
CREATE TABLE IF NOT EXISTS session_record (
    session_id               text        PRIMARY KEY,
    transcript               text,
    agent_ref                text,
    compaction_failure_count integer     NOT NULL DEFAULT 0,
    session_totals           jsonb,
    budget_override          jsonb,
    updated_at               timestamptz NOT NULL DEFAULT now()
);
