/*
 * Copyright 2025 the original author or authors.
 */

/**
 * MongoDB-backed implementations of the memory store SPIs
 * ({@link at.aimon.core.memory.WorkspaceStore}, {@link at.aimon.core.memory.ObservationStore},
 * {@link at.aimon.core.memory.RepresentationStore}).
 *
 * <p>
 * Multi-instance ready: the MongoDB collections are the single source of truth (no in-memory
 * mirror), so multiple application instances can share one database. Observations carry a
 * {@code softDeletedAt} field — {@code merge}/{@code softDelete} set it and every query filters it
 * out, while {@code purgeSoftDeletedBefore} enforces the audit-retention window.
 *
 * <p>
 * Per the design doc §5.2 store/index split, {@link at.aimon.memory.mongodb.MongoObservationStore} is
 * metadata-only and its {@code semanticSearch} throws — compose it with
 * {@code at.aimon.core.memory.IndexedObservationStore} and an {@code ObservationIndex} (e.g. a
 * {@code KnowledgeStore}-backed one) to add search.
 *
 * <p>
 * Schema (collections + indexes) is operator-applied via {@code db/mongodb/init.js}; the runtime
 * never executes {@code createCollection}/{@code createIndex}, mirroring the operator-applied
 * {@code V1__init.sql} of {@code aimon-memory-postgres}. A derivation queue is not provided here;
 * single-node deployments reuse the in-memory queue, and full multi-instance derivation can use
 * {@code aimon-memory-postgres}'s row-locked queue.
 */
package at.aimon.memory.mongodb;
