package at.aimon.core.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.context.ContextDecision;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.context.ContextRequest;
import at.aimon.core.agent.context.ContextView;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.exception.LlmPromptTooLongException;

/**
 * Pins {@code /compact} built over a {@link ContextEngine}: the command hands the engine its request and instructions
 * and reports what {@link ContextEngine#compactNow} returned.
 */
class CompactCommandContextEngineTest {

    private static final LlmModel MODEL = LlmModel.builder().name("test-model").build();

    private final DefaultHookRegistry hookRegistry = new DefaultHookRegistry();
    private final Environment environment = Environment.createDefault();

    @Test
    void compactsThroughTheEngineWithTheCallersAttribution() {
        final RecordingEngine engine = new RecordingEngine(success());
        final CompactCommand command = new CompactCommand(engine, hookRegistry, new DefaultHookExecutionManager(),
                environment);
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.of("s-1"));
        memory.addUserMessage("hello");

        final CommandExecutionResult result = command.execute(context(command, memory), DirectCommandExecutionRequest
                .builder().arguments("  keep the api  ").principal(Principal.user("u-1", "Alice")).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Conversation compacted");
        assertThat(engine.instructions.get()).isEqualTo("keep the api");
        final ContextRequest seen = engine.request.get();
        assertThat(seen.getTranscriptBuffer()).isSameAs(memory);
        assertThat(seen.getModel()).isSameAs(MODEL);
        assertThat(seen.getHookRegistry()).containsSame(hookRegistry);
        assertThat(seen.getEnvironment()).containsSame(environment);
        assertThat(seen.getCaller().getPrincipal()).hasValue(Principal.user("u-1", "Alice"));
        assertThat(seen.getCallMetadata().orElseThrow().getComponent()).hasValue(CompactCommand.COMPONENT_NAME);
        assertThat(seen.getCallMetadata().orElseThrow().getTraceId()).hasValue("s-1");
    }

    @Test
    void reportsAnEngineThatCannotCompact() {
        final CompactCommand command = new CompactCommand(ContextEngine.passthrough(), hookRegistry,
                new DefaultHookExecutionManager(), environment);
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.of("s-1"));
        memory.addUserMessage("hello");

        final CommandExecutionResult result = command.execute(context(command, memory),
                DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResponse()).contains("Compaction failed");
    }

    @Test
    void rejectsANullEngine() {
        assertThatThrownBy(() -> new CompactCommand((ContextEngine) null, hookRegistry,
                new DefaultHookExecutionManager(), environment)).isInstanceOf(NullPointerException.class);
    }

    private static CommandExecutionContext context(CompactCommand command, TranscriptBuffer memory) {
        return CommandExecutionContext.builder().command(command).defaultModel(MODEL)
                .toolRegistry(new DefaultToolRegistry()).transcriptBuffer(memory).build();
    }

    private static CompactionResult success() {
        final Instant now = Instant.now();
        return CompactionResult.success("summary",
                CompactionMetadata.builder().trigger(CompactionTrigger.MANUAL).preCompactTokenCount(100)
                        .postCompactTokenCount(10).messagesSummarized(1).startedAt(now).completedAt(now).build());
    }

    /** Records the compactNow call and returns a canned result. */
    private static final class RecordingEngine implements ContextEngine {
        final AtomicReference<ContextRequest> request = new AtomicReference<>();
        final AtomicReference<String> instructions = new AtomicReference<>();
        private final CompactionResult result;

        RecordingEngine(CompactionResult result) {
            this.result = result;
        }

        @Override
        public ContextDecision prepare(ContextRequest request) {
            return ContextDecision.none(ContextView.of(request.getTranscriptBuffer().getMessages()));
        }

        @Override
        public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
            return Optional.empty();
        }

        @Override
        public CompactionResult compactNow(ContextRequest request, String instructions) {
            this.request.set(request);
            this.instructions.set(instructions);
            return result;
        }
    }
}
