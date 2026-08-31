-- aimon-memory-postgres V1 initial schema.
-- Apply once per environment via:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f V1__init.sql
-- See docs/design/honcho-memory-integration.md §5 for context.
-- The runtime never executes DDL; this file is operator-applied.
--
-- Vector data is NOT stored here. Embeddings live in the KnowledgeStore
-- backend (e.g., aimon-knowledge-opensearch). This module persists metadata
-- + the outbox queue that feeds KnowledgeStore upserts (design §5.2).

-- 워크스페이스: 모든 메모리 객체의 멀티 테넌트 격리 루트.
CREATE TABLE IF NOT EXISTS mem_workspace (
    id            text        PRIMARY KEY,
    display_name  text        NOT NULL,
    metadata      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL
);

-- Observation 메타데이터.
-- subject/observer 모두 같은 workspace 안에 있어야 한다 (도메인 불변식).
-- 임베딩은 KnowledgeStore 가 담당 — 본 테이블에는 벡터 컬럼 없음.
-- soft_deleted_at 은 Reconciler.merge() loser 보존용 (30일 audit window).
CREATE TABLE IF NOT EXISTS mem_observation (
    workspace_id                   text             NOT NULL REFERENCES mem_workspace(id) ON DELETE CASCADE,
    local_id                       text             NOT NULL,
    subject_principal_type         text             NOT NULL,
    subject_principal_id           text             NOT NULL,
    subject_principal_display_name text             NOT NULL,
    observer_principal_type        text             NOT NULL,
    observer_principal_id          text             NOT NULL,
    observer_principal_display_name text            NOT NULL,
    content                        text             NOT NULL,
    obs_type                       text             NOT NULL,
    source_message_ids             jsonb            NOT NULL DEFAULT '[]'::jsonb,
    confidence                     double precision NOT NULL,
    metadata                       jsonb            NOT NULL DEFAULT '{}'::jsonb,
    created_at                     timestamptz      NOT NULL,
    soft_deleted_at                timestamptz,
    PRIMARY KEY (workspace_id, local_id),
    CONSTRAINT mem_observation_confidence_range CHECK (confidence >= 0.0 AND confidence <= 1.0),
    CONSTRAINT mem_observation_obs_type CHECK (obs_type IN ('EXPLICIT', 'DEDUCTIVE'))
);

-- Subject 단위 조회 (findBySubject, count, semanticSearch hydration) 가속.
CREATE INDEX IF NOT EXISTS mem_observation_subject_idx
    ON mem_observation (workspace_id, subject_principal_type, subject_principal_id, created_at DESC)
    WHERE soft_deleted_at IS NULL;

-- Dreamer findByConfidenceBelow 가속.
CREATE INDEX IF NOT EXISTS mem_observation_subject_confidence_idx
    ON mem_observation (workspace_id, subject_principal_type, subject_principal_id, confidence)
    WHERE soft_deleted_at IS NULL;

-- Representation 스냅샷.
-- payload_blob 은 LLM 컨텍스트 주입을 위한 사전 합성 결과 (관찰 목록 + summary).
-- observer_principal_* 가 NULL 이면 global representation, session_id 가 NULL 이면 cross-session.
CREATE TABLE IF NOT EXISTS mem_representation (
    id                              bigserial    PRIMARY KEY,
    workspace_id                    text         NOT NULL REFERENCES mem_workspace(id) ON DELETE CASCADE,
    subject_principal_type          text         NOT NULL,
    subject_principal_id            text         NOT NULL,
    subject_principal_display_name  text         NOT NULL,
    observer_principal_type         text,
    observer_principal_id           text,
    observer_principal_display_name text,
    session_id                      text,
    summary                         text         NOT NULL,
    token_count                     integer      NOT NULL,
    observation_local_ids           jsonb        NOT NULL DEFAULT '[]'::jsonb,
    payload_blob                    jsonb        NOT NULL,
    generated_at                    timestamptz  NOT NULL,
    CONSTRAINT mem_representation_token_count_nonneg CHECK (token_count >= 0),
    CONSTRAINT mem_representation_observer_pair CHECK (
        (observer_principal_type IS NULL AND observer_principal_id IS NULL AND observer_principal_display_name IS NULL)
        OR (observer_principal_type IS NOT NULL AND observer_principal_id IS NOT NULL
            AND observer_principal_display_name IS NOT NULL)
    )
);

-- "최신 global representation" 조회 가속.
CREATE INDEX IF NOT EXISTS mem_representation_latest_global_idx
    ON mem_representation (workspace_id, subject_principal_type, subject_principal_id, generated_at DESC)
    WHERE observer_principal_id IS NULL;

-- "최신 (subject, observer, session) representation" 조회 가속.
CREATE INDEX IF NOT EXISTS mem_representation_latest_local_idx
    ON mem_representation (workspace_id, subject_principal_type, subject_principal_id,
                           observer_principal_type, observer_principal_id, session_id, generated_at DESC)
    WHERE observer_principal_id IS NOT NULL;

-- DerivationWorkUnit row-lock 클레임. (workspace, session, observer) triple 당 하나의 워커만.
-- 멀티 인스턴스 환경에서 SELECT ... FOR UPDATE SKIP LOCKED 로 클레임.
-- workspace 삭제 시 진행 중인 클레임도 함께 정리되도록 ON DELETE CASCADE.
CREATE TABLE IF NOT EXISTS mem_active_work_unit (
    workspace_id            text        NOT NULL REFERENCES mem_workspace(id) ON DELETE CASCADE,
    session_id              text        NOT NULL,
    observer_principal_type text        NOT NULL,
    observer_principal_id   text        NOT NULL,
    holder_id               text        NOT NULL,
    claimed_at              timestamptz NOT NULL,
    expires_at              timestamptz NOT NULL,
    PRIMARY KEY (workspace_id, session_id, observer_principal_type, observer_principal_id)
);

-- 만료된 클레임 청소용 인덱스.
CREATE INDEX IF NOT EXISTS mem_active_work_unit_expires_at_idx
    ON mem_active_work_unit (expires_at);

-- KnowledgeStore 임베딩 동기화 outbox (§5.2 분산 쓰기 정책).
-- ObservationStore 가 mem_observation 과 동일 트랜잭션에서 INSERT.
-- 별도 워커가 SELECT ... FOR UPDATE SKIP LOCKED 로 클레임하고
-- KnowledgeStore.upsert/delete 호출 후 행 삭제.
--
-- workspace_id / observation_local_id 에 FK 를 두지 않는 것은 의도적이다:
-- (1) DELETE outbox row 는 mem_observation 행이 이미 삭제된 뒤에도 KnowledgeStore
--     에 도달해야 하므로 dangling reference 가 정상 시나리오이다.
-- (2) workspace 삭제 시 mem_observation CASCADE 가 끝난 뒤 잔여 outbox row 는
--     observationExists() 체크로 self-heal 된다 (KnowledgeStoreOutboxRelay 참고).
CREATE TABLE IF NOT EXISTS mem_outbox (
    id                  bigserial    PRIMARY KEY,
    workspace_id        text         NOT NULL,
    observation_local_id text        NOT NULL,
    subject_key         text         NOT NULL,
    operation           text         NOT NULL,
    payload             text,
    attempt_count       integer      NOT NULL DEFAULT 0,
    last_error          text,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    next_attempt_at     timestamptz  NOT NULL DEFAULT now(),
    claimed_by          text,
    claimed_until       timestamptz,
    CONSTRAINT mem_outbox_operation CHECK (operation IN ('UPSERT', 'DELETE'))
);

-- Outbox drain 인덱스: next_attempt_at 순서로 정렬된 행을 빠르게 스캔.
-- claimed_until 필터는 런타임 쿼리에서 적용 (now() 는 IMMUTABLE 이 아니므로 partial index 술부에 못 씀).
CREATE INDEX IF NOT EXISTS mem_outbox_drain_idx
    ON mem_outbox (next_attempt_at, id);

-- 같은 (workspace, observation) 에 대한 outbox 중복 누적 시 합치기 위한 인덱스.
CREATE INDEX IF NOT EXISTS mem_outbox_observation_idx
    ON mem_outbox (workspace_id, observation_local_id);
