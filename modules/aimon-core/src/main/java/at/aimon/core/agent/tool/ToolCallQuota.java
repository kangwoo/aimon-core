package at.aimon.core.agent.tool;

import java.util.Optional;

/**
 * One execution's tool-call budget: how many calls it may run, how many it has run, and how many it asked for beyond
 * that. Without a limit it only counts.
 *
 * <p>
 * A fork builds one from its policy's {@code maxToolCalls} and admits each batch the model asks for before running it.
 * It is public and bound into the fork's tool context ({@code ToolContextKeys.TOOL_CALL_QUOTA}) so that a tool which
 * dispatches further calls itself — {@code ExecuteCode}, whose script may call tools many times — admits each of them
 * here too. Otherwise one {@code ExecuteCode} call would count once and walk past the limit
 * ({@code docs/design/tool/programmatic-tool-calling.md} decision 14).
 *
 * <p>
 * Thread-safe: the loop's thread admits batches while a script's nested calls may admit from other threads. Every
 * method is atomic with respect to the others.
 */
public final class ToolCallQuota {

    /** The cap, or {@code null} for none. */
    private final Integer limit;
    private int used;
    private int refused;

    private ToolCallQuota(Integer limit) {
        this.limit = limit;
    }

    /**
     * Returns a quota capped at {@code limit} calls.
     *
     * @param limit
     *            the most calls that may run (must not be negative)
     * @return a new quota (never null)
     * @throws IllegalArgumentException
     *             if {@code limit} is negative
     */
    public static ToolCallQuota limitedTo(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
        return new ToolCallQuota(limit);
    }

    /**
     * Returns a quota with no cap, which only counts.
     *
     * @return a new quota (never null)
     */
    public static ToolCallQuota unlimited() {
        return new ToolCallQuota(null);
    }

    /**
     * Admits up to {@code requested} calls and returns how many may run; the rest count as refused.
     *
     * @param requested
     *            the calls asked for (must not be negative)
     * @return how many of them may run, from {@code 0} to {@code requested}
     * @throws IllegalArgumentException
     *             if {@code requested} is negative
     */
    public synchronized int admit(int requested) {
        if (requested < 0) {
            throw new IllegalArgumentException("requested must not be negative: " + requested);
        }
        final int admitted = limit == null ? requested : Math.max(0, Math.min(requested, limit - used));
        used += admitted;
        refused += requested - admitted;
        return admitted;
    }

    /**
     * Returns the cap.
     *
     * @return the cap, empty when there is none
     */
    public Optional<Integer> getLimit() {
        return Optional.ofNullable(limit);
    }

    /**
     * Returns how many calls were admitted.
     *
     * @return the admitted count
     */
    public synchronized int used() {
        return used;
    }

    /**
     * Returns how many calls were refused for the cap.
     *
     * @return the refused count
     */
    public synchronized int refused() {
        return refused;
    }

    /**
     * Returns whether a call was refused for the cap — the execution asked for more than it may run.
     *
     * @return {@code true} once any call was refused
     */
    public synchronized boolean isExceeded() {
        return refused > 0;
    }

    @Override
    public synchronized String toString() {
        return "ToolCallQuota{used=" + used + ", refused=" + refused + ", limit=" + (limit == null ? "none" : limit)
                + '}';
    }
}
