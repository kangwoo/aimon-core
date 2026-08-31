/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.InterruptAccess;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.event.ScheduledTaskEvent;
import at.aimon.core.scheduling.event.ScheduledTaskEventListener;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.TaskCompletedEvent;
import at.aimon.core.scheduling.event.TaskFailedEvent;
import at.aimon.core.scheduling.event.TaskInterruptedEvent;

/**
 * Pins the cancellation contract of {@link RoutineExecutor}: a run that has been interrupted stops, stops soon, and
 * says so.
 *
 * <p>
 * The tests split along the three rungs of the propagation ladder documented on the executor — the unconditional step
 * boundary, the cooperative signal, and outright termination — plus the two places a cancelled run could quietly
 * become something else: the retry backoff (where nothing is executing to notice) and the result, which must not read
 * as a failure.
 *
 * <p>
 * Where it can be, the interrupt is raised from inside the step itself. That is not how it happens in production, but
 * it removes the thread handoff from tests that are not about timing, leaving only the two that genuinely are —
 * termination and shutdown, both of which are claims about a blocked step unwinding.
 */
class RoutineExecutorInterruptTest {

    /**
     * Long enough that a step reaching its own timeout would fail these tests rather than pass them: every "the run
     * ended promptly" assertion below is only worth something if timing out is visibly slower than being stopped.
     */
    private static final Duration GENEROUS_STEP_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration TEST_PATIENCE = Duration.ofSeconds(10);

    private DefaultAgentRuntimeRegistry agentRuntimeRegistry;
    private SimpleScheduledTaskEventPublisher eventPublisher;
    private RecordingListener events;
    private RoutineExecutor executor;

    @BeforeEach
    void setUp() {
        agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();
        eventPublisher = new SimpleScheduledTaskEventPublisher();
        events = new RecordingListener();
        eventPublisher.addListener(events);
        executor = new RoutineExecutor(agentRuntimeRegistry, eventPublisher);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    /**
     * The bottom rung, and the one the other two are optimisations of: a routine whose tools do not participate in
     * cancellation at all still stops.
     *
     * <p>
     * The tool here declares the default {@link InterruptBehavior#NON_INTERRUPTIBLE} and ignores the signal entirely,
     * so the step that is running when the interrupt lands runs to completion — that is the contract, not a defect.
     * What must not happen is the fourth step, or the third, starting afterwards.
     */
    @Test
    void runStopsAtTheNextStepBoundaryEvenWhenTheToolIgnoresInterrupts() {
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicReference<ScheduledTaskId> taskId = new AtomicReference<>();

        final Tool tool = new ScriptedTool("noop", InterruptBehavior.NON_INTERRUPTIBLE, context -> {
            if (invocations.incrementAndGet() == 2) {
                executor.interrupt(taskId.get(), InterruptReason.TASK_CANCELLED);
            }
        });

        final ScheduledTask task = register(tool, 4);
        taskId.set(task.getId());

        final RoutineResult result = executor.execute(task);

        // Steps 1 and 2 ran; the interrupt landed during step 2, and steps 3 and 4 were never started.
        assertThat(invocations).hasValue(2);
        assertThat(result.getTotalStepCount()).isEqualTo(2);
        assertThat(result.getCompletedStepCount()).isEqualTo(2);

        assertThat(result.isCancelled()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getInterruptReason()).contains(InterruptReason.TASK_CANCELLED);
    }

    /**
     * A cancelled run must not be filed as a broken one.
     *
     * <p>
     * This is the failure mode with teeth. An interrupt that lands inside a step surfaces as an ordinary step failure
     * — a terminated tool returns an error like any other — so without an explicit read of the signal the run would
     * report {@code FAILURE}, publish {@link TaskFailedEvent}, and put a fault on a task that has none. Both halves
     * are asserted: what is published, and what is not.
     */
    @Test
    void aRunStoppedMidStepReportsCancelledRatherThanFailed() {
        final AtomicReference<ScheduledTaskId> taskId = new AtomicReference<>();

        final Tool tool = new ScriptedTool("stoppable", InterruptBehavior.COOPERATIVE, context -> {
            executor.interrupt(taskId.get(), InterruptReason.TASK_CANCELLED);
            throw new IllegalStateException("step aborted by the interrupt");
        });

        final ScheduledTask task = register(tool, 2);
        taskId.set(task.getId());

        final RoutineResult result = executor.execute(task);

        assertThat(result.isCancelled()).isTrue();
        assertThat(result.getInterruptReason()).contains(InterruptReason.TASK_CANCELLED);

        assertThat(events.ofType(TaskInterruptedEvent.class)).singleElement()
                .satisfies(e -> assertThat(e.getReason()).isEqualTo(InterruptReason.TASK_CANCELLED));
        assertThat(events.ofType(TaskFailedEvent.class)).isEmpty();
        assertThat(events.ofType(TaskCompletedEvent.class)).isEmpty();

        // The step's own error survives into the record — reclassifying the run does not hide what the step reported.
        assertThat(result.getStepResults()).singleElement().satisfies(step -> assertThat(step.isSuccess()).isFalse());
    }

    /**
     * The middle rung: a {@link InterruptBehavior#COOPERATIVE} step is handed a live signal on its
     * {@link ToolContext}, so it can return from inside a step instead of running to its end.
     *
     * <p>
     * Asserting that the signal reads {@code cancelled} — rather than merely that some signal is present — is what
     * makes this a test. {@link InterruptAccess#signalOf(ToolContext)} substitutes a no-op signal when the key is
     * missing, so a context that never carried one would still hand the tool an object, and that object would
     * cheerfully report that nothing had been cancelled.
     */
    @Test
    void cooperativeStepsAreHandedTheRunsLiveCancellationSignal() {
        final AtomicReference<ScheduledTaskId> taskId = new AtomicReference<>();
        final AtomicReference<Boolean> observedCancelled = new AtomicReference<>();

        final Tool tool = new ScriptedTool("poller", InterruptBehavior.COOPERATIVE, context -> {
            final CancellationSignal signal = InterruptAccess.signalOf(context);
            assertThat(signal.isCancelled()).isFalse();

            executor.interrupt(taskId.get(), InterruptReason.TASK_CANCELLED);

            // The same instance the executor tripped, seen from inside the step.
            observedCancelled.set(signal.isCancelled());
        });

        final ScheduledTask task = register(tool, 2);
        taskId.set(task.getId());

        final RoutineResult result = executor.execute(task);

        assertThat(observedCancelled.get()).isTrue();
        assertThat(result.isCancelled()).isTrue();
    }

    /**
     * The top rung: a step that is blocked is terminated where it stands rather than waited out.
     *
     * <p>
     * The tool declares {@link InterruptBehavior#THREAD_INTERRUPT} and parks for far longer than the test is willing
     * to wait, so the assertion is load-bearing in both directions — the run can only finish this quickly if the
     * terminator fired, and a regression that dropped the terminator would leave the step to its
     * {@link #GENEROUS_STEP_TIMEOUT}.
     */
    @Test
    void threadInterruptStepsAreTerminatedInsteadOfWaitedOut() throws Exception {
        final CountDownLatch stepEntered = new CountDownLatch(1);
        final CountDownLatch neverCounted = new CountDownLatch(1);

        final Tool tool = new ScriptedTool("blocking", InterruptBehavior.THREAD_INTERRUPT, context -> {
            stepEntered.countDown();
            neverCounted.await(GENEROUS_STEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        });

        final ScheduledTask task = register(tool, 1);
        final CompletableFuture<RoutineResult> run = CompletableFuture.supplyAsync(() -> executor.execute(task));

        assertThat(stepEntered.await(TEST_PATIENCE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(executor.isRunning(task.getId())).isTrue();
        assertThat(executor.interrupt(task.getId(), InterruptReason.TASK_CANCELLED)).isTrue();

        final RoutineResult result = run.get(TEST_PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(result.isCancelled()).isTrue();
        assertThat(result.getInterruptReason()).contains(InterruptReason.TASK_CANCELLED);
        assertThat(executor.isRunning(task.getId())).isFalse();
    }

    /**
     * The gap between steps is as much a place to be stuck as a step is.
     *
     * <p>
     * Nothing is executing during a retry backoff, so there is no tool to poll the signal and no future to terminate:
     * a plain sleep would hold a cancelled run for the rest of the delay, and delays are configured in minutes. The
     * step here fails on its first attempt and trips the signal on the way out, so the executor arrives at a backoff
     * it must already refuse to serve.
     */
    @Test
    void anInterruptCutsShortTheRetryBackoffInsteadOfSleepingItOut() {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<ScheduledTaskId> taskId = new AtomicReference<>();

        final Tool tool = new ScriptedTool("flaky", InterruptBehavior.COOPERATIVE, context -> {
            attempts.incrementAndGet();
            executor.interrupt(taskId.get(), InterruptReason.TASK_CANCELLED);
            throw new IllegalStateException("attempt failed");
        });

        final ScheduledTask task = registerWithRetries(tool, 3, Duration.ofSeconds(30));
        taskId.set(task.getId());

        final long startedNanos = System.nanoTime();
        final RoutineResult result = executor.execute(task);
        final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

        assertThat(attempts).hasValue(1);
        assertThat(result.isCancelled()).isTrue();
        // Three retries at thirty seconds each is what this must not have done.
        assertThat(elapsed).isLessThan(TEST_PATIENCE);
    }

    /**
     * Shutting the executor down is a cancellation of everything still running, and it has to happen before the pool
     * is asked to drain: a step that has not been told to stop cannot stop, so the grace period would simply expire.
     */
    @Test
    void shutdownStopsRunsAlreadyInFlight() throws Exception {
        final CountDownLatch stepEntered = new CountDownLatch(1);
        final CountDownLatch neverCounted = new CountDownLatch(1);

        final Tool tool = new ScriptedTool("blocking", InterruptBehavior.THREAD_INTERRUPT, context -> {
            stepEntered.countDown();
            neverCounted.await(GENEROUS_STEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        });

        final ScheduledTask task = register(tool, 1);
        final CompletableFuture<RoutineResult> run = CompletableFuture.supplyAsync(() -> executor.execute(task));

        assertThat(stepEntered.await(TEST_PATIENCE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        executor.shutdown();

        final RoutineResult result = run.get(TEST_PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(result.isCancelled()).isTrue();
        assertThat(result.getInterruptReason()).contains(InterruptReason.SYSTEM_SHUTDOWN);
    }

    /**
     * Nothing running is not an error, and it is not a success either. A caller has to be able to tell "I stopped it"
     * from "there was nothing here to stop" — in a scale-out deployment the second answer is how a caller learns the
     * run belongs to another node.
     */
    @Test
    void interruptingATaskThatIsNotRunningHereReportsThatNothingWasStopped() {
        final Tool tool = new ScriptedTool("noop", InterruptBehavior.NON_INTERRUPTIBLE, context -> {
        });
        final ScheduledTask task = register(tool, 1);

        assertThat(executor.isRunning(task.getId())).isFalse();
        assertThat(executor.interrupt(task.getId(), InterruptReason.TASK_CANCELLED)).isFalse();

        // And the signal did not linger: a later run of the same task is unaffected.
        assertThat(executor.execute(task).isSuccess()).isTrue();
    }

    /**
     * A run that was never interrupted must be untouched by any of this — the cancelled path is an addition, not a
     * new way for an ordinary routine to end.
     */
    @Test
    void anUninterruptedRunStillCompletesNormally() {
        final AtomicInteger invocations = new AtomicInteger();
        final Tool tool = new ScriptedTool("noop", InterruptBehavior.NON_INTERRUPTIBLE,
                context -> invocations.incrementAndGet());

        final RoutineResult result = executor.execute(register(tool, 3));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isCancelled()).isFalse();
        assertThat(result.getInterruptReason()).isEmpty();
        assertThat(invocations).hasValue(3);
        assertThat(events.ofType(TaskCompletedEvent.class)).hasSize(1);
        assertThat(events.ofType(TaskInterruptedEvent.class)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------------------------

    private ScheduledTask register(Tool tool, int stepCount) {
        return registerWithRetries(tool, 0, Duration.ZERO, stepCount);
    }

    private ScheduledTask registerWithRetries(Tool tool, int maxRetries, Duration retryDelay) {
        return registerWithRetries(tool, maxRetries, retryDelay, 1);
    }

    private ScheduledTask registerWithRetries(Tool tool, int maxRetries, Duration retryDelay, int stepCount) {
        final Agent agent = DefaultAgent.builder().name("routine-interrupt").systemPrompt("test").build();
        final AgentRuntimeId boundRuntimeId = AgentRuntimeId.from(agent);
        agentRuntimeRegistry.register(new StubAgentRuntime(boundRuntimeId, agent, List.of(tool)));

        final List<RoutineStep> steps = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(RoutineStep.builder().tool(tool.getDefinition().getName()).toolParams("{}").maxRetries(maxRetries)
                    .retryDelay(retryDelay).timeout(GENEROUS_STEP_TIMEOUT).build());
        }

        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("test-task").cronExpression("*/5 * * * *")
                .routine(steps).owner(Principal.system()).boundRuntimeId(boundRuntimeId).enabled(true).build();
    }

    /** Minimal {@link AgentRuntime} stub returning a fixed tool list. */
    private static final class StubAgentRuntime implements AgentRuntime {

        private final AgentRuntimeId id;
        private final Agent agent;
        private final List<Tool> tools;

        StubAgentRuntime(AgentRuntimeId id, Agent agent, List<Tool> tools) {
            this.id = Objects.requireNonNull(id);
            this.agent = Objects.requireNonNull(agent);
            this.tools = List.copyOf(tools);
        }

        @Override
        public AgentRuntimeId getId() {
            return id;
        }

        @Override
        public Agent getAgent() {
            return agent;
        }

        @Override
        public List<Tool> getAvailableTools() {
            return tools;
        }
    }

    /**
     * Test tool whose body is supplied per test and whose {@link InterruptBehavior} is declared per test.
     *
     * <p>
     * A body that throws becomes a failed step, which is how the tests reproduce a step that was cut short: the
     * executor cannot tell the difference between that and a tool that was terminated, and it is not supposed to.
     */
    private static final class ScriptedTool extends AbstractTool {

        private final InterruptBehavior behavior;
        private final ThrowingConsumer body;

        ScriptedTool(String name, InterruptBehavior behavior, ThrowingConsumer body) {
            super(name, "test tool", Map.of("type", "object", "additionalProperties", false, "properties", Map.of()));
            this.behavior = behavior;
            this.body = body;
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return behavior;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            try {
                body.accept(context);
                return ToolResult.success("ok");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted");
            } catch (Exception e) {
                return ToolResult.error(e.getMessage());
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer {
        void accept(ToolContext context) throws Exception;
    }

    /** Collects every published event so a test can assert on what was <em>not</em> published as well. */
    private static final class RecordingListener implements ScheduledTaskEventListener {

        private final List<ScheduledTaskEvent> received = new ArrayList<>();

        <T extends ScheduledTaskEvent> List<T> ofType(Class<T> type) {
            synchronized (received) {
                return received.stream().filter(type::isInstance).map(type::cast).toList();
            }
        }

        private void record(ScheduledTaskEvent event) {
            synchronized (received) {
                received.add(event);
            }
        }

        @Override
        public void onTaskCompleted(TaskCompletedEvent event) {
            record(event);
        }

        @Override
        public void onTaskFailed(TaskFailedEvent event) {
            record(event);
        }

        @Override
        public void onTaskInterrupted(TaskInterruptedEvent event) {
            record(event);
        }
    }
}
