/**
 * Postgres-backed implementations of the {@code aimon-core} session SPIs
 * ({@code SessionLeaseStore}, {@code SessionSignalBus}, {@code SessionInbox},
 * {@code IdempotencyStore}).
 *
 * <p>
 * See {@code docs/design/session/backends.md} for the full design. DDL is shipped as
 * {@code V*.sql} resources under {@code db/postgres/} and applied manually by operators with
 * {@code psql -v ON_ERROR_STOP=1} — the runtime never executes {@code CREATE} statements.
 */
package at.aimon.session.postgres;
