package at.aimon.core.agent.prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable ordered container of {@link SystemPromptPart} instances.
 *
 * <p>
 * Represents a structured system prompt as a list of parts. The ordering is significant: the parts are emitted in the
 * order supplied, and {@link #concatenated()} joins them in that order. Instances are immutable —
 * {@link #append(SystemPromptPart)}
 * returns a new {@code SystemPromptParts} without mutating the receiver.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     SystemPromptParts parts = SystemPromptParts.of(List.of(agentInstructions, toolRegistry, userContext));
 *
 *     // Legacy single-String fallback:
 *     String legacyPrompt = parts.concatenated();
 * }
 * </pre>
 *
 * @see SystemPromptPart
 */
public final class SystemPromptParts {

    /** Separator used by {@link #concatenated()} between part contents. */
    private static final String CONTENT_SEPARATOR = "\n\n";

    private final List<SystemPromptPart> parts;

    private SystemPromptParts(List<SystemPromptPart> parts) {
        // Defensive copy + unmodifiable wrapper to enforce immutability.
        Objects.requireNonNull(parts, "Parts cannot be null");
        List<SystemPromptPart> copy = new ArrayList<>(parts.size());
        for (SystemPromptPart part : parts) {
            copy.add(Objects.requireNonNull(part, "Part cannot be null"));
        }
        this.parts = Collections.unmodifiableList(copy);
    }

    /**
     * Creates a new {@code SystemPromptParts} from the given list.
     *
     * <p>
     * The input list is defensively copied. Later changes to the caller's list do not affect the returned instance.
     *
     * @param parts
     *            the non-null ordered list of parts (may be empty; elements must be non-null)
     * @return a new {@code SystemPromptParts}
     * @throws NullPointerException
     *             if {@code parts} is {@code null} or contains a {@code null} element
     */
    public static SystemPromptParts of(List<SystemPromptPart> parts) {
        return new SystemPromptParts(parts);
    }

    /**
     * Creates an empty {@code SystemPromptParts}.
     *
     * @return an empty container
     */
    public static SystemPromptParts empty() {
        return new SystemPromptParts(List.of());
    }

    /**
     * Returns the parts in order as an unmodifiable list.
     *
     * @return an unmodifiable, never-null list of parts
     */
    public List<SystemPromptPart> parts() {
        return parts;
    }

    /**
     * Returns the concatenation of all part contents separated by {@code "\n\n"}.
     *
     * <p>
     * Useful as a single-{@code String} fallback for callers that have not yet been updated to consume structured
     * parts. Returns an empty string when this container is empty.
     *
     * @return the joined content; never {@code null}
     */
    public String concatenated() {
        return parts.stream().map(SystemPromptPart::getContent).collect(Collectors.joining(CONTENT_SEPARATOR));
    }

    /**
     * Returns a new {@code SystemPromptParts} with the given part appended at the end.
     *
     * <p>
     * The receiver is not modified.
     *
     * @param part
     *            the non-null part to append
     * @return a new container
     * @throws NullPointerException
     *             if {@code part} is {@code null}
     */
    public SystemPromptParts append(SystemPromptPart part) {
        Objects.requireNonNull(part, "Part cannot be null");
        List<SystemPromptPart> next = new ArrayList<>(parts.size() + 1);
        next.addAll(parts);
        next.add(part);
        return new SystemPromptParts(next);
    }

    /**
     * @return {@code true} if this container holds no parts
     */
    public boolean isEmpty() {
        return parts.isEmpty();
    }

    /**
     * @return the number of parts in this container
     */
    public int size() {
        return parts.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SystemPromptParts that = (SystemPromptParts) o;
        return parts.equals(that.parts);
    }

    @Override
    public int hashCode() {
        return parts.hashCode();
    }

    @Override
    public String toString() {
        return "SystemPromptParts{size=" + parts.size() + ", parts=" + parts + '}';
    }
}
