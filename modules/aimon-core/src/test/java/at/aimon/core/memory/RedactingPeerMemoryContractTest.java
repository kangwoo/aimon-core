package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.memory.redaction.DefaultRedactionPolicy;

/**
 * The decorator every assembled deployment actually holds, run through the same contract as the backend underneath.
 *
 * <p>
 * {@code MemoryAssembly} wraps every backend in {@link RedactingPeerMemory} and hands on only the wrapper, so this —
 * not {@link StoreBackedPeerMemory} — is the {@link PeerMemory} a running stack calls. It is also not a pass-through:
 * it re-implements {@link MemorySearcher#search} by rebuilding the query, re-implements
 * {@link ObservationRecorder#observe} by rewriting the draft, and re-exposes three capability signals from the tier
 * beneath it. Each of those is a place a field or a flag can be dropped without any test noticing, because
 * {@code RedactingPeerMemoryTest} asks what the decorator adds rather than whether the contract survives it.
 *
 * <p>
 * Extending the store-backed suite rather than restating it is the point: the assertion is that wrapping changes
 * <em>nothing</em> a caller can observe about the contract, and the only way to assert "nothing" is to run the
 * identical cases. The seed hooks stay the parent's — they write to the stores directly, which is below the decorator
 * and therefore unredacted, so what the read tiers find is what the parent's cases expect.
 *
 * <h2>The inherited cases do not reach the rebuilds, so two cases here do</h2>
 *
 * <p>
 * IMPORTANT: {@code RedactingSearcher} and {@code RedactingRecorder} both <b>short-circuit</b> when the policy does not
 * fire — {@code if (!result.isModified()) return inner.search(query);} — and none of the fixtures the parent suite
 * sends ({@code "tea"}, {@code "Alice prefers tea"}, …) matches any of {@link DefaultRedactionPolicy}'s five patterns.
 * So all 21 inherited cases take the pass-through branch and the rebuild is never entered. Inheriting the suite alone
 * therefore proves the decorator does not <em>break</em> the contract, and proves nothing whatever about the rebuilds.
 *
 * <p>
 * That gap was measured, not guessed: deleting {@code .minScore(...)} and {@code .sessionId(...)} from the query
 * rebuild leaves every inherited case green, including the two named for those very axes. The cases below close it by
 * sending text the policy does trip, and they use the <em>rejection</em> contract as the probe — a backend that cannot
 * apply a narrowing axis must refuse it, so an axis dropped in the rebuild turns a refusal into a silently wider
 * answer, which is a louder failure than a lost filter.
 *
 * @see RedactingPeerMemoryTest RedactingPeerMemoryTest — the field-by-field assertions on all three rebuilds
 */
@DisplayName("RedactingPeerMemory — PeerMemory contract")
class RedactingPeerMemoryContractTest extends StoreBackedPeerMemoryContractTest {

    /** Trips {@link DefaultRedactionPolicy}'s SECRET rule, so the decorator takes its rebuild branch. */
    private static final String REDACTABLE = "token: hunter2";

    @Override
    protected PeerMemory newBackend() {
        return new RedactingPeerMemory(super.newBackend(), new DefaultRedactionPolicy());
    }

    @Test
    @DisplayName("a rebuilt query still carries minScore, so a backend that cannot score still rejects it")
    void rebuiltQueryStillCarriesMinScore() {
        final MemorySearcher searcher = backend().searcher().orElseThrow();
        assertThat(searcher.ranksByScore()).as("the delegate's signal must survive the decorator").isFalse();

        assertThatThrownBy(() -> searcher.search(MemorySearchQuery.builder().subject(SUBJECT).observer(OBSERVER)
                .query(REDACTABLE).minScore(0.5d).build())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ranksByScore");
    }

    @Test
    @DisplayName("a rebuilt query still carries the session id, so a backend that cannot narrow still rejects it")
    void rebuiltQueryStillCarriesSessionId() {
        final MemorySearcher searcher = backend().searcher().orElseThrow();
        assertThat(searcher.narrowsBySession()).as("the delegate's signal must survive the decorator").isFalse();

        assertThatThrownBy(() -> searcher.search(MemorySearchQuery.builder().subject(SUBJECT).observer(OBSERVER)
                .query(REDACTABLE).sessionId(SESSION_ID).build())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("narrowsBySession");
    }

    @Test
    @DisplayName("a rewritten draft is still recorded, masked, under the subject the caller named")
    void rewrittenDraftIsStillRecorded() {
        final Observation saved = backend().observationRecorder().orElseThrow().observe(ObservationDraft.builder()
                .subject(SUBJECT).observer(OBSERVER).sessionId(SESSION_ID).content(REDACTABLE).build());

        assertThat(saved.getSubject()).isEqualTo(SUBJECT);
        assertThat(saved.getObserver()).isEqualTo(OBSERVER);
        assertThat(saved.getContent()).as("the rebuild branch ran, so the secret never reached the store")
                .doesNotContain("hunter2");
        assertThat(saved.getMetadata()).containsEntry("redacted", "true");
    }
}
