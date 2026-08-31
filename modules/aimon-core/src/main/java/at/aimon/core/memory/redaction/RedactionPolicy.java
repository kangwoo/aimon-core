package at.aimon.core.memory.redaction;

/**
 * Strategy that masks sensitive information (PII, secrets) from a free-form
 * message before it enters the memory derivation pipeline.
 *
 * <p>
 * Redaction is the last gate before content reaches the LLM-driven deriver
 * queue (design doc §6.5). Implementations must be safe to apply repeatedly:
 * for any input {@code x}, {@code redact(redact(x).getRedactedContent())}
 * must yield the same redacted content as {@code redact(x)}.
 *
 * <p>
 * Implementations must be thread-safe — a single instance is typically shared
 * across all message ingestion paths.
 */
public interface RedactionPolicy {

    /**
     * Masks sensitive information in the given content.
     *
     * <p>
     * The call is idempotent: applying the policy to already-redacted output
     * must not introduce additional matches.
     *
     * @param content
     *            the raw message content (must not be null)
     * @return a {@link RedactionResult} describing the redacted text and the
     *         matches that were applied (never null)
     * @throws NullPointerException
     *             if {@code content} is null
     */
    RedactionResult redact(String content);
}
