package at.aimon.core.memory.dialectic;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.memory.redaction.RedactionResult;

/**
 * A {@link DialecticEngine} that masks the question before it reaches the engine underneath.
 *
 * <p>
 * <b>This closes the one outgoing gate that never existed.</b> Of the four tiers that carry
 * caller-written free text outwards, three were already masked somewhere — ingest inside the derivation queue, observed
 * content and search queries inside their tools. CHAT was not: {@code MemoryChatTool} has no redaction policy at all,
 * and the model's question travelled verbatim into {@link DialecticQuery#getQuestion()}. That question is written by
 * the model out of the conversation it is having, so it is exactly as likely to carry a secret as anything else in it.
 *
 * <p>
 * It exists as its own type because {@link DialecticEngine} predates the tier SPI and is promised unchanged, so the
 * decorator cannot be folded into a generic one.
 *
 * <p>
 * Redaction is idempotent, so a query that was already masked upstream passes through unharmed.
 */
public final class RedactingDialecticEngine implements DialecticEngine {

    private static final Logger log = LoggerFactory.getLogger(RedactingDialecticEngine.class);

    private final DialecticEngine delegate;
    private final RedactionPolicy redactionPolicy;

    /**
     * Wraps {@code delegate} so every question is masked on the way in.
     *
     * @param delegate
     *            the engine that answers (must not be null)
     * @param redactionPolicy
     *            the policy applied to the question (must not be null)
     */
    public RedactingDialecticEngine(DialecticEngine delegate, RedactionPolicy redactionPolicy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.redactionPolicy = Objects.requireNonNull(redactionPolicy, "redactionPolicy cannot be null");
    }

    /**
     * Returns the engine underneath, so an owner can be closed by whoever created it.
     *
     * @return the wrapped engine, never null
     */
    public DialecticEngine getDelegate() {
        return delegate;
    }

    @Override
    public DialecticResponse query(DialecticQuery query) {
        return delegate.query(redact(query));
    }

    @Override
    public void queryStream(DialecticQuery query, LlmStreamSink sink) {
        delegate.queryStream(redact(query), sink);
    }

    private DialecticQuery redact(DialecticQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        RedactionResult result = redactionPolicy.redact(query.getQuestion());
        if (!result.isModified()) {
            return query;
        }
        if (result.getRedactedContent().isBlank()) {
            throw new IllegalArgumentException(
                    "question became blank after redaction; nothing was asked of the memory backend");
        }
        log.debug("Dialectic question redacted: categories={}", result.getCategories());
        return DialecticQuery.builder().workspace(query.getWorkspace()).subject(query.getSubject())
                .observer(query.getObserver()).sessionId(query.getSessionId().orElse(null))
                .question(result.getRedactedContent()).level(query.getLevel()).build();
    }
}
