package at.aimon.core.config.hook;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single matcher → handler-list entry inside the JSON {@code hooks} block.
 *
 * <p>
 * Mirrors the Claude Code shape:
 *
 * <pre>
 * { "matcher": "Bash", "hooks": [ {"type":"command", ...}, ... ] }
 * </pre>
 *
 * <p>
 * The {@code matcher} is optional &mdash; absent or blank is interpreted as &quot;match every tool&quot;. Hooks are
 * never null but can be empty (which the loader will warn about and skip at registry-build time).
 *
 * <p>
 * Immutable; thread-safe.
 */
public final class HookEntry {

    private final String matcher;
    private final List<HookHandlerSpec> handlers;

    /**
     * @param matcher
     *            the matcher expression, or {@code null}/blank for &quot;all tools&quot;
     * @param handlers
     *            the handler specs (must not be null; copied defensively)
     */
    @JsonCreator
    public HookEntry(@JsonProperty("matcher") String matcher, @JsonProperty("hooks") List<HookHandlerSpec> handlers) {
        this.matcher = matcher;
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    /**
     * @return the matcher expression, or {@code null} if the entry should match every tool
     */
    @JsonProperty("matcher")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getMatcher() {
        return matcher;
    }

    /**
     * @return the immutable handler list (never null; possibly empty)
     */
    @JsonProperty("hooks")
    public List<HookHandlerSpec> getHandlers() {
        return handlers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HookEntry that)) {
            return false;
        }
        return Objects.equals(matcher, that.matcher) && handlers.equals(that.handlers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(matcher, handlers);
    }

    @Override
    public String toString() {
        return "HookEntry{matcher='" + matcher + "', handlers=" + handlers.size() + '}';
    }
}
