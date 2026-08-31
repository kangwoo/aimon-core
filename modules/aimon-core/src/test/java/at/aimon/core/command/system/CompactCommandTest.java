package at.aimon.core.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionRequest;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.command.CommandType;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;

class CompactCommandTest {

    private RecordingEngine engine;
    private RecordingGuard guard;
    private HookRegistry hookRegistry;
    private HookExecutionManager hookExecutionManager;
    private AtomicReference<OnStopContext> capturedOnStop;
    private Environment environment;
    private CompactCommand command;

    @BeforeEach
    void setUp() {
        engine = new RecordingEngine();
        guard = new RecordingGuard();
        hookRegistry = new DefaultHookRegistry();
        hookExecutionManager = new DefaultHookExecutionManager();
        capturedOnStop = new AtomicReference<>();
        hookRegistry.register(HookEventType.ON_STOP, context -> {
            capturedOnStop.set(context);
            return HookResult.success();
        });
        environment = Environment.createDefault();
        command = new CompactCommand(engine, guard, hookRegistry, hookExecutionManager, environment);
    }

    @Test
    void hasCompactNameAndSystemType() {
        assertThat(command.getName()).isEqualTo("compact");
        assertThat(command.getType()).isEqualTo(CommandType.SYSTEM);
        assertThat(command.getMetadata().getDescription()).hasValue("Manually compact the current conversation");
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThatThrownBy(() -> new CompactCommand(null, guard, hookRegistry, hookExecutionManager, environment))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompactCommand(engine, null, hookRegistry, hookExecutionManager, environment))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompactCommand(engine, guard, null, hookExecutionManager, environment))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompactCommand(engine, guard, hookRegistry, null, environment))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompactCommand(engine, guard, hookRegistry, hookExecutionManager, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeRejectsNullArguments() {
        assertThatThrownBy(() -> command.execute(null, DirectCommandExecutionRequest.of("")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> command.execute(contextWithMemory(memoryWith("hello")), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void returnsNoActiveConversationWhenMemoryAbsent() {
        engine.setNextResult(successResult(0, 0));

        CommandExecutionResult result = command.execute(contextWithoutMemory(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("No active conversation");
        assertThat(engine.lastRequest.get()).isNull();
        assertThat(capturedOnStop.get()).as("OnStop is not fired when there is no compaction to perform").isNull();
    }

    @Test
    void returnsNothingToCompactWhenMemoryEmpty() {
        TranscriptBuffer empty = new TranscriptBuffer(SessionId.generate());
        engine.setNextResult(successResult(0, 0));

        CommandExecutionResult result = command.execute(contextWithMemory(empty), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("nothing to compact");
        assertThat(engine.lastRequest.get()).isNull();
        assertThat(capturedOnStop.get()).isNull();
    }

    @Test
    void buildsManualRequestAndResetsCircuitBreakerOnSuccess() {
        TranscriptBuffer memory = memoryWith("hello world");
        engine.setNextResult(successResult(1234, 567));

        CommandExecutionResult result = command.execute(contextWithMemory(memory),
                DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Conversation compacted").contains("1234").contains("567");

        CompactionRequest captured = engine.lastRequest.get();
        assertThat(captured).isNotNull();
        assertThat(captured.getTrigger()).isEqualTo(CompactionTrigger.MANUAL);
        assertThat(captured.getTranscriptBuffer()).isSameAs(memory);
        assertThat(captured.getEnvironment()).isSameAs(environment);
        assertThat(captured.getHookRegistry()).isSameAs(hookRegistry);
        assertThat(captured.getCustomInstructions()).isEmpty();

        assertThat(guard.lastResetSessionId.get()).isEqualTo(memory.getSessionId());
    }

    @Test
    void forwardsTrimmedCustomInstructions() {
        TranscriptBuffer memory = memoryWith("hello");
        engine.setNextResult(successResult(100, 50));

        command.execute(contextWithMemory(memory), DirectCommandExecutionRequest.of("   focus on api endpoints   "));

        CompactionRequest captured = engine.lastRequest.get();
        assertThat(captured.getCustomInstructions()).hasValue("focus on api endpoints");
    }

    @Test
    void blankArgumentsResultInNoCustomInstructions() {
        TranscriptBuffer memory = memoryWith("hello");
        engine.setNextResult(successResult(100, 50));

        command.execute(contextWithMemory(memory), DirectCommandExecutionRequest.of("   "));

        CompactionRequest captured = engine.lastRequest.get();
        assertThat(captured.getCustomInstructions()).isEmpty();
    }

    @Test
    void engineFailureSurfacesAsCommandFailureAndDoesNotResetGuard() {
        TranscriptBuffer memory = memoryWith("hello");
        IllegalStateException error = new IllegalStateException("summary llm down");
        engine.setNextResult(CompactionResult.failure(error, CompactionMetadata.builder()
                .trigger(CompactionTrigger.MANUAL).startedAt(Instant.now()).completedAt(Instant.now()).build()));

        CommandExecutionResult result = command.execute(contextWithMemory(memory),
                DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResponse()).contains("Compaction failed").contains("summary llm down");
        assertThat(guard.lastResetSessionId.get()).isNull();
    }

    @Test
    void engineExceptionIsCaughtAndReturnedAsFailure() {
        TranscriptBuffer memory = memoryWith("hello");
        engine.setNextException(new RuntimeException("boom"));

        CommandExecutionResult result = command.execute(contextWithMemory(memory),
                DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResponse()).contains("Compaction failed").contains("boom");
        assertThat(guard.lastResetSessionId.get()).isNull();
    }

    @Test
    void attachesCallMetadataWithDefaultsWhenNoPrincipalSet() {
        TranscriptBuffer memory = memoryWith("hello");
        engine.setNextResult(successResult(100, 50));

        command.execute(contextWithMemory(memory), DirectCommandExecutionRequest.of(""));

        LlmCallMetadata metadata = engine.lastRequest.get().getCallMetadata().orElseThrow();
        assertThat(metadata.getComponent()).hasValue(CompactCommand.COMPONENT_NAME);
        assertThat(metadata.getFeature()).hasValue(LlmCallMetadata.Feature.COMPACTION);
        assertThat(metadata.getTraceId()).hasValue(memory.getSessionId().toString());
        assertThat(metadata.getPrincipal()).isEmpty();
    }

    @Test
    void attachesPrincipalFromRequestWhenPresent() {
        TranscriptBuffer memory = memoryWith("hello");
        engine.setNextResult(successResult(100, 50));
        DirectCommandExecutionRequest request = DirectCommandExecutionRequest.builder()
                .principal(Principal.user("user-42", "Alice")).build();

        command.execute(contextWithMemory(memory), request);

        LlmCallMetadata metadata = engine.lastRequest.get().getCallMetadata().orElseThrow();
        assertThat(metadata.getPrincipal()).hasValue(Principal.user("user-42", "Alice"));
    }

    @Test
    void firesOnStopHookWithSuccessTrueOnSuccessfulCompaction() {
        TranscriptBuffer memory = memoryWith("hello");
        engine.setNextResult(successResult(100, 50));

        command.execute(contextWithMemory(memory), DirectCommandExecutionRequest.of(""));

        OnStopContext stopContext = capturedOnStop.get();
        assertThat(stopContext).isNotNull();
        assertThat(stopContext.isSuccess()).isTrue();
        assertThat(stopContext.getInvokerName()).isEqualTo(CompactCommand.ON_STOP_INVOKER_NAME);
        assertThat(stopContext.getFinalAnswer()).contains("Conversation compacted");
        assertThat(stopContext.getMetadata().getDuration().isNegative()).isFalse();
    }

    @Test
    void firesOnStopHookWithSuccessFalseWhenEngineReturnsFailure() {
        TranscriptBuffer memory = memoryWith("hello");
        engine.setNextResult(CompactionResult.failure(new IllegalStateException("blocked"), CompactionMetadata.builder()
                .trigger(CompactionTrigger.MANUAL).startedAt(Instant.now()).completedAt(Instant.now()).build()));

        command.execute(contextWithMemory(memory), DirectCommandExecutionRequest.of(""));

        OnStopContext stopContext = capturedOnStop.get();
        assertThat(stopContext).isNotNull();
        assertThat(stopContext.isSuccess()).isFalse();
        assertThat(stopContext.getFinalAnswer()).contains("Compaction failed");
    }

    @Test
    void firesOnStopHookWithSuccessFalseWhenEngineThrows() {
        TranscriptBuffer memory = memoryWith("hello");
        engine.setNextException(new RuntimeException("kaboom"));

        command.execute(contextWithMemory(memory), DirectCommandExecutionRequest.of(""));

        OnStopContext stopContext = capturedOnStop.get();
        assertThat(stopContext).isNotNull();
        assertThat(stopContext.isSuccess()).isFalse();
        assertThat(stopContext.getFinalAnswer()).contains("kaboom");
    }

    @Test
    void nullEngineResultIsTreatedAsFailureAndDoesNotResetGuard() {
        TranscriptBuffer memory = memoryWith("hello");
        engine.setNextResult(null);

        CommandExecutionResult result = command.execute(contextWithMemory(memory),
                DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResponse()).contains("Compaction failed").contains("null");
        assertThat(guard.lastResetSessionId.get()).isNull();
        // OnStop still fires on the failure path.
        OnStopContext stopContext = capturedOnStop.get();
        assertThat(stopContext).isNotNull();
        assertThat(stopContext.isSuccess()).isFalse();
    }

    @Test
    void omitsPrincipalWhenNoPrincipalSet() {
        TranscriptBuffer memory = memoryWith("hello");
        engine.setNextResult(successResult(100, 50));
        DirectCommandExecutionRequest request = DirectCommandExecutionRequest.of("");

        command.execute(contextWithMemory(memory), request);

        LlmCallMetadata metadata = engine.lastRequest.get().getCallMetadata().orElseThrow();
        assertThat(metadata.getPrincipal()).isEmpty();
    }

    private CommandExecutionContext contextWithMemory(TranscriptBuffer memory) {
        return CommandExecutionContext.builder().command(command)
                .defaultModel(LlmModel.builder().name("test-model").build()).toolRegistry(new DefaultToolRegistry())
                .transcriptBuffer(memory).build();
    }

    private CommandExecutionContext contextWithoutMemory() {
        return CommandExecutionContext.builder().command(command)
                .defaultModel(LlmModel.builder().name("test-model").build()).toolRegistry(new DefaultToolRegistry())
                .build();
    }

    private static TranscriptBuffer memoryWith(String userMessage) {
        TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage(userMessage);
        return memory;
    }

    private static CompactionResult successResult(int preTokens, int postTokens) {
        Instant now = Instant.now();
        CompactionMetadata metadata = CompactionMetadata.builder().trigger(CompactionTrigger.MANUAL)
                .preCompactTokenCount(preTokens).postCompactTokenCount(postTokens).messagesSummarized(1).startedAt(now)
                .completedAt(now).build();
        return CompactionResult.success("summary", metadata);
    }

    private static final class RecordingEngine implements CompactionEngine {
        private final AtomicReference<CompactionRequest> lastRequest = new AtomicReference<>();
        private CompactionResult nextResult;
        private RuntimeException nextException;

        void setNextResult(CompactionResult result) {
            this.nextResult = result;
            this.nextException = null;
        }

        void setNextException(RuntimeException exception) {
            this.nextException = exception;
            this.nextResult = null;
        }

        @Override
        public CompactionResult compact(CompactionRequest request) {
            lastRequest.set(request);
            if (nextException != null) {
                throw nextException;
            }
            return nextResult;
        }
    }

    private static final class RecordingGuard implements CompactionGuard {
        private final AtomicReference<SessionId> lastResetSessionId = new AtomicReference<>();

        @Override
        public at.aimon.core.agent.compact.CompactionDecision maybeCompact(TranscriptBuffer memory, LlmModel model,
                HookRegistry hookRegistry, Environment environment) {
            return at.aimon.core.agent.compact.CompactionDecision.none();
        }

        @Override
        public void recordExternalSuccess(SessionId sessionId) {
            lastResetSessionId.set(sessionId);
        }
    }
}
