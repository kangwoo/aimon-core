package at.aimon.core.agent.quota;

import java.util.Collection;
import java.util.Map;

/**
 * The store of accumulated usage per {@link QuotaKey} — the one swap point between a single node and a cluster.
 *
 * <p>
 * <b>Application-scoped.</b> One ledger is shared by every agent runtime and every execution of the application, and
 * is not closed when a runtime is. The in-tree default keeps the counters in memory; a ledger that several nodes share
 * replaces it without any caller changing.
 *
 * <p>
 * Writes are <b>increments</b>, never absolute values: a principal's or a tenant's key is written by many nodes at
 * once, and only a delta survives that. Implementations must be thread-safe.
 */
public interface UsageLedger {

    /**
     * Adds {@code usage} to each of {@code keys}. Atomicity is required per key only — no transaction across keys.
     *
     * @param keys
     *            the keys to charge (must not be null; may be empty)
     * @param usage
     *            the usage to add to each (must not be null)
     */
    void charge(Collection<QuotaKey> keys, QuotaUsage usage);

    /**
     * Reads the accumulated usage of each key.
     *
     * @param keys
     *            the keys to read (must not be null; may be empty)
     * @return one entry per requested key, {@link QuotaUsage#zero()} for a key never charged (never null)
     */
    Map<QuotaKey, QuotaUsage> get(Collection<QuotaKey> keys);
}
