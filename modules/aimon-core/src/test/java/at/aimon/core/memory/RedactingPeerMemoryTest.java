package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.DialecticQuery;
import at.aimon.core.memory.dialectic.DialecticResponse;
import at.aimon.core.memory.dialectic.ReasoningLevel;
import at.aimon.core.memory.redaction.DefaultRedactionPolicy;
import at.aimon.core.memory.redaction.RedactionMatch;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.memory.redaction.RedactionResult;

/**
 * The redaction gate, as a property of the object rather than of its callers.
 *
 * <p>
 * Four tiers carry caller-written free text outwards and all four are masked here. The fifth, SNAPSHOT, is checked to
 * pass through untouched — not as an oversight but because its query carries peers, a session, a mode and a budget,
 * and wrapping a tier that sends no text would be ceremony that later readers would take for a rule.
 */
@DisplayName("RedactingPeerMemory")
class RedactingPeerMemoryTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final String SECRET = "the deploy account is AKIAIOSFODNN7EXAMPLE, use it";

    private final RedactionPolicy policy = new DefaultRedactionPolicy();

    private RecordingBackend backend;
    private PeerMemory redacting;

    @BeforeEach
    void setUp() {
        backend = new RecordingBackend();
        redacting = new RedactingPeerMemory(backend, policy);
    }

    private static PeerView peer(String id) {
        return PeerView.of(WS, Principal.user(id, id));
    }

    @Test
    @DisplayName("the wrapper reports the backend it wraps, so diagnostics still name the real thing")
    void backendIdPassesThrough() {
        assertThat(redacting.backendId()).isEqualTo("recording");
        assertThat(MemoryCapabilities.of(redacting)).isEqualTo(MemoryCapabilities.of(backend));
    }

    @Test
    @DisplayName("the delegate stays reachable, because the wrapper owns nothing to close")
    void delegateIsReachable() {
        assertThat(((RedactingPeerMemory) redacting).getDelegate()).isSameAs(backend);
    }

    @Test
    @DisplayName("INGEST: the conversation is masked before the backend sees it")
    void ingestIsMasked() {
        redacting.ingestor().orElseThrow().ingest(MemoryIngestRequest.builder().observer(peer("agent")).sessionId("s-1")
                .messages(List.of(Message.user(SECRET))).build());

        assertThat(backend.ingested).hasSize(1);
        assertThat(rendered(backend.ingested.get(0).getMessages())).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    @Test
    @DisplayName("OBSERVE: the fact is masked, and the draft records that it happened")
    void observeIsMasked() {
        redacting.observationRecorder().orElseThrow().observe(
                ObservationDraft.builder().subject(peer("alice")).observer(peer("agent")).content(SECRET).build());

        assertThat(backend.observed).hasSize(1);
        assertThat(backend.observed.get(0).getContent()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(backend.observed.get(0).getMetadata()).containsEntry("redacted", "true");
    }

    @Test
    @DisplayName("SEARCH: the phrase is masked before it reaches the tier — and the embedding backend behind it")
    void searchIsMasked() {
        redacting.searcher().orElseThrow()
                .search(MemorySearchQuery.builder().subject(peer("alice")).query(SECRET).topK(3).build());

        assertThat(backend.searched).hasSize(1);
        assertThat(backend.searched.get(0).getQuery()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        // Everything else about the query survives the rebuild.
        assertThat(backend.searched.get(0).getTopK()).isEqualTo(3);
        assertThat(backend.searched.get(0).getSubject()).isEqualTo(peer("alice"));
    }

    @Test
    @DisplayName("CHAT: the question is masked — the one tier that had no gate at all before this decorator")
    void chatIsMasked() {
        redacting.dialecticEngine().orElseThrow().query(DialecticQuery.builder().workspace(WS).subject(peer("alice"))
                .observer(peer("agent")).question(SECRET).level(ReasoningLevel.FAST).build());

        assertThat(backend.asked).hasSize(1);
        assertThat(backend.asked.get(0).getQuestion()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(backend.asked.get(0).getLevel()).isEqualTo(ReasoningLevel.FAST);
    }

    @Test
    @DisplayName("SNAPSHOT passes through unwrapped: its query carries no caller-written text to mask")
    void snapshotIsNotWrapped() {
        assertThat(redacting.snapshotReader()).get().isSameAs(backend.snapshotReader().orElseThrow());
    }

    @Test
    @DisplayName("text that survives redaction unchanged is passed on as the same object, not rebuilt")
    void cleanTextIsUntouched() {
        MemorySearchQuery clean = MemorySearchQuery.builder().subject(peer("alice")).query("tea").build();

        redacting.searcher().orElseThrow().search(clean);

        assertThat(backend.searched.get(0)).isSameAs(clean);
    }

    @Test
    @DisplayName("text that redaction empties is refused rather than sent as an empty query")
    void fullyRedactedTextIsRefused() {
        RedactionPolicy eraser = content -> RedactionResult.of("",
                List.of(RedactionMatch.of("secret", 0, content.length(), "")));
        PeerMemory erasing = new RedactingPeerMemory(backend, eraser);

        assertThatThrownBy(() -> erasing.searcher().orElseThrow()
                .search(MemorySearchQuery.builder().subject(peer("alice")).query("tea").build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank after redaction");
    }

    @Test
    @DisplayName("a second application changes nothing — redaction is idempotent, so the layered gates are safe")
    void redactionIsIdempotent() {
        PeerMemory twice = new RedactingPeerMemory(new RedactingPeerMemory(backend, policy), policy);

        twice.observationRecorder().orElseThrow().observe(
                ObservationDraft.builder().subject(peer("alice")).observer(peer("agent")).content(SECRET).build());

        assertThat(backend.observed).hasSize(1);
        assertThat(backend.observed.get(0).getContent()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    private static String rendered(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            sb.append(message.getContent());
        }
        return sb.toString();
    }

    /** Records what reached each tier so the test can inspect it after the decorator has run. */
    private static final class RecordingBackend implements PeerMemory {

        private final List<MemoryIngestRequest> ingested = new ArrayList<>();
        private final List<ObservationDraft> observed = new ArrayList<>();
        private final List<MemorySearchQuery> searched = new ArrayList<>();
        private final List<DialecticQuery> asked = new ArrayList<>();
        private final MemorySnapshotReader reader = query -> Optional.empty();

        @Override
        public String backendId() {
            return "recording";
        }

        @Override
        public Optional<MemorySnapshotReader> snapshotReader() {
            return Optional.of(reader);
        }

        @Override
        public Optional<MemorySearcher> searcher() {
            return Optional.of(new MemorySearcher() {
                @Override
                public List<MemoryHit> search(MemorySearchQuery query) {
                    searched.add(query);
                    return List.of();
                }

                @Override
                public boolean ranksByScore() {
                    return false;
                }
            });
        }

        @Override
        public Optional<DialecticEngine> dialecticEngine() {
            return Optional.of(query -> {
                asked.add(query);
                return DialecticResponse.builder().answer("stub").build();
            });
        }

        @Override
        public Optional<ObservationRecorder> observationRecorder() {
            return Optional.of(new ObservationRecorder() {
                @Override
                public Observation observe(ObservationDraft draft) {
                    observed.add(draft);
                    return Observation.builder().id(ObservationId.of(WS, "o-1")).subject(draft.getSubject())
                            .observer(draft.getObserver()).content(draft.getContent()).type(draft.getType())
                            .confidence(draft.getConfidence()).createdAt(Instant.now()).metadata(draft.getMetadata())
                            .build();
                }

                @Override
                public boolean storesConfidence() {
                    return true;
                }
            });
        }

        @Override
        public Optional<MemoryIngestor> ingestor() {
            return Optional.of(request -> {
                ingested.add(request);
                return MemoryIngestReceipt.builder().accepted(request.getMessages().size()).build();
            });
        }
    }
}
