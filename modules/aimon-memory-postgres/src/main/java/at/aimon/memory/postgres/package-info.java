/**
 * PostgreSQL-backed implementations of the memory-layer storage interfaces
 * defined in {@code at.aimon.core.memory}. Maps {@code mem_workspace},
 * {@code mem_observation}, {@code mem_representation}, {@code mem_active_work_unit},
 * and {@code mem_outbox} (KnowledgeStore embedding-sync outbox per design §5.2).
 *
 * <p>
 * Vector data lives in the {@code at.aimon.core.knowledge.KnowledgeStore}
 * backend — this module only persists metadata + the outbox queue.
 *
 * <p>
 * Schema is operator-applied via Flyway from
 * {@code src/main/resources/db/postgres/V1__init.sql}; production code never
 * executes DDL.
 */
package at.aimon.memory.postgres;
