/**
 * MongoDB-backed implementations of the {@code aimon-core} session SPIs
 * ({@code SessionLeaseStore}, {@code SessionSignalBus}, {@code SessionInbox},
 * {@code IdempotencyStore}).
 *
 * <p>
 * See {@code docs/design/session/backends.md} for the full design. Collection / index
 * setup ships as {@code db/mongodb/init.js} and is applied manually by operators with {@code mongosh} — the
 * runtime never executes {@code createCollection} or {@code createIndex}. Change Streams require a replica set;
 * standalone MongoDB deployments are not supported.
 */
package at.aimon.session.mongodb;
