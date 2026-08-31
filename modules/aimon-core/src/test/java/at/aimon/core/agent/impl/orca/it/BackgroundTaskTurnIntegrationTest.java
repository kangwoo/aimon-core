package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;

/**
 * L1 — background subagent tasks and the control-plane tools that observe them.
 *
 * <p>
 * A background {@code Task} is the only tool call in this suite whose effect outlives the tool call itself: it returns
 * a
 * task id immediately and the fork keeps running on another thread, so the parent turn continues while work is still in
 * flight. Every assertion here is about that seam — the id round-trips, {@code AgentOutput} finds the fork's answer,
 * {@code TaskList}/{@code TaskStop} see the same task, and one runtime's task id is worthless to another.
 *
 * <h2>Why the fork is parked in the LLM client, not in a tool</h2>
 *
 * <p>
 * Several tests need the background task to be provably <b>still running</b> at a chosen moment. Parking it inside a
 * tool would depend on the fork's tool registry containing that tool; parking it in its scripted LLM response does not
 * —
 * every fork must call the model at least once. {@link #parkedUntil} is that gate, and it fails loudly rather than
 * hanging: if the parent never opens the gate, the fork's wait times out and the test fails with a message naming the
 * cause.
 *
 * <p>
 * A timing note that shapes the assertions: {@code AgentOutput} with {@code block=false} answers
 * <em>"is still running"</em> both when the task is mid-flight and when it has been accepted but not yet picked up by
 * the pool. Asserting on that shared substring is what makes the polling tests deterministic instead of racing the
 * executor's scheduling.
 */
@DisplayName("RT-IT-L1: background subagent tasks through an assembled runtime")
class BackgroundTaskTurnIntegrationTest {

    private static final String NODE = "agent-a";
    private static final String OTHER_NODE = "agent-b";
    private static final String WORKER = "it-bg-worker";

    /** The launch observation's {@code Task ID: <uuid>} line — the only place the generated id is published. */
    private static final Pattern TASK_ID = Pattern.compile("Task ID: ([0-9a-fA-F-]{36})");

    /** Long enough for a loaded CI box, short enough that a genuine deadlock fails instead of hanging the build. */
    private static final int GATE_TIMEOUT_SECONDS = 30;

    /** Bounds {@code AgentOutput}'s blocking wait so a stuck fork fails the test rather than parking it for 150s. */
    private static final int WAIT_UP_TO_SECONDS = 30;

    /**
     * How long a stop is given to take effect. Kept far below {@link #GATE_TIMEOUT_SECONDS} on purpose — the gap is
     * what lets {@link #taskStopHaltsARunningTask} tell a stop that worked from one that was ignored.
     */
    private static final int STOP_SETTLE_TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        node = support.newNode(NODE, llm, OrcaRuntimeItSupport.options().codeSubagents(workerRegistry()));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private static InMemorySubagentRegistry workerRegistry() {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(Subagent.builder().name(WORKER).description("Integration-test background worker")
                .systemPrompt("You are the background worker.").maxIterations(4).build());
        return registry;
    }

    private static Map<String, Object> backgroundTaskInput(String prompt) {
        return Map.of("subagent_name", WORKER, "prompt", prompt, "description", "run a unit of work in the background",
                "run_in_background", true);
    }

    private static Map<String, Object> agentOutputInput(String taskId, boolean block) {
        return Map.of("taskId", taskId, "block", block, "wait_up_to", WAIT_UP_TO_SECONDS);
    }

    /**
     * Pulls the generated task id out of a launch observation.
     *
     * <p>
     * Throws rather than returning empty: a launch that published no id means the tests below would go on to poll the
     * string {@code "null"}, and the resulting "Task not found" would look like a control-plane bug instead of a
     * missing
     * id.
     */
    private static String taskIdFrom(ScriptedLlmClient.Call call) {
        final Matcher matcher = TASK_ID.matcher(call.lastObservation());
        if (!matcher.find()) {
            throw new AssertionError("no 'Task ID: <uuid>' in the launch observation: " + call.lastObservation());
        }
        return matcher.group(1);
    }

    /**
     * A fork response that waits for {@code gate} before answering, so the task is provably still running until the
     * parent opens it.
     *
     * <p>
     * An interrupt is answered normally rather than thrown: that is exactly what a stopped task does to its own thread,
     * and {@link #taskStopHaltsARunningTask} needs it to be an outcome rather than a failure.
     */
    private static ScriptedLlmClient.Responder parkedUntil(CountDownLatch gate, LlmResponse answer) {
        return call -> {
            try {
                if (!gate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("the background fork was never released after " + GATE_TIMEOUT_SECONDS
                            + "s — the parent turn never reached the point that opens the gate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ScriptedLlmClient.text("interrupted");
            }
            return answer;
        };
    }

    /**
     * Blocks until {@code taskId} reaches a terminal state on {@code node}'s control plane, and returns that state.
     *
     * <p>
     * Polling rather than a single read: a stop is filed synchronously but settled asynchronously, in the completion
     * callback of the fork's future. Reading the state once, right after the turn, would race that callback.
     */
    private BackgroundTaskState awaitTerminalState(String taskId, int timeoutSeconds) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        BackgroundTaskState last = null;
        while (System.nanoTime() < deadline) {
            last = node.subagentManager().status(taskId).map(BackgroundTask::getState)
                    .orElseThrow(() -> new AssertionError("task " + taskId + " is not in the control plane's store"));
            if (last.isTerminal()) {
                return last;
            }
            sleepBriefly();
        }
        throw new AssertionError("task " + taskId + " never reached a terminal state within " + timeoutSeconds
                + "s — last seen " + last);
    }

    private static void sleepBriefly() {
        try {
            TimeUnit.MILLISECONDS.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while polling for a task's terminal state", e);
        }
    }

    /** Waits on a rendezvous latch, turning the waiter's own interruption into a failure rather than a hang. */
    private static boolean await(CountDownLatch latch, int timeoutSeconds) {
        try {
            return latch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting on a rendezvous latch", e);
        }
    }

    @Test
    @DisplayName("a background launch returns a task id, and AgentOutput retrieves the fork's answer through it")
    void backgroundLaunchPublishesATaskIdThatAgentOutputCanRedeem() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(ScriptedLlmClient.forkRoute(sessionId.value(), WORKER),
                ScriptedLlmClient.text("SENTINEL-BG-RESULT-4a19"));
        llm.scriptDynamic(sessionId.value(), call -> ScriptedLlmClient.callTool("Task", backgroundTaskInput("work")),
                call -> ScriptedLlmClient.callTool("AgentOutput", agentOutputInput(taskIdFrom(call), true)),
                call -> ScriptedLlmClient.text("collected"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "run something in the background");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("collected");
        // The launch is what publishes the id; without it the model has no handle on the work it just started.
        assertThat(llm.callsFor(sessionId.value()).get(1).lastObservation())
                .contains("Background task launched successfully").contains(WORKER);
        // And redeeming that id yields the fork's own answer — proof the id addressed this task and not merely some
        // task. The fork ran on another thread, so this also pins that its result crosses back into the parent turn.
        assertThat(llm.lastCallFor(sessionId.value()).lastObservation()).contains("Background Task Result")
                .contains("SENTINEL-BG-RESULT-4a19");
    }

    /**
     * The claim that makes "background" mean anything: the parent turn keeps going while the fork is unfinished.
     *
     * <p>
     * The gate is only opened from the parent's <em>third</em> LLM call, so the fork cannot possibly have completed by
     * the time the parent polls on its second. If the launch secretly blocked until the fork was done, the fork would
     * wait on a gate that the parent can never reach and the test would fail with {@link #parkedUntil}'s message rather
     * than hanging.
     */
    @Test
    @DisplayName("the parent turn runs on while the background task is still in flight")
    void theParentTurnContinuesWhileTheTaskIsStillRunning() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicReference<String> taskId = new AtomicReference<>();

        llm.scriptDynamic(ScriptedLlmClient.forkRoute(sessionId.value(), WORKER),
                parkedUntil(gate, ScriptedLlmClient.text("SENTINEL-BG-LATE-7e52")));
        llm.scriptDynamic(sessionId.value(), call -> ScriptedLlmClient.callTool("Task", backgroundTaskInput("work")),
                call -> {
                    taskId.set(taskIdFrom(call));
                    // Non-blocking poll while the fork is parked: the answer must be "not yet", not the final result.
                    return ScriptedLlmClient.callTool("AgentOutput", agentOutputInput(taskId.get(), false));
                }, call -> {
                    // The parent got here with the fork still parked — the claim this test exists for. Only now is the
                    // fork allowed to finish, and the blocking read below collects what it produces.
                    gate.countDown();
                    return ScriptedLlmClient.callTool("AgentOutput", agentOutputInput(taskId.get(), true));
                }, call -> ScriptedLlmClient.text("finally collected"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "start work and check on it");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("finally collected");

        final List<ScriptedLlmClient.Call> calls = llm.callsFor(sessionId.value());
        // Call 2 saw the non-blocking poll. "is still running" covers both live states — accepted-but-unscheduled and
        // genuinely mid-flight — so this does not race the executor's scheduling.
        assertThat(calls.get(2).lastObservation()).contains("is still running").doesNotContain("SENTINEL-BG-LATE-7e52");
        // Call 3 saw the blocking read, which waited out the fork it had just released.
        assertThat(calls.get(3).lastObservation()).contains("SENTINEL-BG-LATE-7e52");
    }

    @Test
    @DisplayName("TaskList reports the launched task on the same control plane that spawned it")
    void taskListReportsTheLaunchedTask() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final AtomicReference<String> taskId = new AtomicReference<>();
        llm.script(ScriptedLlmClient.forkRoute(sessionId.value(), WORKER), ScriptedLlmClient.text("worker done"));
        llm.scriptDynamic(sessionId.value(), call -> ScriptedLlmClient.callTool("Task", backgroundTaskInput("work")),
                call -> {
                    taskId.set(taskIdFrom(call));
                    // Collect first, so the listing below is not racing an unscheduled task into existence.
                    return ScriptedLlmClient.callTool("AgentOutput", agentOutputInput(taskId.get(), true));
                }, call -> ScriptedLlmClient.callTool("TaskList", Map.of()), call -> ScriptedLlmClient.text("listed"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "start work and then list tasks");

        assertThat(result.isSuccess()).isTrue();
        // TaskTool and TaskListTool are handed the same SubagentExecutionManager. If they ever stopped sharing it, the
        // launch would still succeed and the listing would still render — just empty.
        assertThat(llm.lastCallFor(sessionId.value()).lastObservation()).contains("Background Tasks")
                .contains(taskId.get()).contains(WORKER);
    }

    /**
     * The stop must actually reach the worker, not merely be filed.
     *
     * <p>
     * {@code TaskStop}'s observation cannot say so on its own: {@code DefaultSubagentExecutionManager#stop} returns
     * {@code true} as soon as it finds a live handle and calls {@code requestStop()}, so <em>"Stop requested for
     * task"</em> survives a regression in which cancellation became a no-op. Nor does the terminal state on its own —
     * {@code finalizeBackgroundTask} derives {@code KILLED} from the {@code stopRequested} flag that
     * {@code requestStop()} sets <b>before</b> it interrupts anything, so a task that was asked to stop and ignored it
     * still settles as {@code KILLED} once it ends for any other reason.
     *
     * <h2>What makes the two controls decisive</h2>
     *
     * <p>
     * A rendezvous, and a timing gap. The fork counts {@code reachedTheModel} down before parking, and the parent waits
     * for it before issuing the stop — so the stop provably arrives while the worker is blocked in
     * {@code CountDownLatch.await}, on a gate nothing ever opens. From there the only exit is a thread interrupt, which
     * is exactly the lever {@code RunningTaskHandle#requestStop} claims to pull. Without this rendezvous the fork is
     * usually still starting up when the stop lands, and it aborts at its cancellation checkpoint without ever calling
     * the model — a real stop, but not the one whose absence this test is meant to detect.
     *
     * <p>
     * The settle timeout is then deliberately far below the gate: a working stop settles in milliseconds, while an
     * ignored one cannot settle before the fork's {@value #GATE_TIMEOUT_SECONDS}s gate expires. So
     * {@code KILLED within}
     * {@value #STOP_SETTLE_TIMEOUT_SECONDS}s means the task really stopped, not that it was merely labelled stopped.
     */
    @Test
    @DisplayName("TaskStop interrupts a task blocked mid-flight, and the task settles as killed")
    void taskStopHaltsARunningTask() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        // A gate nothing ever opens: the fork stays parked until the stop interrupts it, so the task is guaranteed to
        // be live when TaskStop reaches it. Without that guarantee the tool would sometimes answer "nothing to stop"
        // and the test would be asserting on whichever branch won the race.
        final CountDownLatch neverOpened = new CountDownLatch(1);
        final CountDownLatch reachedTheModel = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final AtomicReference<String> taskId = new AtomicReference<>();

        llm.scriptDynamic(ScriptedLlmClient.forkRoute(sessionId.value(), WORKER), call -> {
            reachedTheModel.countDown();
            try {
                if (!neverOpened.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError(
                            "the fork sat parked for " + GATE_TIMEOUT_SECONDS + "s — TaskStop never interrupted it");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
                return ScriptedLlmClient.text("interrupted");
            }
            return ScriptedLlmClient.text("should never be reached");
        });
        llm.scriptDynamic(sessionId.value(), call -> ScriptedLlmClient.callTool("Task", backgroundTaskInput("work")),
                call -> {
                    taskId.set(taskIdFrom(call));
                    // Hold the parent here until the fork is provably blocked inside its LLM call.
                    assertThat(await(reachedTheModel, GATE_TIMEOUT_SECONDS))
                            .as("the background fork never reached its first model call").isTrue();
                    return ScriptedLlmClient.callTool("TaskStop", Map.of("taskId", taskId.get()));
                }, call -> ScriptedLlmClient.text("stopped"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "start work and then stop it");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("stopped");
        assertThat(llm.lastCallFor(sessionId.value()).lastObservation()).contains("Stop requested for task");

        // The blocked worker thread really was interrupted — reported by the thread that took the interrupt, not by
        // the tool that asked for one.
        assertThat(await(interrupted, STOP_SETTLE_TIMEOUT_SECONDS))
                .as("the parked fork's thread was never interrupted by TaskStop").isTrue();
        // And it settled as a kill, quickly enough that the gate cannot be what released it.
        assertThat(awaitTerminalState(taskId.get(), STOP_SETTLE_TIMEOUT_SECONDS)).isEqualTo(BackgroundTaskState.KILLED);
    }

    /** Waits out the fork's interrupt, converting the wait's own interruption into a failure rather than a hang. */
    private static boolean awaitInterrupt(CountDownLatch interrupted) {
        try {
            return interrupted.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the fork's interrupt", e);
        }
    }

    @Test
    @DisplayName("an unknown task id is an observation the turn survives, not a failure")
    void unknownTaskIdIsObservedNotFatal() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("AgentOutput", agentOutputInput(UUID.randomUUID().toString(), false)),
                ScriptedLlmClient.text("moved on"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "read output for a task that was never launched");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("moved on");
        assertThat(llm.lastCallFor(sessionId.value()).lastObservation()).contains("Task not found");
    }

    /**
     * L3 territory check: a background task id is globally unique, so the only thing stopping one agent from reading
     * another's output is the control plane's ownership check.
     *
     * <p>
     * That claim only holds if both agents are looking at the <b>same</b> control plane, which is why the intruder is
     * built with {@link OrcaRuntimeItSupport.Options#shareSubagentManagerWith}. On the harness default each node gets
     * its own manager, and the assertion below would pass because the intruder's task store is empty — true of a
     * runtime with every ownership check deleted. Sharing is also the production shape: one
     * {@code OrcaAgentExecutor}, and therefore one task store, serves every runtime
     * {@code OrcaAgentRuntimeManager} creates. With it shared, "Task not found" can only come from
     * {@code ScopedSubagentTaskController} refusing a task stamped with a different {@code AgentRuntimeId}.
     *
     * <p>
     * The id is proven live and readable on its own runtime first. Without that half, the "not found" below would also
     * hold if the id had simply been wrong — and the deliberate design point here is that a <em>valid</em> foreign id
     * is answered with the same message as a nonexistent one ({@link #unknownTaskIdIsObservedNotFatal}), so that a
     * probing agent cannot learn whether the task exists.
     */
    @Test
    @DisplayName("one runtime cannot read another runtime's background task, even off a shared control plane")
    void backgroundTaskOutputDoesNotCrossRuntimes() {
        final SessionId ownerSession = OrcaRuntimeItSupport.newSession();
        final AtomicReference<String> taskId = new AtomicReference<>();
        llm.script(ScriptedLlmClient.forkRoute(ownerSession.value(), WORKER),
                ScriptedLlmClient.text("SENTINEL-BG-OWNED-2c88"));
        llm.scriptDynamic(ownerSession.value(), call -> ScriptedLlmClient.callTool("Task", backgroundTaskInput("work")),
                call -> {
                    taskId.set(taskIdFrom(call));
                    return ScriptedLlmClient.callTool("AgentOutput", agentOutputInput(taskId.get(), true));
                }, call -> ScriptedLlmClient.text("owner collected"));

        node.run(ownerSession, "run work in the background");

        // The owner really can redeem this id — so the failure below is about ownership, not about a bad id.
        assertThat(llm.lastCallFor(ownerSession.value()).lastObservation()).contains("SENTINEL-BG-OWNED-2c88");

        final OrcaRuntimeItSupport.Node other = support.newNode(OTHER_NODE, llm,
                OrcaRuntimeItSupport.options().codeSubagents(workerRegistry()).shareSubagentManagerWith(node));
        // The intruder is looking at the very store that holds the task — so the id it is about to use is not just
        // valid, it is resolvable from where it stands. Only the ownership check stands between the two.
        assertThat(other.subagentManager().status(taskId.get())).isPresent();

        final SessionId intruderSession = OrcaRuntimeItSupport.newSession();
        llm.script(intruderSession.value(),
                ScriptedLlmClient.callTool("AgentOutput", agentOutputInput(taskId.get(), false)),
                ScriptedLlmClient.text("nothing to see"));

        final OrcaAgentExecutionResult intrusion = other.run(intruderSession, "read that other agent's task output");

        assertThat(intrusion.isSuccess()).isTrue();
        assertThat(llm.lastCallFor(intruderSession.value()).lastObservation()).contains("Task not found")
                .doesNotContain("SENTINEL-BG-OWNED-2c88");
    }
}
