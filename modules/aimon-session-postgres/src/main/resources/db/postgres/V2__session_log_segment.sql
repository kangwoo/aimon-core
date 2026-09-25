-- aimon-session-postgres V2: sealed session-log segments.
-- Apply once per environment, after V1, via:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f V2__session_log_segment.sql
-- See docs/design/session/session-log.md §5.6. The runtime never executes DDL; this file is operator-applied.
--
-- FROZEN from its first deployment on, like V1 -- PostgresSchemaFreezeTest pins it.

-- 세션 로그의 봉인 구간. 한 segment = 한 행. 레코드의 manifest 가 가리킬 때만 유효하다.
-- payload 가 text 인 이유는 session_record.transcript 와 같다(NUL 문자). manifest 가 payload 의 해시를
-- 들고 있으므로 바이트 그대로 저장·반환한다. created_at 은 봉인한 노드의 시계이며 GC 유예 기간과 비교된다.
CREATE TABLE IF NOT EXISTS session_log_segment (
    session_id   text        NOT NULL,
    segment_id   text        NOT NULL,
    from_seq     bigint      NOT NULL,
    to_seq       bigint      NOT NULL,
    entry_count  integer     NOT NULL,
    payload      text        NOT NULL,
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (session_id, segment_id)
);
