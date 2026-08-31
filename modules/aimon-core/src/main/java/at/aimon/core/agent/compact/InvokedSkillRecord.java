package at.aimon.core.agent.compact;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable record of a single Skill invocation surfaced to {@code PostCompactHook}s so they can re-attach skill
 * context after the L3 summary collapses {@code tool_use}/{@code tool_result} pairs.
 *
 * <p>
 * Snapshotted by {@link DefaultCompactionEngine} before {@code TranscriptBuffer} is replaced. Hooks such as
 * {@code InvokedSkillsRestoreHook} consume the snapshot to append a human-readable reminder of which skills the agent
 * invoked in the compacted segment.
 *
 * <p>
 * Two records are equal when both {@code name} and {@code args} are equal — the engine deduplicates on this equality so
 * repeated invocations with identical inputs collapse to a single entry while distinct argument strings each produce
 * their own record.
 */
public final class InvokedSkillRecord {

    private final String name;
    private final String args;

    private InvokedSkillRecord(String name, String args) {
        this.name = Objects.requireNonNull(name, "Skill name cannot be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Skill name cannot be blank");
        }
        this.args = args == null ? "" : args;
    }

    /** Creates a record without args (or with the empty-string args). */
    public static InvokedSkillRecord of(String name) {
        return new InvokedSkillRecord(name, "");
    }

    /** Creates a record with the given args. Null args are normalised to the empty string. */
    public static InvokedSkillRecord of(String name, String args) {
        return new InvokedSkillRecord(name, args);
    }

    public String getName() {
        return name;
    }

    /** The raw {@code args} string passed to the Skill tool, or empty if none. Never null. */
    public String getArgs() {
        return args;
    }

    /** {@link #getArgs()} wrapped in an {@link Optional}, empty when no args were supplied. */
    public Optional<String> getArgsOptional() {
        return args.isEmpty() ? Optional.empty() : Optional.of(args);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InvokedSkillRecord that)) {
            return false;
        }
        return name.equals(that.name) && args.equals(that.args);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, args);
    }

    @Override
    public String toString() {
        return args.isEmpty()
                ? "InvokedSkillRecord{" + name + "}"
                : "InvokedSkillRecord{" + name + " args=\"" + args + "\"}";
    }
}
