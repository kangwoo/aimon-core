package at.aimon.core.llm.tagging;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import at.aimon.core.llm.LlmCallMetadata;

/**
 * Thread-local holder that carries an ambient {@link LlmCallMetadata} for the current execution context.
 *
 * <p>
 * Used by {@link TaggingLlmClient} to attach attribution tags to LLM calls that flow through code paths which cannot
 * thread metadata explicitly (legacy callers, library-internal components, the metadata-less 4-arg
 * {@code sendMessage} overload).
 *
 * <p>
 * Scopes are stack-based via {@link #push(LlmCallMetadata)}: nested pushes shadow outer values and
 * {@link Scope#close()}
 * restores the previous top. Callers should always use try-with-resources to guarantee cleanup, otherwise leaked
 * entries
 * will surface on later reuse of the same thread (e.g. pooled executors).
 *
 * <p>
 * Example:
 *
 * <pre>
 * {@code
 * try (LlmCallMetadataHolder.Scope scope = LlmCallMetadataHolder.push(
 *         LlmCallMetadata.builder().tag("tenant", tenantId).build())) {
 *     // any LlmClient call inside this block — including the 4-arg overload —
 *     // will be tagged when wrapped by TaggingLlmClient
 *     llmClient.sendMessage(prompt, messages, tools, model);
 * }
 * }
 * </pre>
 *
 * <p>
 * This holder is not propagated across threads automatically; callers that hand work to executors must capture
 * {@link #current()} on the submitting thread and re-{@link #push(LlmCallMetadata)} on the worker thread (a thin
 * propagating {@code ExecutorService} decorator is the typical bridge).
 *
 * <p>
 * Thread-safe by virtue of {@link ThreadLocal} isolation. Stateless beyond the per-thread stack.
 */
public final class LlmCallMetadataHolder {

    private static final ThreadLocal<Deque<LlmCallMetadata>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private LlmCallMetadataHolder() {
    }

    /**
     * Returns the current ambient metadata, or {@link LlmCallMetadata#empty()} if no scope is active on this thread.
     *
     * @return the top-of-stack metadata, never null
     */
    public static LlmCallMetadata current() {
        final Deque<LlmCallMetadata> stack = STACK.get();
        if (stack.isEmpty()) {
            return LlmCallMetadata.empty();
        }
        return stack.peek();
    }

    /**
     * Returns true if any metadata scope is currently active on this thread.
     *
     * @return true when at least one entry exists on the stack
     */
    public static boolean isPresent() {
        return !STACK.get().isEmpty();
    }

    /**
     * Pushes the given metadata as the new top of the per-thread stack. The returned {@link Scope} restores the
     * previous top on {@link Scope#close()}.
     *
     * <p>
     * The supplied metadata replaces the previous top entirely — it is <b>not</b> merged. Callers that want to extend
     * the ambient context should compose first, e.g. {@code current().withTags(extra)}, then push the result.
     *
     * @param metadata
     *            the metadata to install as ambient (must not be null; pass {@link LlmCallMetadata#empty()} to
     *            temporarily clear)
     * @return a closable scope that pops this entry on close (never null)
     */
    public static Scope push(LlmCallMetadata metadata) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        final Deque<LlmCallMetadata> stack = STACK.get();
        stack.push(metadata);
        return new Scope(metadata);
    }

    /**
     * Removes all entries from the current thread's stack. Intended for test cleanup; production code should rely on
     * {@link Scope#close()} to keep the stack balanced.
     */
    public static void clear() {
        STACK.remove();
    }

    /**
     * Closable scope returned by {@link LlmCallMetadataHolder#push(LlmCallMetadata)}.
     *
     * <p>
     * Each scope tracks the metadata it pushed so {@link #close()} can detect unbalanced usage (e.g. closing scopes out
     * of order across threads or accidentally double-closing). Closing the same scope twice is a no-op.
     */
    public static final class Scope implements AutoCloseable {

        private final LlmCallMetadata pushed;
        private boolean closed;

        private Scope(LlmCallMetadata pushed) {
            this.pushed = pushed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            final Deque<LlmCallMetadata> stack = STACK.get();
            if (stack.isEmpty() || stack.peek() != pushed) {
                throw new IllegalStateException("LlmCallMetadataHolder scope closed out of order or on wrong thread");
            }
            stack.pop();
            if (stack.isEmpty()) {
                STACK.remove();
            }
        }
    }
}
