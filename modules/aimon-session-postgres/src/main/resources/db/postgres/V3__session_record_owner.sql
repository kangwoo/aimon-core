-- aimon-session-postgres V3: the owning principal of a session record.
-- Apply once per environment, after V1 and V2, via:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f V3__session_record_owner.sql
-- See docs/design/session/backends.md. The runtime never executes DDL; this file is operator-applied.
--
-- FROZEN from its first deployment on, like V1 and V2 -- PostgresSchemaFreezeTest pins it.

-- 세션의 소유 주체(Principal). 형태는 SessionRecordCodec.encodeOwner 가 정한다
-- ({"version":1,"type":"USER","id":"u-1","displayName":"..."}). NULL 은 "소유자 없음" 이며, 이 컬럼이 생기기 전의
-- 모든 행과 소유자를 지정하지 않은 호스트의 행이 그 상태다. provision 의 같은 upsert 안에서 비어 있을 때만 채워지고
-- 덮어쓰이지 않는다(first-wins). 기록일 뿐 권한 검사가 아니다.
-- 기본값 없는 nullable 컬럼이라 ADD COLUMN 은 테이블을 다시 쓰지 않는다(메타데이터 변경만).
ALTER TABLE session_record ADD COLUMN IF NOT EXISTS owner jsonb;
