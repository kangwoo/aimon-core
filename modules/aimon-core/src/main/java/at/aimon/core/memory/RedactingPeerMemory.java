package at.aimon.core.memory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.Message;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.RedactingDialecticEngine;
import at.aimon.core.memory.redaction.MessageRedactor;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.memory.redaction.RedactionResult;

/**
 * A {@link PeerMemory} that masks every piece of caller-written text on its way out to the backend.
 *
 * <h2>Why the gate moved into the implementation</h2>
 *
 * <p>
 * {@link ObservationDraft} and {@link MemoryIngestRequest} both document that their text must already be redacted, and
 * a documented precondition is exactly what today's memory does <em>not</em> rely on: the derivation queue applies
 * redaction inside {@code enqueue}, so no caller can route around it. Opening a public {@link MemoryIngestor} would
 * have traded that for a comment — an application could call {@code peerMemory.ingestor().ingest(raw)} and nothing
 * would stop it.
 *
 * <p>
 * So the assembly wraps every backend in this decorator and hands the wrapper to the stack. There is no path to an
 * unwrapped backend, and the gate is one gate again. Adapters therefore know nothing about redaction, which is the
 * property that matters as adapters multiply: a gate per adapter is a gate the next adapter forgets, and the omission
 * only becomes visible after a secret has left the process.
 *
 * <h2>Four tiers are wrapped, and SNAPSHOT is not</h2>
 *
 * <p>
 * The test is whether caller-written free text travels outwards.
 *
 * <table border="1">
 * <caption>What each tier sends out</caption>
 * <tr>
 * <th>Tier</th>
 * <th>Outgoing free text</th>
 * </tr>
 * <tr>
 * <td>INGEST</td>
 * <td>the conversation itself</td>
 * </tr>
 * <tr>
 * <td>OBSERVE</td>
 * <td>the fact the model wrote</td>
 * </tr>
 * <tr>
 * <td>SEARCH</td>
 * <td>the search phrase</td>
 * </tr>
 * <tr>
 * <td>CHAT</td>
 * <td>the question the model wrote — the one tier with no gate before this class</td>
 * </tr>
 * <tr>
 * <td>SNAPSHOT</td>
 * <td><b>none.</b> Its query carries peers, a session, a mode and a budget</td>
 * </tr>
 * </table>
 *
 * <p>
 * SEARCH and CHAT are wrapped even though a tool sits in front of them, because
 * {@link PeerMemory#searcher()} and {@link PeerMemory#dialecticEngine()} are public: an application can reach the tier
 * without going through a tool.
 *
 * <p>
 * Redaction is idempotent, so text already masked by a tool passes through this decorator and, on the default backend,
 * through the derivation queue a third time without harm.
 *
 * <h2>Closing</h2>
 *
 * <p>
 * This wrapper owns nothing. An assembly deciding what to close must test {@link #getDelegate()} — not the wrapper —
 * or a backend holding an HTTP client would never be closed at all.
 */
public final class RedactingPeerMemory implements PeerMemory {

    private static final String META_KEY_REDACTED = "redacted";
    private static final String META_KEY_REDACTION_CATEGORIES = "redaction.categories";

    private static final Logger log = LoggerFactory.getLogger(RedactingPeerMemory.class);

    private final PeerMemory delegate;
    private final RedactionPolicy redactionPolicy;
    private final MessageRedactor messageRedactor;

    /**
     * Wraps {@code delegate} so nothing reaches it unmasked.
     *
     * @param delegate
     *            the backend that does the work (must not be null)
     * @param redactionPolicy
     *            the policy applied to every outgoing text (must not be null)
     */
    public RedactingPeerMemory(PeerMemory delegate, RedactionPolicy redactionPolicy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.redactionPolicy = Objects.requireNonNull(redactionPolicy, "redactionPolicy cannot be null");
        this.messageRedactor = new MessageRedactor(redactionPolicy);
    }

    /**
     * Returns the backend underneath.
     *
     * <p>
     * Teardown decisions belong on this object, never on the wrapper: the wrapper holds no resources, so an
     * {@code instanceof AutoCloseable} check against it answers {@code false} for a backend that very much does.
     *
     * @return the wrapped backend, never null
     */
    public PeerMemory getDelegate() {
        return delegate;
    }

    @Override
    public String backendId() {
        return delegate.backendId();
    }

    @Override
    public Optional<MemorySnapshotReader> snapshotReader() {
        // Not wrapped: a snapshot query carries peers, a session, a mode and a budget — no caller-written text.
        return delegate.snapshotReader();
    }

    @Override
    public Optional<MemorySearcher> searcher() {
        return delegate.searcher().map(RedactingSearcher::new);
    }

    @Override
    public Optional<DialecticEngine> dialecticEngine() {
        return delegate.dialecticEngine().map(engine -> new RedactingDialecticEngine(engine, redactionPolicy));
    }

    @Override
    public Optional<ObservationRecorder> observationRecorder() {
        return delegate.observationRecorder().map(RedactingRecorder::new);
    }

    @Override
    public Optional<MemoryIngestor> ingestor() {
        return delegate.ingestor().map(RedactingIngestor::new);
    }

    @Override
    public String toString() {
        return "RedactingPeerMemory[" + delegate + "]";
    }

    /** Masks the search phrase before it reaches the tier — and the embedding backend behind it. */
    private final class RedactingSearcher implements MemorySearcher {

        private final MemorySearcher inner;

        RedactingSearcher(MemorySearcher inner) {
            this.inner = inner;
        }

        @Override
        public List<MemoryHit> search(MemorySearchQuery query) {
            Objects.requireNonNull(query, "query cannot be null");
            RedactionResult result = redactionPolicy.redact(query.getQuery());
            if (!result.isModified()) {
                return inner.search(query);
            }
            if (result.getRedactedContent().isBlank()) {
                throw new IllegalArgumentException("query became blank after redaction; nothing was searched for");
            }
            log.debug("Memory search query redacted: categories={}", result.getCategories());
            return inner.search(MemorySearchQuery.builder().subject(query.getSubject())
                    .observer(query.getObserver().orElse(null)).query(result.getRedactedContent()).topK(query.getTopK())
                    .minScore(query.getMinScore()).sessionId(query.getSessionId().orElse(null)).build());
        }

        @Override
        public boolean ranksByScore() {
            return inner.ranksByScore();
        }
    }

    /** Masks the fact before it is written, and records that it happened. */
    private final class RedactingRecorder implements ObservationRecorder {

        private final ObservationRecorder inner;

        RedactingRecorder(ObservationRecorder inner) {
            this.inner = inner;
        }

        @Override
        public Observation observe(ObservationDraft draft) {
            Objects.requireNonNull(draft, "draft cannot be null");
            RedactionResult result = redactionPolicy.redact(draft.getContent());
            if (!result.isModified()) {
                return inner.observe(draft);
            }
            if (result.getRedactedContent().isBlank()) {
                throw new IllegalArgumentException("content became blank after redaction; nothing was recorded");
            }
            log.debug("Observation content redacted: categories={}", result.getCategories());
            Map<String, String> metadata = new HashMap<>(draft.getMetadata());
            metadata.put(META_KEY_REDACTED, "true");
            metadata.put(META_KEY_REDACTION_CATEGORIES, String.join(",", result.getCategories()));
            return inner.observe(draft.withContent(result.getRedactedContent()).withMetadata(Map.copyOf(metadata)));
        }

        @Override
        public boolean storesConfidence() {
            return inner.storesConfidence();
        }
    }

    /** Masks the whole conversation before it is offered for derivation. */
    private final class RedactingIngestor implements MemoryIngestor {

        private final MemoryIngestor inner;

        RedactingIngestor(MemoryIngestor inner) {
            this.inner = inner;
        }

        @Override
        public MemoryIngestReceipt ingest(MemoryIngestRequest request) {
            Objects.requireNonNull(request, "request cannot be null");
            List<Message> redacted = messageRedactor.redactAll(request.getMessages());
            return inner.ingest(request.withMessages(redacted));
        }
    }
}
