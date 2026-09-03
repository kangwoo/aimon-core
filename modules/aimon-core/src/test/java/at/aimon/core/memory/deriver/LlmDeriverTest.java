package at.aimon.core.memory.deriver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.InMemoryRepresentationStore;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.reconciler.ReconcileDecision;
import at.aimon.core.memory.reconciler.Reconciler;

@DisplayName("LlmDeriver")
class LlmDeriverTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView OBSERVER = PeerView.of(WS, Principal.user("alice"));

    private StubLlmClient llm;
    private ObservationStore store;
    private LlmDeriver deriver;

    @BeforeEach
    void setUp() {
        llm = new StubLlmClient();
        store = new InMemoryObservationStore();
        deriver = new LlmDeriver(llm, store, "fake-model");
    }

    @Test
    @DisplayName("parses JSON array and persists each observation")
    void parsesArray() {
        llm.respondWith("""
                [
                  {"content": "Alice prefers tea", "type": "EXPLICIT", "confidence": 0.9},
                  {"content": "Alice works mornings", "type": "DEDUCTIVE", "confidence": 0.6}
                ]
                """, TokenUsage.of(40, 30, 70));

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).hasSize(2);
        assertThat(result.getLlmTokensUsed()).isEqualTo(70L);
        assertThat(result.getCreated()).extracting(Observation::getContent).containsExactly("Alice prefers tea",
                "Alice works mornings");
        assertThat(result.getCreated()).extracting(Observation::getType).containsExactly(ObservationType.EXPLICIT,
                ObservationType.DEDUCTIVE);
        assertThat(result.getCreated()).extracting(Observation::getConfidence).containsExactly(0.9d, 0.6d);
        assertThat(store.count(OBSERVER)).isEqualTo(2);
    }

    /**
     * The extraction prompt offers exactly EXPLICIT and DEDUCTIVE. Once the enum grew to four, a model naming one of
     * the other two would have had it accepted verbatim — the deriver would have produced a value the design says it
     * does not produce, and persisted it where an older jar cannot read it back. An unoffered kind is now handled
     * the way an unrecognised one always was, rather than being promoted to a real classification.
     */
    @Test
    @DisplayName("a type the extraction prompt does not offer is read as DEDUCTIVE, not taken at face value")
    void unofferedTypeFallsBackToDeductive() {
        llm.respondWith("""
                [
                  {"content": "Alice deploys on Fridays", "type": "INDUCTIVE", "confidence": 0.9},
                  {"content": "Alice contradicts herself", "type": "CONTRADICTION", "confidence": 0.9}
                ]
                """, TokenUsage.of(10, 10, 20));

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).hasSize(2);
        assertThat(result.getCreated()).extracting(Observation::getType).containsOnly(ObservationType.DEDUCTIVE);
    }

    @Test
    @DisplayName("strips markdown code fences before parsing")
    void stripsCodeFences() {
        llm.respondWith("""
                ```json
                [{"content": "x", "type": "EXPLICIT", "confidence": 0.5}]
                ```
                """, TokenUsage.empty());

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(result.getCreated().get(0).getContent()).isEqualTo("x");
    }

    @Test
    @DisplayName("non-array JSON yields empty result without throwing")
    void nonArrayResponse() {
        llm.respondWith("{\"oops\": true}", TokenUsage.empty());

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).isEmpty();
    }

    @Test
    @DisplayName("malformed JSON yields empty result without throwing")
    void malformedJson() {
        llm.respondWith("not json", TokenUsage.empty());

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).isEmpty();
    }

    @Test
    @DisplayName("blank response yields empty result")
    void blankResponse() {
        llm.respondWith("   ", TokenUsage.empty());

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).isEmpty();
    }

    @Test
    @DisplayName("LlmClient throwing is swallowed and returns empty result")
    void llmFailureSwallowed() {
        llm.respondThrowing(new LlmClientException("boom"));

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).isEmpty();
        assertThat(result.getUpdated()).isEmpty();
        assertThat(result.getLlmTokensUsed()).isZero();
    }

    @Test
    @DisplayName("computes confidence from type base score, ignoring any LLM-provided value (§4.3)")
    void computesConfidenceFromType() {
        llm.respondWith("""
                [
                  {"content": "explicit fact", "type": "EXPLICIT", "confidence": 5.0},
                  {"content": "deduced fact", "type": "DEDUCTIVE", "confidence": -1.0}
                ]
                """, TokenUsage.empty());

        DerivationResult result = deriver.derive(ctx());

        // §4.3: confidence = base_score(type) (EXPLICIT=0.9, DEDUCTIVE=0.6); the LLM-reported values are ignored.
        assertThat(result.getCreated()).extracting(Observation::getConfidence).containsExactly(0.9d, 0.6d);
    }

    @Test
    @DisplayName("missing or unknown type defaults to DEDUCTIVE")
    void defaultType() {
        llm.respondWith("""
                [
                  {"content": "no type", "confidence": 0.5},
                  {"content": "weird type", "type": "BANANA", "confidence": 0.5}
                ]
                """, TokenUsage.empty());

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).extracting(Observation::getType).containsExactly(ObservationType.DEDUCTIVE,
                ObservationType.DEDUCTIVE);
    }

    @Test
    @DisplayName("items without textual content are skipped")
    void skipsBadItems() {
        llm.respondWith("""
                [
                  "just a string",
                  {"type": "EXPLICIT", "confidence": 0.5},
                  {"content": "valid", "type": "EXPLICIT", "confidence": 0.5}
                ]
                """, TokenUsage.empty());

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(result.getCreated().get(0).getContent()).isEqualTo("valid");
    }

    @Test
    @DisplayName("subject equals observer per Stage 2 simplification")
    void subjectEqualsObserver() {
        llm.respondWith("[{\"content\": \"x\", \"type\": \"EXPLICIT\", \"confidence\": 0.5}]", TokenUsage.empty());

        DerivationResult result = deriver.derive(ctx());
        Observation obs = result.getCreated().get(0);

        assertThat(obs.getSubject()).isEqualTo(OBSERVER);
        assertThat(obs.getObserver()).isEqualTo(OBSERVER);
        assertThat(obs.getId().getWorkspaceId()).isEqualTo("ws-1");
    }

    @Test
    @DisplayName("no representation call when RepresentationStore is null (observation-only behavior)")
    void noRepresentationCallWithoutStore() {
        llm.respondWith("[{\"content\": \"x\", \"type\": \"EXPLICIT\", \"confidence\": 0.5}]",
                TokenUsage.of(10, 5, 15));

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(result.getLlmTokensUsed()).isEqualTo(15L);
        assertThat(llm.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("saves Representation with subject/observer/sessionId/observations/summary/tokenCount")
    void savesRepresentation() {
        InMemoryRepresentationStore reprStore = new InMemoryRepresentationStore();
        LlmDeriver derWithRepr = new LlmDeriver(llm, store, "fake-model", reprStore);

        llm.enqueueResponse("""
                [
                  {"content": "Alice prefers tea", "type": "EXPLICIT", "confidence": 0.9},
                  {"content": "Alice works mornings", "type": "DEDUCTIVE", "confidence": 0.6}
                ]
                """, TokenUsage.of(40, 30, 70));
        llm.enqueueResponse("Alice is a tea-drinking morning worker.", TokenUsage.of(20, 10, 30));

        DerivationResult result = derWithRepr.derive(ctx());

        assertThat(result.getCreated()).hasSize(2);
        assertThat(result.getLlmTokensUsed()).isEqualTo(100L);
        assertThat(llm.callCount()).isEqualTo(2);
        assertThat(reprStore.size()).isEqualTo(1);

        Representation saved = reprStore.findLatestLocal(OBSERVER, OBSERVER, "sess-1").orElseThrow();
        assertThat(saved.getSubject()).isEqualTo(OBSERVER);
        assertThat(saved.getObserver()).contains(OBSERVER);
        assertThat(saved.getSessionId()).contains("sess-1");
        assertThat(saved.getObservations()).hasSize(2);
        assertThat(saved.getObservations()).extracting(Observation::getContent).containsExactly("Alice prefers tea",
                "Alice works mornings");
        assertThat(saved.getSummary()).isEqualTo("Alice is a tea-drinking morning worker.");
        assertThat(saved.getTokenCount()).isPositive();
    }

    @Test
    @DisplayName("uses dedicated summary system prompt for the second LLM call")
    void usesSummaryPromptOnSecondCall() {
        InMemoryRepresentationStore reprStore = new InMemoryRepresentationStore();
        LlmDeriver derWithRepr = new LlmDeriver(llm, store, "fake-model", reprStore);

        llm.enqueueResponse("[{\"content\": \"x\", \"type\": \"EXPLICIT\", \"confidence\": 0.5}]", TokenUsage.empty());
        llm.enqueueResponse("a short summary", TokenUsage.empty());

        derWithRepr.derive(ctx());

        assertThat(llm.systemPrompts()).hasSize(2);
        assertThat(llm.systemPrompts().get(0)).contains("extract atomic observations");
        assertThat(llm.systemPrompts().get(1)).contains("Synthesize a short, factual");
    }

    @Test
    @DisplayName("trims whitespace from summary text")
    void trimsSummaryWhitespace() {
        InMemoryRepresentationStore reprStore = new InMemoryRepresentationStore();
        LlmDeriver derWithRepr = new LlmDeriver(llm, store, "fake-model", reprStore);

        llm.enqueueResponse("[{\"content\": \"x\", \"type\": \"EXPLICIT\", \"confidence\": 0.5}]", TokenUsage.empty());
        llm.enqueueResponse("\n\n  trimmed summary  \n", TokenUsage.empty());

        derWithRepr.derive(ctx());

        Representation saved = reprStore.findLatestLocal(OBSERVER, OBSERVER, "sess-1").orElseThrow();
        assertThat(saved.getSummary()).isEqualTo("trimmed summary");
    }

    @Test
    @DisplayName("summary call failure is swallowed and observations remain persisted")
    void summaryFailureKeepsObservations() {
        InMemoryRepresentationStore reprStore = new InMemoryRepresentationStore();
        LlmDeriver derWithRepr = new LlmDeriver(llm, store, "fake-model", reprStore);

        llm.enqueueResponse("[{\"content\": \"x\", \"type\": \"EXPLICIT\", \"confidence\": 0.5}]",
                TokenUsage.of(5, 5, 10));
        llm.enqueueThrow(new LlmClientException("summary boom"));

        DerivationResult result = derWithRepr.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(store.count(OBSERVER)).isEqualTo(1);
        assertThat(reprStore.size()).isZero();
        assertThat(result.getLlmTokensUsed()).isEqualTo(10L);
    }

    @Test
    @DisplayName("tokenCount reflects both summary and observations content")
    void tokenCountIncludesObservations() {
        // Two derivations with the same short summary but different observation lengths must yield different
        // tokenCounts — otherwise MemoryInjectionMode.FULL with a maxTokens budget cannot distinguish a small
        // payload from a large one and the budget cut effectively never fires.
        InMemoryRepresentationStore reprStore = new InMemoryRepresentationStore();
        LlmDeriver derWithRepr = new LlmDeriver(llm, store, "fake-model", reprStore);

        llm.enqueueResponse("[{\"content\": \"short\", \"type\": \"EXPLICIT\", \"confidence\": 0.5}]",
                TokenUsage.empty());
        llm.enqueueResponse("summary", TokenUsage.empty());
        derWithRepr.derive(ctx());
        int smallTokenCount = reprStore.findLatestLocal(OBSERVER, OBSERVER, "sess-1").orElseThrow().getTokenCount();

        InMemoryRepresentationStore reprStore2 = new InMemoryRepresentationStore();
        LlmDeriver derWithRepr2 = new LlmDeriver(llm, new at.aimon.core.memory.InMemoryObservationStore(), "fake-model",
                reprStore2);
        String longContent = "a".repeat(400);
        llm.enqueueResponse("[{\"content\": \"" + longContent + "\", \"type\": \"EXPLICIT\", \"confidence\": 0.5}]",
                TokenUsage.empty());
        llm.enqueueResponse("summary", TokenUsage.empty());
        derWithRepr2.derive(ctx());
        int largeTokenCount = reprStore2.findLatestLocal(OBSERVER, OBSERVER, "sess-1").orElseThrow().getTokenCount();

        assertThat(largeTokenCount).isGreaterThan(smallTokenCount);
        assertThat(largeTokenCount).isGreaterThanOrEqualTo(longContent.length() / 4);
    }

    @Test
    @DisplayName("skips summary call when no observations were created")
    void skipsSummaryWhenNoObservations() {
        InMemoryRepresentationStore reprStore = new InMemoryRepresentationStore();
        LlmDeriver derWithRepr = new LlmDeriver(llm, store, "fake-model", reprStore);

        llm.enqueueResponse("[]", TokenUsage.of(5, 5, 10));

        DerivationResult result = derWithRepr.derive(ctx());

        assertThat(result.getCreated()).isEmpty();
        assertThat(reprStore.size()).isZero();
        assertThat(llm.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("reconciler null → legacy save-every-candidate behavior preserved")
    void reconcilerNullPreservesLegacyBehavior() {
        // Sanity check that the new (5-arg) constructor with reconciler=null is identical to the original.
        LlmDeriver derWithNullReconciler = new LlmDeriver(llm, store, "fake-model", null, null);
        llm.respondWith("[{\"content\": \"x\", \"type\": \"EXPLICIT\", \"confidence\": 0.5}]", TokenUsage.empty());

        DerivationResult result = derWithNullReconciler.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(store.count(OBSERVER)).isEqualTo(1);
    }

    @Test
    @DisplayName("reconciler returns Accept → candidate is saved")
    void reconcilerAcceptSavesCandidate() {
        ScriptedReconciler rec = new ScriptedReconciler(ReconcileDecision.Accept.instance());
        LlmDeriver derWithReconciler = new LlmDeriver(llm, store, "fake-model", null, rec);
        llm.respondWith("[{\"content\": \"x\", \"type\": \"EXPLICIT\", \"confidence\": 0.5}]", TokenUsage.empty());

        DerivationResult result = derWithReconciler.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(store.count(OBSERVER)).isEqualTo(1);
        assertThat(rec.invocations).isEqualTo(1);
        assertThat(rec.lastConflicts).isEmpty();
    }

    @Test
    @DisplayName("reconciler returns Reject → candidate is dropped")
    void reconcilerRejectDropsCandidate() {
        Observation existing = seed("alice prefers tea", 0.8d);
        store.save(existing);
        ScriptedReconciler rec = new ScriptedReconciler(new ReconcileDecision.Reject("duplicate"));
        LlmDeriver derWithReconciler = new LlmDeriver(llm, store, "fake-model", null, rec);
        llm.respondWith("[{\"content\": \"tea\", \"type\": \"EXPLICIT\", \"confidence\": 0.5}]", TokenUsage.empty());

        DerivationResult result = derWithReconciler.derive(ctx());

        assertThat(result.getCreated()).isEmpty();
        // Only the seed remains.
        assertThat(store.count(OBSERVER)).isEqualTo(1);
        assertThat(((InMemoryObservationStore) store).findById(existing.getId())).isPresent();
        assertThat(rec.lastConflicts).extracting(Observation::getId).containsExactly(existing.getId());
    }

    @Test
    @DisplayName("reconciler returns Replace → candidate saved, superseded deleted")
    void reconcilerReplaceSwapsObservations() {
        Observation existing = seed("alice prefers tea", 0.4d);
        store.save(existing);
        ScriptedReconciler rec = new ScriptedReconciler(new ReconcileDecision.Replace(existing.getId()));
        LlmDeriver derWithReconciler = new LlmDeriver(llm, store, "fake-model", null, rec);
        llm.respondWith("[{\"content\": \"tea\", \"type\": \"EXPLICIT\", \"confidence\": 0.95}]", TokenUsage.empty());

        DerivationResult result = derWithReconciler.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(store.count(OBSERVER)).isEqualTo(1);
        assertThat(((InMemoryObservationStore) store).findById(existing.getId())).isEmpty();
    }

    @Test
    @DisplayName("reconciler returns Merge with candidate-wins id → merged saved, existing dropped")
    void reconcilerMergeCandidateWins() {
        Observation existing = seed("alice prefers tea", 0.4d);
        store.save(existing);
        // The merged observation uses the candidate id (we don't know it ahead of time, so the ScriptedReconciler
        // builds it on the fly using whichever id wins by confidence).
        ScriptedReconciler rec = ScriptedReconciler.merging(0.95d, "alice prefers tea with milk");
        LlmDeriver derWithReconciler = new LlmDeriver(llm, store, "fake-model", null, rec);
        llm.respondWith("[{\"content\": \"tea\", \"type\": \"EXPLICIT\", \"confidence\": 0.95}]", TokenUsage.empty());

        DerivationResult result = derWithReconciler.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(result.getCreated().get(0).getContent()).isEqualTo("alice prefers tea with milk");
        assertThat(store.count(OBSERVER)).isEqualTo(1);
        // Existing was deleted.
        assertThat(((InMemoryObservationStore) store).findById(existing.getId())).isEmpty();
    }

    @Test
    @DisplayName("reconciler returns Merge with existing-wins id → merged overwrites existing, candidate not added")
    void reconcilerMergeExistingWins() {
        Observation existing = seed("alice prefers tea", 0.95d);
        store.save(existing);
        ScriptedReconciler rec = ScriptedReconciler.merging(0.4d, "alice prefers tea with milk");
        LlmDeriver derWithReconciler = new LlmDeriver(llm, store, "fake-model", null, rec);
        llm.respondWith("[{\"content\": \"tea\", \"type\": \"EXPLICIT\", \"confidence\": 0.4}]", TokenUsage.empty());

        DerivationResult result = derWithReconciler.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(result.getCreated().get(0).getId()).isEqualTo(existing.getId());
        assertThat(result.getCreated().get(0).getContent()).isEqualTo("alice prefers tea with milk");
        assertThat(store.count(OBSERVER)).isEqualTo(1);
    }

    @Test
    @DisplayName("reconciler throws → candidate saved as fallback")
    void reconcilerThrowFallsBackToAccept() {
        Reconciler throwingRec = (candidate, conflicts) -> {
            throw new IllegalStateException("judge unavailable");
        };
        LlmDeriver derWithReconciler = new LlmDeriver(llm, store, "fake-model", null, throwingRec);
        llm.respondWith("[{\"content\": \"x\", \"type\": \"EXPLICIT\", \"confidence\": 0.5}]", TokenUsage.empty());

        DerivationResult result = derWithReconciler.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(store.count(OBSERVER)).isEqualTo(1);
    }

    private Observation seed(String content, double confidence) {
        return Observation.builder().id(ObservationId.of(WS, "seed-" + UUID.randomUUID())).subject(OBSERVER)
                .observer(OBSERVER).content(content).type(ObservationType.EXPLICIT).confidence(confidence)
                .createdAt(Instant.now()).build();
    }

    private DerivationContext ctx() {
        return DerivationContext.builder().workspace(WS).sessionId("sess-1").observer(OBSERVER)
                .messages(List.of(Message.user("hello"))).build();
    }

    /** Minimal {@link Reconciler} that returns a scripted decision and records the call. */
    private static final class ScriptedReconciler implements Reconciler {

        private final ReconcileDecision decision;
        private final boolean buildMergeDynamically;
        private final double mergedConfidence;
        private final String mergedContent;
        int invocations;
        List<Observation> lastConflicts = List.of();

        ScriptedReconciler(ReconcileDecision decision) {
            this.decision = decision;
            this.buildMergeDynamically = false;
            this.mergedConfidence = 0d;
            this.mergedContent = null;
        }

        private ScriptedReconciler(double mergedConfidence, String mergedContent) {
            this.decision = null;
            this.buildMergeDynamically = true;
            this.mergedConfidence = mergedConfidence;
            this.mergedContent = mergedContent;
        }

        static ScriptedReconciler merging(double mergedConfidence, String mergedContent) {
            return new ScriptedReconciler(mergedConfidence, mergedContent);
        }

        @Override
        public ReconcileDecision evaluate(Observation candidate, List<Observation> conflicts) {
            invocations++;
            lastConflicts = conflicts;
            if (!buildMergeDynamically) {
                return decision;
            }
            // Pick the winner by confidence so the merged observation's id matches whichever side outweighs the other.
            // This mirrors DefaultReconciler.buildMergedObservation without depending on it directly.
            Observation other = conflicts.get(0);
            Observation winner = candidate.getConfidence() >= other.getConfidence() ? candidate : other;
            Observation merged = Observation.builder().id(winner.getId()).subject(winner.getSubject())
                    .observer(winner.getObserver()).content(mergedContent).type(winner.getType())
                    .confidence(mergedConfidence).createdAt(Instant.now()).build();
            return new ReconcileDecision.Merge(other.getId(), merged);
        }
    }

    /** Minimal LlmClient stub that records calls and supports scripted responses. */
    private static final class StubLlmClient implements LlmClient {

        private LlmResponse defaultResponse = LlmResponse.text("");
        private RuntimeException defaultError;
        private final Deque<Object> scripted = new ArrayDeque<>();
        private final AtomicReference<String> lastSystemPrompt = new AtomicReference<>();
        private final List<String> systemPrompts = new ArrayList<>();
        private int callCount;

        void respondWith(String text, TokenUsage usage) {
            this.defaultResponse = LlmResponse.of(text, List.of(), usage);
            this.defaultError = null;
            this.scripted.clear();
        }

        void respondThrowing(RuntimeException error) {
            this.defaultError = error;
            this.scripted.clear();
        }

        void enqueueResponse(String text, TokenUsage usage) {
            this.scripted.addLast(LlmResponse.of(text, List.of(), usage));
        }

        void enqueueThrow(RuntimeException error) {
            this.scripted.addLast(error);
        }

        int callCount() {
            return callCount;
        }

        List<String> systemPrompts() {
            return List.copyOf(systemPrompts);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            callCount++;
            lastSystemPrompt.set(systemPrompt);
            systemPrompts.add(systemPrompt);
            if (!scripted.isEmpty()) {
                Object next = scripted.pollFirst();
                if (next instanceof RuntimeException ex) {
                    throw ex;
                }
                return (LlmResponse) next;
            }
            if (defaultError != null) {
                throw defaultError;
            }
            return defaultResponse;
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
            return sendMessage(systemPromptParts.concatenated(), messages, tools, modelConfig);
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

    }
}
