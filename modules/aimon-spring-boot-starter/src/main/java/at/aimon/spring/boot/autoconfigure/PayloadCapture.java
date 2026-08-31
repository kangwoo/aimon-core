package at.aimon.spring.boot.autoconfigure;

/**
 * How much of a traced span's content is recorded, selected by {@code aimon.tracing.payload-capture}.
 *
 * <p>
 * <b>Two values, not three.</b> The property tree was sketched with {@code none | truncated | full}, and the
 * middle one has no counterpart in the framework: {@code TracePayloadPolicy} captures either summary counts or
 * the content, and content capture is <em>always</em> truncated to a character cap. There is no untruncated
 * mode for {@code truncated} to be distinguished from, so a third constant would be a value the IDE offers, the
 * binder accepts, and nothing downstream can tell apart from {@link #FULL}. The cap lives on its own property,
 * {@code aimon.tracing.max-chars}.
 *
 * <p>
 * Both values are honoured, but only with {@code aimon.tracing.enabled=true} — this enum selects what a span
 * records, not whether spans exist. Setting it under {@code enabled=false} is refused by name rather than
 * ignored, because a deployment that asked for payload capture and got no tracing at all has been told nothing.
 */
public enum PayloadCapture {

    /**
     * Record summary counts only — content lengths and token usage, no message or tool-result text. Named for
     * what it does not do: the counts are not payloads, and they are recorded in both modes.
     */
    NONE,

    /**
     * Record the content in addition to the counts, truncated to the tracer's character cap. Everything the
     * agent read and wrote reaches the tracing backend, secrets included unless a redactor removes them, which
     * is why this is not a default anywhere.
     */
    FULL
}
