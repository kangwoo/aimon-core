package at.aimon.core.tracing;

import java.util.Objects;

/**
 * Controls how much of a span's input/output is captured: a bounded summary (the default) or the full content truncated
 * to a size cap (TRACE-02).
 *
 * <p>
 * The two capture sites that hold the actual content — {@code TracingLlmClient} (LLM response text) and
 * {@code OrcaAgentExecutor} (tool result content) — consult this policy. In {@link Mode#SUMMARY} (the default) they
 * record only counts (length, token usage), exactly as before this increment. In {@link Mode#FULL} they additionally
 * attach the content, {@link #truncate(String) truncated} to {@link #getMaxChars()} characters. The summary fields are
 * kept in both modes so the pre-truncation length is always known.
 *
 * <p>
 * Capturing content is separate from masking it: secret redaction is applied centrally by the {@link SpanRedactor}
 * wired into the tracer at storage time, regardless of this policy.
 *
 * <p>
 * Immutable value object.
 */
public final class TracePayloadPolicy {

    /** Default truncation cap (characters) for captured content in {@link Mode#FULL}. */
    public static final int DEFAULT_MAX_CHARS = 8192;

    private static final TracePayloadPolicy SUMMARY_ONLY = new TracePayloadPolicy(Mode.SUMMARY, DEFAULT_MAX_CHARS);

    /** How much of a span's input/output to capture. */
    public enum Mode {
        /** Record only summary counts (length, tokens) — the framework default; zero extra payload. */
        SUMMARY,

        /** Record the full content in addition to the summary, truncated to the configured cap. */
        FULL
    }

    private final Mode mode;
    private final int maxChars;

    private TracePayloadPolicy(Mode mode, int maxChars) {
        this.mode = Objects.requireNonNull(mode, "mode cannot be null");
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be >= 1, got: " + maxChars);
        }
        this.maxChars = maxChars;
    }

    /**
     * Returns the default policy: summary-only capture (no content), matching pre-TRACE-02 behaviour.
     *
     * @return the summary-only policy (never null)
     */
    public static TracePayloadPolicy summaryOnly() {
        return SUMMARY_ONLY;
    }

    /**
     * Returns a full-capture policy using the {@link #DEFAULT_MAX_CHARS default} truncation cap.
     *
     * @return a full-capture policy (never null)
     */
    public static TracePayloadPolicy full() {
        return new TracePayloadPolicy(Mode.FULL, DEFAULT_MAX_CHARS);
    }

    /**
     * Returns a full-capture policy truncating captured content to {@code maxChars} characters.
     *
     * @param maxChars
     *            the truncation cap (must be {@code >= 1})
     * @return a full-capture policy (never null)
     * @throws IllegalArgumentException
     *             if {@code maxChars < 1}
     */
    public static TracePayloadPolicy full(int maxChars) {
        return new TracePayloadPolicy(Mode.FULL, maxChars);
    }

    /**
     * Returns whether content (tool result / LLM response text) should be captured in addition to the summary.
     *
     * @return {@code true} in {@link Mode#FULL}, {@code false} in {@link Mode#SUMMARY}
     */
    public boolean capturesContent() {
        return mode == Mode.FULL;
    }

    /**
     * Returns the truncation cap (characters) applied to captured content.
     *
     * @return the maximum number of characters
     */
    public int getMaxChars() {
        return maxChars;
    }

    /**
     * Truncates {@code text} to {@link #getMaxChars()} characters, appending a {@code …(truncated N chars)} marker when
     * content was cut. Null-safe: returns {@code null} for a null input.
     *
     * @param text
     *            the content to truncate (may be null)
     * @return the truncated content, or {@code null} if {@code text} was null
     */
    public String truncate(String text) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        final int cut = text.length() - maxChars;
        return text.substring(0, maxChars) + "…(truncated " + cut + " chars)";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final TracePayloadPolicy that = (TracePayloadPolicy) o;
        return maxChars == that.maxChars && mode == that.mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, maxChars);
    }

    @Override
    public String toString() {
        return "TracePayloadPolicy{mode=" + mode + ", maxChars=" + maxChars + '}';
    }
}
