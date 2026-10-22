-- aimon-session-postgres V4: the idle-session waker's per-session caps (WakeLedger).
-- Apply once per environment, after V1, V2 and V3, via:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f V4__wake_ledger.sql
-- See docs/design/session/idle-wake.md §4.3. The runtime never executes DDL; this file is operator-applied.
-- Needed only when idle-session waking is on and its caps are shared between nodes (PostgresWakeLedger).
--
-- FROZEN from its first deployment on, like V1 to V3 -- PostgresWakeLedgerSchemaFreezeTest pins it.

-- 세션 하나의 깨우기 상한 상태. recent_wakes 는 최근 한 시간 안의 깨우기 시각(epoch 밀리초), consecutive 는 사용자
-- 턴 없이 연속으로 깨운 횟수, turn_count_after_wake 는 마지막 깨운 턴이 끝났을 때의 세션 턴 수(모르면 NULL),
-- outstanding 은 아직 끝나지 않은 깨운 턴의 수다(노드 여럿이 하나씩 돌릴 수 있어 플래그가 아니라 개수).
-- 갱신은 행 잠금(SELECT ... FOR UPDATE) 아래 READ COMMITTED 한 트랜잭션이다.
-- updated_at 은 오래된 행을 운영자가 지울 때의 기준이다. 깨우기를 시도할 때마다 갱신되므로, 하루 지난 행을 지우면
-- 잃는 것은 연속 횟수뿐이다 — 하루 조용했던 세션의 연속은 다시 세도 된다.
CREATE TABLE IF NOT EXISTS wake_ledger (
    session_id            text        PRIMARY KEY,
    recent_wakes          bigint[]    NOT NULL,
    consecutive           integer     NOT NULL,
    turn_count_after_wake integer,
    outstanding           integer     NOT NULL,
    updated_at            timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS wake_ledger_updated_at ON wake_ledger (updated_at);
