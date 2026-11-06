package at.aimon.core.tools.code;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The keys of operator warnings already logged, so each is logged once by its owner
 * ({@code docs/overview/scope-model.md}: state lives with its owner). {@link ExecuteCodeToolProvider} owns one for its
 * own warning and the plans' warnings of every {@link ExecuteCodeTool} it registers, so two stacks in one JVM each
 * warn, and a runtime rebuilt after eviction does not warn again. Past {@link #CAP} keys a warning is logged at DEBUG
 * instead: no behaviour depends on the set, and an unbounded stream of agent names must not grow it forever.
 *
 * <p>
 * Thread-safe.
 */
final class WarnOnce {

    /** The most keys kept. */
    static final int CAP = 1024;

    private static final Logger log = LoggerFactory.getLogger(WarnOnce.class);

    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    /**
     * Records {@code key} and says whether to log its warning at WARN.
     *
     * @param key
     *            the warning's key (agent, tool, reason)
     * @return true the first time {@code key} is seen while the set has room; false when it was seen before or the set
     *         is full (then the key is logged at DEBUG here)
     */
    boolean first(String key) {
        if (keys.contains(key)) {
            return false;
        }
        if (keys.size() >= CAP) {
            log.debug("ExecuteCode: warn-once set full ({} keys); not warning again for {}", CAP, key);
            return false;
        }
        return keys.add(key);
    }
}
