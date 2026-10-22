# aimon-session-postgres — operator schema bundle

This directory ships the DDL files that operators apply to their Postgres primary
to enable the four `aimon-core` session SPIs backed by Postgres. The runtime
**never** executes `CREATE TABLE` / `CREATE INDEX` / `ALTER TABLE`. Boot-time
auto-migration is explicitly out of scope.

## Apply procedure

Recommended on a fresh database (apply in order — V1, V2, V3, then V4 if you need it):

```bash
psql "$DATABASE_URL" -c 'CREATE SCHEMA IF NOT EXISTS aimon_session;'
psql "$DATABASE_URL" --set=search_path=aimon_session,public \
     -v ON_ERROR_STOP=1 -f V1__init.sql
psql "$DATABASE_URL" --set=search_path=aimon_session,public \
     -v ON_ERROR_STOP=1 -f V2__session_log_segment.sql
psql "$DATABASE_URL" --set=search_path=aimon_session,public \
     -v ON_ERROR_STOP=1 -f V3__session_record_owner.sql
psql "$DATABASE_URL" --set=search_path=aimon_session,public \
     -v ON_ERROR_STOP=1 -f V4__wake_ledger.sql
```

## Files

- `V1__init.sql` — required at deploy. Tables + minimal correctness indexes
  (`conversation_lock`, `conversation_lock_fence`, `conversation_signal`,
  `conversation_inbox`, `idempotency_entry`).
- `V2__session_log_segment.sql` — required at deploy, after V1. The
  `session_log_segment` table that `PostgresSessionLogSegmentStore` writes sealed
  session-log ranges to (`docs/design/session/session-log.md` §6).
- `V3__session_record_owner.sql` — required at deploy, after V2. Adds the nullable
  `session_record.owner` (jsonb) column that `PostgresSessionRecordStore` records a
  session's owning principal in. Apply it before rolling out the build that ships it:
  that build reads and writes the column on every `provision` and `load`, so without
  it those fail whether or not a host names an owner.
- `V4__wake_ledger.sql` — needed only with idle-session waking and a shared ledger
  (`PostgresWakeLedger`), after V3. The `wake_ledger` table holding each session's wake
  caps (`docs/design/session/idle-wake.md` §7). Every wake attempt rewrites `updated_at`,
  so deleting rows a day old resets only the streak of sessions quiet that long; do it on
  whatever schedule suits you.
- `V5__indexes.sql` — **opt-in / future-work, not yet shipped**. Operators add
  this only when concrete monitoring thresholds trigger (see
  `docs/design/session/backends.md` §3.4).

## Server settings

- `synchronous_commit = on` (mandatory — lock + idempotency invariants assume
  durable commits).
- `idle_in_transaction_session_timeout = 30s` recommended.
- `max_connections >= 100` for a 4-node cluster (each node consumes ≤ 23
  connections; see design §6.3).
