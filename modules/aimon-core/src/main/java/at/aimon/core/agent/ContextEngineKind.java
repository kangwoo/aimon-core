package at.aimon.core.agent;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Which context engine an agent's runtime is built with — the policy deciding how the LLM view of a long conversation
 * is shrunk (context-engine §10).
 *
 * <p>
 * Chosen per agent, because the runtime is agent-scoped: AGENT.md frontmatter {@code context-engine}, or a deployment
 * default (Spring {@code aimon.context.engine}). Either spelling of a value is its {@link #configValue()}.
 */
public enum ContextEngineKind {

    /** Today's behaviour: compact the whole view into one summary at the model's auto-compact threshold. */
    DEFAULT("default"),

    /**
     * Keep the head and a recent tail verbatim and summarize the middle, earlier and in smaller steps; registers the
     * {@code SessionHistory} tool. Requires the version-2 session log write format.
     */
    ROLLING("rolling");

    private final String configValue;

    ContextEngineKind(String configValue) {
        this.configValue = configValue;
    }

    /** The value as written in configuration: {@code default} or {@code rolling}. */
    public String configValue() {
        return configValue;
    }

    /**
     * Parses a configuration value, ignoring case and surrounding whitespace.
     *
     * @param value
     *            the value (must not be null)
     * @return the kind (never null)
     * @throws IllegalArgumentException
     *             if the value names no kind; the message lists the accepted values
     */
    public static ContextEngineKind fromConfig(String value) {
        Objects.requireNonNull(value, "value cannot be null");
        final String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ContextEngineKind kind : values()) {
            if (kind.configValue.equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown context engine '" + value + "'; expected one of "
                + Arrays.stream(values()).map(ContextEngineKind::configValue).collect(Collectors.joining(", ")));
    }
}
