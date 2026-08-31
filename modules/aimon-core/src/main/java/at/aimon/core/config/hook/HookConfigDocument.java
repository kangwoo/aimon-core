package at.aimon.core.config.hook;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Top-level Claude Code-compatible hook configuration document.
 *
 * <p>
 * Wire shape:
 *
 * <pre>
 * {
 *   "hooks": {
 *     "PreToolUse": [
 *       {
 *         "matcher": "Bash",
 *         "hooks": [
 *           { "type": "command", "command": "echo hi", "timeout": 60 }
 *         ]
 *       }
 *     ],
 *     "PostToolUse": [...]
 *   }
 * }
 * </pre>
 *
 * <p>
 * Event-name keys are kept as raw strings here; mapping to AIMON event names happens at load time
 * (see {@code HookEventNameMapping}). Unknown event names are collected and reported by the loader.
 *
 * <p>
 * Immutable; thread-safe. The constructor copies the supplied map to defend against later mutation.
 */
public final class HookConfigDocument {

    private final Map<String, List<HookEntry>> hooks;

    /**
     * @param hooks
     *            map keyed by Claude Code event name, each value is a list of {@link HookEntry}. {@code null} is
     *            normalised to an empty map. Inner lists are copied defensively.
     */
    @JsonCreator
    public HookConfigDocument(@JsonProperty("hooks") Map<String, List<HookEntry>> hooks) {
        this.hooks = copy(hooks);
    }

    private static Map<String, List<HookEntry>> copy(Map<String, List<HookEntry>> src) {
        if (src == null || src.isEmpty()) {
            return Map.of();
        }
        final Map<String, List<HookEntry>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<HookEntry>> e : src.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * @return immutable view keyed by Claude Code event name (never null; empty when no hooks declared)
     */
    @JsonProperty("hooks")
    public Map<String, List<HookEntry>> getHooks() {
        return hooks;
    }

    /**
     * @return an empty document (no hooks)
     */
    public static HookConfigDocument empty() {
        return new HookConfigDocument(Map.of());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HookConfigDocument that)) {
            return false;
        }
        return hooks.equals(that.hooks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hooks);
    }

    @Override
    public String toString() {
        return "HookConfigDocument{events=" + hooks.keySet() + '}';
    }
}
