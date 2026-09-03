package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.memory.ExecutionMemorySink;
import at.aimon.core.memory.ExecutionMemoryUpdate;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * The memory write seam as the executor actually drives it — the half no other suite reaches.
 *
 * <p>
 * {@code ExecutionMemoryIngestTest} covers the two ends in isolation: {@code TranscriptBuffer}'s mark-and-delta, and
 * {@code IngestingExecutionMemorySink}'s peer resolution. Neither runs {@code OrcaAgentExecutor}, so the wiring
 * between them — the mark set at the top of {@code execute()} and the feed in its {@code finally} — was asserted
 * nowhere.
 *
 * <p>
 * The case that matters most is the interrupted one. {@code feedExecutionMemory} promises that "an interrupted or
 * failed execution feeds too", and the {@code finally} it sits in also re-arms the caller's interrupt flag. The order
 * of those two is invisible to every other test and decides whether the promise holds for any sink that can be
 * interrupted — which is every sink that reaches a network, i.e. the reason the seam exists.
 */
@DisplayName("OrcaAgentExecutor execution-memory ingest seam")
class OrcaAgentExecutorMemoryIngestTest {

    private static final Principal CALLER = Principal.user("alice", "Alice");

    @TempDir
    Path tempDir;

    private final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();

    /** These tests deliberately leave an interrupt in flight; JUnit reuses the thread, so never leak one. */
    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("a turn feeds exactly what it added, under its own session and principal")
    void aTurnFeedsItsOwnMessages() {
        final RecordingSink sink = new RecordingSink();
        final ScriptedLlmClient llmClient = new ScriptedLlmClient();
        llmClient.enqueue(LlmResponse.text("hello back"));

        final SessionId sessionId = SessionId.generate();
        run(llmClient, sink, sessionId, "hi");

        assertThat(sink.updates).hasSize(1);
        final ExecutionMemoryUpdate update = sink.updates.get(0);
        assertThat(update.getSessionId()).contains(sessionId);
        assertThat(update.getPrincipal()).contains(CALLER);
        // containsExactly, not contains: the display name says "exactly what it added", and a regression that fed
        // the whole transcript instead of the delta would satisfy a containment check.
        assertThat(update.getMessages()).extracting(Message::getContent).containsExactly("hi", "hello back");
    }

    @Test
    @DisplayName("a second turn feeds only its own delta, not the first turn's messages again")
    void consecutiveTurnsDoNotOverlap() {
        final RecordingSink sink = new RecordingSink();
        final ScriptedLlmClient llmClient = new ScriptedLlmClient();
        llmClient.enqueue(LlmResponse.text("first answer"));
        llmClient.enqueue(LlmResponse.text("second answer"));

        final SessionId sessionId = SessionId.generate();
        final OrcaAgentExecutor executor = createExecutor(llmClient, sink);
        final OrcaAgentRuntime runtime = createRuntime();
        executor.execute(runtime, request(sessionId, "first question"));
        executor.execute(runtime, request(sessionId, "second question"));

        assertThat(sink.updates).hasSize(2);
        assertThat(sink.updates.get(1).getMessages()).extracting(Message::getContent)
                .as("the mark moves with each execution, so nothing is offered twice")
                .contains("second question", "second answer").doesNotContain("first question", "first answer");
    }

    @Test
    @DisplayName("an execution interrupted mid-flight still feeds, and feeds on a thread with no live interrupt")
    void anInterruptedExecutionFeedsOnACleanThread() {
        final RecordingSink sink = new RecordingSink();
        final ScriptedLlmClient llmClient = new ScriptedLlmClient();
        // What LlmCallGateway#sleepQuietly does when a retry backoff is interrupted: restore the flag, then report
        // the failure as an ordinary exception. The turn ends as ERROR, so execute()'s finally re-arms the caller's
        // interrupt — and the sink must have been fed before that happens.
        llmClient.failWith(() -> {
            Thread.currentThread().interrupt();
            return new IllegalStateException("provider unavailable");
        });

        final OrcaAgentExecutionResult result = run(llmClient, sink, SessionId.generate(), "hi");

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
        assertThat(sink.updates).as("what was said still happened, so a failed execution is still offered").hasSize(1);
        // The assertion this test exists for. A sink is contracted to be quick, not to be uninterruptible: called
        // under a live flag, any interruptible call inside it (a bounded queue's put, an HTTP client, a Future#get)
        // throws on entry and the execution's messages are lost — for precisely the executions feedExecutionMemory
        // promises to feed. isInterrupted() rather than interrupted() so observing cannot itself clear the flag.
        assertThat(sink.interruptedWhenCalled).as("the memory feed runs on the swept side of the interrupt re-arm")
                .isFalse();
        // And the sweep is not a theft: the caller's cancellation is still handed back afterwards.
        assertThat(Thread.interrupted()).as("the caller's interrupt is re-armed after the feed, not instead of it")
                .isTrue();
    }

    @Test
    @DisplayName("a sink that throws does not fail the execution — memory is an enrichment")
    void aThrowingSinkDoesNotFailTheExecution() {
        final ScriptedLlmClient llmClient = new ScriptedLlmClient();
        llmClient.enqueue(LlmResponse.text("hello back"));

        final OrcaAgentExecutionResult result = run(llmClient, update -> {
            throw new IllegalStateException("memory backend unreachable");
        }, SessionId.generate(), "hi");

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.getFinalAnswer()).isEqualTo("hello back");
    }

    @Test
    @DisplayName("a sink that throws an Error still does not cost the caller their interrupt")
    void aSinkThrowingAnErrorStillRearmsTheInterrupt() {
        final ScriptedLlmClient llmClient = new ScriptedLlmClient();
        llmClient.failWith(() -> {
            Thread.currentThread().interrupt();
            return new IllegalStateException("provider unavailable");
        });
        // feedExecutionMemory guards with catch (RuntimeException), so an Error goes straight through it: a
        // LinkageError from an optional backend jar, or an AssertionError from a sink written with `assert`. Since
        // the feed now precedes the re-arm, that Error must not be able to carry the re-arm away with it.
        final ExecutionMemorySink erroring = update -> {
            throw new LinkageError("memory adapter jar is missing a class");
        };

        assertThatThrownBy(() -> run(llmClient, erroring, SessionId.generate(), "hi")).isInstanceOf(LinkageError.class);

        assertThat(Thread.interrupted()).as("the re-arm runs in a finally, so an Error cannot swallow the caller's"
                + " cancellation on its way out").isTrue();
    }

    @Test
    @DisplayName("with no sink wired the turn is unchanged — the seam is opt-in")
    void noSinkIsTheDefault() {
        final ScriptedLlmClient llmClient = new ScriptedLlmClient();
        llmClient.enqueue(LlmResponse.text("hello back"));

        final OrcaAgentExecutionResult result = run(llmClient, null, SessionId.generate(), "hi");

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    // ============================== helpers ==============================

    private OrcaAgentExecutionResult run(LlmClient llmClient, ExecutionMemorySink sink, SessionId sessionId,
            String userInput) {
        return createExecutor(llmClient, sink).execute(createRuntime(), request(sessionId, userInput));
    }

    private static OrcaAgentExecutionRequest request(SessionId sessionId, String userInput) {
        return OrcaAgentExecutionRequest.builder().userInput(userInput).sessionId(sessionId).principal(CALLER).build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient llmClient, ExecutionMemorySink sink) {
        return new OrcaAgentExecutorFactory().withExecutionMemorySink(sink).create(llmClient,
                new DefaultTranscriptManager(repository));
    }

    private OrcaAgentRuntime createRuntime() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    /**
     * Captures every offer, and the interrupt state of the thread that made it.
     *
     * <p>
     * Deliberately not thread-safe. {@code execute()} is synchronous and the sink runs in its {@code finally}, so
     * every call is on the test's own thread. Marking the flag {@code volatile} beside a plain {@code ArrayList}
     * would claim two different things about one call — and be wrong twice over if it were ever true, since a
     * non-atomic {@code |=} loses updates and the list corrupts.
     */
    private static final class RecordingSink implements ExecutionMemorySink {

        private final List<ExecutionMemoryUpdate> updates = new ArrayList<>();
        private boolean interruptedWhenCalled;

        @Override
        public void afterExecution(ExecutionMemoryUpdate update) {
            interruptedWhenCalled |= Thread.currentThread().isInterrupted();
            updates.add(update);
        }
    }

    /** Minimal scripted client; unscripted calls end the turn. Mirrors the one in the interrupt-hygiene suite. */
    private static final class ScriptedLlmClient implements LlmClient {

        private final List<LlmResponse> responses = new ArrayList<>();
        private Supplier<RuntimeException> failure;

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        /**
         * A supplier rather than a prebuilt exception, so a test can set the interrupt flag at the moment of the
         * throw — reproducing a provider client that restored the flag on its way out.
         */
        void failWith(Supplier<RuntimeException> failure) {
            this.failure = failure;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            if (failure != null) {
                throw failure.get();
            }
            if (!responses.isEmpty()) {
                return responses.remove(0);
            }
            return LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10));
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }
}
