# aimon-session-postgres — operator schema bundle

This directory ships the DDL files that operators apply to their Postgres primary
to enable the four `aimon-core` session SPIs backed by Postgres. The runtime
**never** executes `CREATE TABLE` / `CREATE INDEX` / `ALTER TABLE`. Boot-time
auto-migration is explicitly out of scope.

## Apply procedure

Recommended on a fresh database (V1 module ship — V1 only):

```bash
psql "$DATABASE_URL" -c 'CREATE SCHEMA IF NOT EXISTS aimon_session;'
psql "$DATABASE_URL" --set=search_path=aimon_session,public \
     -v ON_ERROR_STOP=1 -f V1__init.sql
```

## Files

- `V1__init.sql` — required at deploy. Tables + minimal correctness indexes
  (`conversation_lock`, `conversation_lock_fence`, `conversation_signal`,
  `conversation_inbox`, `idempotency_entry`).
- `V2__indexes.sql` — **opt-in / future-work, not yet shipped**. Operators add
  this only when concrete monitoring thresholds trigger (see
  `docs/design/session/backends.md` §7.4).

## Server settings

- `synchronous_commit = on` (mandatory — lock + idempotency invariants assume
  durable commits).
- `idle_in_transaction_session_timeout = 30s` recommended.
- `max_connections >= 100` for a 4-node cluster (each node consumes ≤ 23
  connections; see design §6.3).
