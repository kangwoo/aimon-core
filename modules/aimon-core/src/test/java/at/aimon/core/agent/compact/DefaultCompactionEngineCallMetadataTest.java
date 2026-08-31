package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.base.Principal;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * Verifies that {@link DefaultCompactionEngine} merges caller-supplied {@link LlmCallMetadata} with its own framework
 * defaults via {@link LlmCallMetadata#withDefaults(LlmCallMetadata)} when invoking the summary LLM call.
 */
class DefaultCompactionEngineCallMetadataTest {

    private RecordingLlmClient llmClient;
    private TokenEstimator tokenEstimator;
    private DefaultHookExecutionManager hookExecutionManager;
    private HookRegistry hookRegistry;
    private Environment environment;
    private DefaultCompactionEngine engine;

    @BeforeEach
    void setUp() {
        llmClient = new RecordingLlmClient();
        tokenEstimator = new HeuristicTokenEstimator();
        hookExecutionManager = new DefaultHookExecutionManager();
        hookRegistry = new DefaultHookRegistry();
        environment = Environment.createDefault();
        engine = DefaultCompactionEngine.withDefaults(llmClient, tokenEstimator, hookExecutionManager);
    }

    @Test
    void usesEngineDefaultsWhenNoCallerMetadata() {
        TranscriptBuffer memory = memoryWith("hello world");
        CompactionRequest request = baseRequest(memory).build();

        engine.compact(request);

        LlmCallMetadata seen = llmClient.lastMetadata.get();
        assertThat(seen).isNotNull();
        assertThat(seen.getComponent()).hasValue("compaction-engine");
        assertThat(seen.getFeature()).hasValue(LlmCallMetadata.Feature.COMPACTION);
        assertThat(seen.getTraceId()).hasValue(memory.getSessionId().toString());
        assertThat(seen.getPrincipal()).isEmpty();
    }

    @Test
    void mergesCallerPrincipalWithEngineDefaults() {
        TranscriptBuffer memory = memoryWith("hello world");
        Principal caller = Principal.user("user-99", "Bob");
        LlmCallMetadata callerMetadata = LlmCallMetadata.builder().principal(caller).build();
        CompactionRequest request = baseRequest(memory).callMetadata(callerMetadata).build();

        engine.compact(request);

        LlmCallMetadata seen = llmClient.lastMetadata.get();
        assertThat(seen).isNotNull();
        // Caller-supplied principal is preserved.
        assertThat(seen.getPrincipal()).hasValue(caller);
        // Engine-supplied defaults fill the gaps.
        assertThat(seen.getComponent()).hasValue("compaction-engine");
        assertThat(seen.getFeature()).hasValue(LlmCallMetadata.Feature.COMPACTION);
        assertThat(seen.getTraceId()).hasValue(memory.getSessionId().toString());
    }

    @Test
    void callerComponentAndTraceIdWinOverEngineDefaultsOnOverlap() {
        TranscriptBuffer memory = memoryWith("hello world");
        LlmCallMetadata callerMetadata = LlmCallMetadata.builder().component("compact-command")
                .traceId("custom-trace-123").build();
        CompactionRequest request = baseRequest(memory).callMetadata(callerMetadata).build();

        engine.compact(request);

        LlmCallMetadata seen = llmClient.lastMetadata.get();
        assertThat(seen).isNotNull();
        // Caller wins on overlap.
        assertThat(seen.getComponent()).hasValue("compact-command");
        assertThat(seen.getTraceId()).hasValue("custom-trace-123");
        // Feature still falls back to the engine default.
        assertThat(seen.getFeature()).hasValue(LlmCallMetadata.Feature.COMPACTION);
    }

    private CompactionRequest.Builder baseRequest(TranscriptBuffer memory) {
        return CompactionRequest.builder().transcriptBuffer(memory).trigger(CompactionTrigger.MANUAL)
                .model(LlmModel.builder().name("test-model").build()).hookRegistry(hookRegistry)
                .environment(environment);
    }

    private static TranscriptBuffer memoryWith(String userMessage) {
        TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage(userMessage);
        return memory;
    }

    private static final class RecordingLlmClient implements LlmClient {
        private final AtomicReference<LlmCallMetadata> lastMetadata = new AtomicReference<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            // Should never be reached: the engine always passes a metadata-aware overload.
            throw new AssertionError("Engine should call the metadata-aware sendMessage overload");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            lastMetadata.set(metadata);
            return LlmResponse.text("compacted summary");
        }

        @Override
        public String getProviderName() {
            return "recording";
        }
    }
}
