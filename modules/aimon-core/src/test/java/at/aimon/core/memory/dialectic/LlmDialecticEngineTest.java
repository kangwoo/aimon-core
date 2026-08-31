package at.aimon.core.memory.dialectic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("LlmDialecticEngine")
class LlmDialecticEngineTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice"));

    private StubLlmClient llm;
    private ObservationStore store;
    private LlmDialecticEngine engine;

    @BeforeEach
    void setUp() {
        llm = new StubLlmClient();
        store = new InMemoryObservationStore();
        engine = new LlmDialecticEngine(llm, store, "fake-model");
    }

    @Test
    @DisplayName("prefetched observations are inlined into the system prompt")
    void prefetchInlinedInPrompt() {
        seed("Alice prefers tea over coffee", ObservationType.EXPLICIT, 0.9d);
        seed("Alice works in the morning hours so she chooses tea", ObservationType.DEDUCTIVE, 0.6d);
        llm.respondWith("Alice prefers tea.", TokenUsage.of(20, 5, 25));

        DialecticResponse response = engine.query(query("tea"));

        assertThat(response.getAnswer()).isEqualTo("Alice prefers tea.");
        assertThat(response.getObservationsConsidered()).hasSize(2);
        assertThat(response.getTokenUsage()).isEqualTo(TokenUsage.of(20, 5, 25));
        String prompt = llm.lastSystemPrompt.get();
        assertThat(prompt).contains("Alice prefers tea over coffee").contains("EXPLICIT").contains("DEDUCTIVE")
                .contains("confidence=0.90");
    }

    @Test
    @DisplayName("max tokens is taken from the query level")
    void maxTokensFromLevel() {
        llm.respondWith("ok", TokenUsage.empty());

        engine.query(DialecticQuery.builder().workspace(WS).subject(ALICE).observer(ALICE).question("?")
                .level(ReasoningLevel.DEEP).build());

        assertThat(llm.lastModel.get().getMaxTokens()).contains(ReasoningLevel.DEEP.getMaxTokens());
    }

    @Test
    @DisplayName("blank LLM response yields fallback answer with the response usage")
    void blankResponseFallback() {
        seed("any seedling content", ObservationType.EXPLICIT, 0.5d);
        llm.respondWith("   ", TokenUsage.of(10, 0, 10));

        DialecticResponse response = engine.query(query("seedling"));

        assertThat(response.getAnswer()).isEqualTo("I don't have enough information to answer that.");
        assertThat(response.getObservationsConsidered()).hasSize(1);
        assertThat(response.getTokenUsage()).isEqualTo(TokenUsage.of(10, 0, 10));
    }

    @Test
    @DisplayName("LLM exception is swallowed and surfaced as fallback answer")
    void llmExceptionFallback() {
        llm.respondThrowing(new LlmClientException("boom"));

        DialecticResponse response = engine.query(query("?"));

        assertThat(response.getAnswer()).isEqualTo("I don't have enough information to answer that.");
        assertThat(response.getTokenUsage().getTotalTokens()).isZero();
    }

    @Test
    @DisplayName("question is sent as the user message")
    void questionAsUserMessage() {
        llm.respondWith("ok", TokenUsage.empty());

        engine.query(query("How is Alice?"));

        assertThat(llm.lastMessages.get()).hasSize(1);
        assertThat(llm.lastMessages.get().get(0).getContent()).isEqualTo("How is Alice?");
    }

    @Test
    @DisplayName("default queryStream falls back to single TEXT_DELTA + STREAM_END")
    void defaultStreamFallback() {
        llm.respondWith("answer", TokenUsage.of(1, 1, 2));
        List<LlmStreamChunk> chunks = new ArrayList<>();
        LlmStreamSink sink = chunks::add;

        engine.queryStream(query("q?"), sink);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getKind()).isEqualTo(LlmStreamChunk.Kind.TEXT_DELTA);
        assertThat(chunks.get(0).getTextDelta()).contains("answer");
        assertThat(chunks.get(1).getKind()).isEqualTo(LlmStreamChunk.Kind.STREAM_END);
    }

    @Test
    @DisplayName("empty answer streams a single STREAM_END chunk")
    void emptyAnswerStreamsOnlyEnd() {
        // Force fallback by throwing — fallback is non-empty, so build a custom engine that returns empty.
        DialecticEngine emptyEngine = q -> DialecticResponse.text("");
        List<LlmStreamChunk> chunks = new ArrayList<>();

        emptyEngine.queryStream(query("?"), chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getKind()).isEqualTo(LlmStreamChunk.Kind.STREAM_END);
    }

    private DialecticQuery query(String q) {
        return DialecticQuery.builder().workspace(WS).subject(ALICE).observer(ALICE).question(q).build();
    }

    private void seed(String content, ObservationType type, double confidence) {
        store.save(Observation.builder().id(ObservationId.of(WS, java.util.UUID.randomUUID().toString())).subject(ALICE)
                .observer(ALICE).content(content).type(type).confidence(confidence).build());
    }

    private static final class StubLlmClient implements LlmClient {

        private LlmResponse response = LlmResponse.text("");
        private RuntimeException error;
        private final AtomicReference<String> lastSystemPrompt = new AtomicReference<>();
        private final AtomicReference<List<Message>> lastMessages = new AtomicReference<>();
        private final AtomicReference<LlmModel> lastModel = new AtomicReference<>();

        void respondWith(String text, TokenUsage usage) {
            this.response = LlmResponse.of(text, List.of(), usage);
            this.error = null;
        }

        void respondThrowing(RuntimeException error) {
            this.error = error;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            lastSystemPrompt.set(systemPrompt);
            lastMessages.set(messages);
            lastModel.set(modelConfig);
            if (error != null) {
                throw error;
            }
            return response;
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
