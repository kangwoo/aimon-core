package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Covers the ReAct loop's handling of the <b>thread interrupt flag</b> — the second half of the interrupt story, which
 * no existing suite touches.
 *
 * <p>
 * {@code SingleToolInvoker} registers {@code toolThread::interrupt} as the terminator for every
 * {@link InterruptBehavior#THREAD_INTERRUPT} tool, and well-behaved tools <i>restore</i> the flag after catching
 * {@code InterruptedException} (see {@code BashTool}'s {@code Thread.currentThread().interrupt()} in its catch block).
 * Something must then <i>consume</i> it, because a live flag is not inert:
 * {@code DefaultHookExecutor#invokeWithTimeout} runs every hook through {@code future.get(timeout)}, which throws
 * {@code InterruptedException} <b>immediately</b> on an already-interrupted caller — before the hook's real result is
 * ever read — and maps it through {@code HookExecutionPolicy#onException}. The default PreTool/permission policy is
 * {@code continueOnExceptionButStopOnBlocked()}, whose mapper is {@code e -> HookResult.success()}. A PreTool hook
 * that returned {@code BLOCKED} was therefore silently replaced by a fail-open success — a permission gate bypassed
 * by an ambient thread flag.
 *
 * <p>
 * Both loop checkpoints now route through {@code CancellationSignals#isCancelledOrInterrupted}, which consumes the
 * flag and promotes it into the turn's {@code CancellationSignal}. Two finalisation points sweep whatever is left:
 * {@code invokeOnStop} (so the OnStop hooks are not fail-opened by the executor's timed {@code get}) and
 * {@code execute()}'s {@code finally} (so the end-of-turn persist runs on a clean thread).
 *
 * <p>
 * Swallowing the flag is only lossless when the turn <em>reported</em> the cancellation as
 * {@link CompletionReason#INTERRUPTED}. On any other outcome nothing else recorded the caller's request, so the flag
 * is re-armed after the persist. These tests pin every consequence: the PreTool block is enforced regardless of the
 * ambient flag, a turn that saw the flag ends as {@code INTERRUPTED} instead of drifting on, nothing escapes onto the
 * caller's thread on that path, the OnStop hooks still run to completion on an error exit, and an interrupt the turn
 * never reported is handed back rather than eaten.
 */
@DisplayName("OrcaAgentExecutor thread-interrupt-flag hygiene")
class OrcaAgentExecutorInterruptFlagHygieneTest {

    /**
     * How long the OnStop hook in {@link #onStopHooksStillRunToCompletionOnAnInterruptedThread} works for. The
     * assertion is about <i>synchronisation</i>, not speed: on a clean thread the hook executor's
     * {@code future.get(timeout)} waits the hook out (the default hook timeout is 30s), so its completion is visible
     * the instant {@code execute()} returns. With a live flag that {@code get} throws {@code InterruptedException}
     * immediately and the hook is abandoned mid-flight on the pool — a window this margin makes comfortably
     * observable without slowing the suite down.
     */
    private static final long ON_STOP_WORK_MILLIS = 300L;

    @TempDir
    Path tempDir;

    private final RecordingSessionRecordStore repository = new RecordingSessionRecordStore();

    /**
     * The whole point of these tests is that a set interrupt flag survives {@code execute()}. JUnit reuses this
     * thread for later tests, so clear it unconditionally rather than leaking a poisoned worker into the suite.
     */
    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("baseline: a PreTool hook returning BLOCKED does stop the tool when no interrupt flag is set")
    void preToolBlockIsHonouredOnACleanThread() {
        final RecordingTool recording = new RecordingTool();

        final ScriptedLlmClient llmClient = new ScriptedLlmClient();
        llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-1", RecordingTool.TOOL_NAME, Map.of())),
                TokenUsage.of(5, 5, 10)));
        llmClient.enqueue(LlmResponse.text("done"));

        run(llmClient, registryOf(recording), blockingPreToolHookFor(RecordingTool.TOOL_NAME));

        assertThat(recording.invocations.get()).as("the PreTool block is honoured on a clean thread").isZero();
    }

    @Test
    @DisplayName("a THREAD_INTERRUPT tool that restores the flag can no longer fail the NEXT tool's PreTool block open")
    void preToolBlockSurvivesAThreadInterruptTool() {
        final FlagRestoringTool flagRestoring = new FlagRestoringTool();
        final RecordingTool recording = new RecordingTool();

        final ScriptedLlmClient llmClient = new ScriptedLlmClient();
        // One batch: the flag-restoring tool first, then the tool the hook is supposed to block.
        llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-1", FlagRestoringTool.TOOL_NAME, Map.of()),
                ToolUse.of("tu-2", RecordingTool.TOOL_NAME, Map.of())), TokenUsage.of(5, 5, 10)));
        llmClient.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutionResult result = run(llmClient, registryOf(flagRestoring, recording),
                blockingPreToolHookFor(RecordingTool.TOOL_NAME));

        // Identical to the baseline above: the ambient flag no longer decides whether a permission gate holds.
        assertThat(recording.invocations.get())
                .as("an ambient interrupt flag must not downgrade a PreTool BLOCKED result into a fail-open success")
                .isZero();
        // The flag is consumed AND promoted, so the turn stops for a stated reason instead of silently continuing on
        // a thread someone asked to stop. (Here the gate skips tu-2 before the hook is even reached — the fail-open
        // window is closed at both ends.)
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(llmClient.callCount).as("no further LLM call is issued once the flag is promoted").isEqualTo(1);
    }

    @Test
    @DisplayName("the interrupt flag is consumed by the loop and never escapes execute() onto the caller's thread")
    void interruptFlagDoesNotEscapeTheTurn() {
        final FlagRestoringTool flagRestoring = new FlagRestoringTool();

        final ScriptedLlmClient llmClient = new ScriptedLlmClient();
        llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-1", FlagRestoringTool.TOOL_NAME, Map.of())),
                TokenUsage.of(5, 5, 10)));
        llmClient.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutionResult result = run(llmClient, registryOf(flagRestoring), null);

        // execute() runs on the caller's thread: the CLI's REPL thread, or — via executeAsync — a pooled worker that
        // would otherwise carry the flag into the NEXT turn's hooks. Thread.interrupted() reads-and-clears, so this
        // assertion doubles as the cleanup.
        assertThat(Thread.interrupted()).as("the loop consumes the flag a tool restored").isFalse();
        assertThat(result.getCompletionReason()).as("the interrupt is surfaced as a completion reason, not as a flag")
                .isEqualTo(CompletionReason.INTERRUPTED);
    }

    @Test
    @DisplayName("an exception exit with a live flag persists on a clean thread and hands the interrupt back")
    void exceptionExitSweepsBeforePersistAndRearmsTheCallersInterrupt() {
        final ScriptedLlmClient llmClient = new ScriptedLlmClient();
        // LlmCallGateway#sleepQuietly does exactly this when a retry backoff is interrupted: restore the flag, then
        // report the failure as an ordinary exception. The throw skips every loop checkpoint, so the finalisation
        // sweeps are the only thing standing between a live flag and the end-of-turn persist.
        llmClient.failWith(() -> {
            Thread.currentThread().interrupt();
            return new IllegalStateException("provider unavailable");
        });

        final OrcaAgentExecutionResult result = run(llmClient, registryOf(), null);

        assertThat(result.getCompletionReason()).as("a provider failure is an error, not a reported cancellation")
                .isEqualTo(CompletionReason.ERROR);
        // Pins the contract, not a particular sweep: whichever finalisation point gets there first, the end-of-turn
        // persist must run on a clean thread so an interrupt-sensitive SessionRecordStore can still write the turn
        // that just finished. On this path invokeOnStop's sweep already satisfies it, which is why removing ONLY the
        // finally's sweep leaves this green — that one is the backstop for a flag arriving after the OnStop hooks.
        assertThat(repository.interruptedDuringPersist).as("the end-of-turn persist runs on a swept thread").isFalse();
        // The result says ERROR, so nothing in it carries the caller's cancellation request. Eating the flag here
        // would silently break their cancellation protocol; it is re-armed instead. Thread.interrupted() reads and
        // clears, so this assertion doubles as the cleanup.
        assertThat(Thread.interrupted())
                .as("an interrupt the turn never reported as INTERRUPTED is handed back to the caller").isTrue();
    }

    @Test
    @DisplayName("OnStop hooks still run to completion when the turn finalises on an interrupted thread")
    void onStopHooksStillRunToCompletionOnAnInterruptedThread() {
        final AtomicBoolean onStopFinished = new AtomicBoolean();

        final ScriptedLlmClient llmClient = new ScriptedLlmClient();
        llmClient.failWith(() -> {
            Thread.currentThread().interrupt();
            return new IllegalStateException("provider unavailable");
        });

        final DefaultHookRegistry hookRegistry = new DefaultHookRegistry();
        hookRegistry.register(HookEventType.ON_STOP, (OnStopHook) context -> {
            sleepQuietly(ON_STOP_WORK_MILLIS);
            onStopFinished.set(true);
            return HookResult.success();
        });

        createExecutor(llmClient).execute(createContext(registryOf(), hookRegistry),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        // handleExecutionError invokes the OnStop hooks INSIDE the try block, i.e. well before execute()'s finally.
        // Without the sweep at the top of invokeOnStop, DefaultHookExecutor#invokeWithTimeout's future.get() throws
        // InterruptedException on the already-interrupted turn thread, its fail-open policy fabricates a success, and
        // the hook is abandoned mid-flight — cleanup and final metrics silently stop being synchronised with the turn.
        assertThat(onStopFinished).as("the OnStop hooks are awaited, not fail-opened by an ambient interrupt").isTrue();
    }

    // ============================== helpers ==============================

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private OrcaAgentExecutionResult run(LlmClient llmClient, DefaultToolRegistry toolRegistry,
            PreToolHook preToolHook) {
        final DefaultHookRegistry hookRegistry = new DefaultHookRegistry();
        if (preToolHook != null) {
            hookRegistry.register(HookEventType.PRE_TOOL, preToolHook);
        }
        return createExecutor(llmClient).execute(createContext(toolRegistry, hookRegistry),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());
    }

    private static PreToolHook blockingPreToolHookFor(String toolName) {
        return context -> toolName.equals(context.getCurrentToolUse().getName())
                ? HookResult.block("blocked by policy")
                : HookResult.success();
    }

    private static DefaultToolRegistry registryOf(AbstractTool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (AbstractTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry, DefaultHookRegistry hookRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry)
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(repository), toolManager, hookManager,
                commandManager, subagentManager);
    }

    /**
     * Mimics a real {@link InterruptBehavior#THREAD_INTERRUPT} tool that caught an {@code InterruptedException} and
     * restored the flag before returning — exactly what {@code BashTool} does in its catch block.
     */
    private static final class FlagRestoringTool extends AbstractTool {

        static final String TOOL_NAME = "FlagRestoring";

        FlagRestoringTool() {
            super(TOOL_NAME, "Restores the thread interrupt flag", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.THREAD_INTERRUPT;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            Thread.currentThread().interrupt();
            return ToolResult.success("flag restored");
        }
    }

    /** Counts how many times it ran — the probe for "was the PreTool block actually enforced?". */
    private static final class RecordingTool extends AbstractTool {

        static final String TOOL_NAME = "Recording";

        private final AtomicInteger invocations = new AtomicInteger();

        RecordingTool() {
            super(TOOL_NAME, "Records that it ran", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            invocations.incrementAndGet();
            return ToolResult.success("recorded");
        }
    }

    /**
     * Records whether the thread that reached the end-of-turn persist was carrying an interrupt. Uses the
     * non-destructive {@code isInterrupted()} read so observing the state cannot itself perform the sweep under test.
     *
     * <p>
     * One override suffices: {@code mergeFromSnapshot} is the only way a session's messages reach storage. This
     * class used to intercept a second write path as well, on the theory that persist "may route through either" — it
     * cannot, and no full-record write exists on {@link at.aimon.core.agent.session.store.SessionRecordStore} to
     * route to.
     */
    private static final class RecordingSessionRecordStore extends InMemorySessionRecordStore {

        private volatile boolean interruptedDuringPersist;

        @Override
        public void mergeFromSnapshot(SessionSnapshot snapshot) {
            interruptedDuringPersist |= Thread.currentThread().isInterrupted();
            super.mergeFromSnapshot(snapshot);
        }
    }

    /** Minimal scripted client; unscripted calls terminate the turn. */
    private static final class ScriptedLlmClient implements LlmClient {

        private final List<LlmResponse> responses = new ArrayList<>();
        private int callCount;
        private Supplier<RuntimeException> failure;

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        /**
         * Makes every call throw what {@code failure} supplies. The supplier — not a pre-built exception — is the
         * point: it lets a test set the interrupt flag at the moment of the throw, reproducing a provider client that
         * restored the flag on its way out.
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
            callCount++;
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
