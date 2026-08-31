package at.aimon.core.tracing;

/**
 * Masks sensitive values in a span's {@code inputs}/{@code outputs} snapshot before it is stored or exported
 * (TRACE-02).
 *
 * <p>
 * Tool inputs are captured verbatim (a {@code Bash(token=...)} argument, a credential passed to an HTTP tool), and
 * full-content capture ({@link TracePayloadPolicy.Mode#FULL}) may add tool results / LLM text. A redactor is applied by
 * the tracer just before {@code close()} records the span, so masking is uniform across every span type regardless of
 * the capture policy.
 *
 * <p>
 * {@link #redact(Object)} must be non-destructive (return a masked copy, never mutate the argument) and should never
 * throw — a redactor that throws causes the offending span to be dropped (fail-safe), which is the safer failure mode
 * than storing unmasked content.
 */
public interface SpanRedactor {

    /**
     * Returns a masked copy of {@code payload} (typically the {@code inputs} or {@code outputs} snapshot), or
     * {@code payload} itself when nothing needs masking.
     *
     * @param payload
     *            the input/output snapshot to redact (may be null)
     * @return the masked payload, or {@code null} if {@code payload} was null
     */
    Object redact(Object payload);

    /**
     * Returns a no-op redactor that passes payloads through unchanged. The framework default (no masking).
     *
     * @return the no-op {@link SpanRedactor}
     */
    static SpanRedactor noop() {
        return payload -> payload;
    }

    /**
     * Returns the default key-pattern redactor that masks map entries whose key looks sensitive
     * ({@code *token*}, {@code *secret*}, {@code *password*}, {@code *credential*}, {@code *apikey*}/{@code *api_key*},
     * {@code *authorization*}), recursing into nested maps and lists.
     *
     * @return the default {@link SpanRedactor}
     */
    static SpanRedactor defaultRedactor() {
        return at.aimon.core.tracing.impl.KeyPatternSpanRedactor.INSTANCE;
    }
}
