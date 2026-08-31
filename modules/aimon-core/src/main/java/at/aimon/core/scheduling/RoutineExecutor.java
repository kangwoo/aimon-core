/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import at.aimon.core.agent.AgentDefinitionVersion;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.ExternallyManaged;
import at.aimon.core.scheduling.event.ScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.StepCompletedEvent;
import at.aimon.core.scheduling.event.StepFailedEvent;
import at.aimon.core.scheduling.event.TaskCompletedEvent;
import at.aimon.core.scheduling.event.TaskFailedEvent;
import at.aimon.core.scheduling.event.TaskInterruptedEvent;
import at.aimon.core.scheduling.event.TaskStartedEvent;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Executes routines for scheduled tasks.
 *
 * <p>
 * The executor runs each step in sequence, with retry and timeout support. Results from previous steps can be passed to
 * subsequent steps.
 * </p>
 *
 * <p>
 * Tools are resolved at execution time by looking up the task's {@link ScheduledTask#getBoundRuntimeId() bound
 * agent runtime} from the {@link AgentRuntimeRegistry}.
 * </p>
 *
 * <h2>Cancelling a run in flight</h2>
 *
 * <p>
 * Every run owns a fresh {@link InterruptCoordinator} for its lifetime, exactly as a session's turn does, and
 * {@link #interrupt(ScheduledTaskId, InterruptReason)} trips it from any thread. Unscheduling a task cannot stop work
 * that has already begun &mdash; a routine with a long-running step would otherwise carry on for minutes after its
 * owner cancelled it, which is the failure this seam exists to close.
 *
 * <p>
 * Propagation follows the framework's cooperative-first rule (see {@code docs/design/agent-execution/interrupt.md}),
 * so what an interrupt actually stops depends on the step's tool:
 *
 * <ul>
 * <li>The run stops at the next <b>step boundary</b> unconditionally. This holds for every tool, including ones that
 * ignore interrupts entirely, and it is the guarantee the other two are optimisations of.
 * <li>{@link InterruptBehavior#COOPERATIVE} steps see the signal through
 * {@link InterruptToolKeys#CANCELLATION_SIGNAL} on their {@link ToolContext} and can return early from within a step.
 * <li>{@link InterruptBehavior#THREAD_INTERRUPT} and {@link InterruptBehavior#EXTERNALLY_TERMINATED} steps are
 * terminated where they stand &mdash; see {@link #executeWithTimeout}.
 * </ul>
 *
 * <p>
 * <b>Node-locality.</b> The in-flight registry is this instance's; an interrupt can only be honoured by the node
 * running the work. In a scale-out deployment a run on another node is reported as "nothing running here" rather than
 * stopped, the same limitation {@code TaskStop} carries for background subagent tasks.
 */
public class RoutineExecutor {

    private static final Logger log = LoggerFactory.getLogger(RoutineExecutor.class);

    /**
     * Pattern for template variables: $step.index.result where index is a 0-based step index.
     */
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$step\\.([0-9]+)\\.result");

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {
    };

    @ExternallyManaged
    private final AgentRuntimeRegistry agentRuntimeRegistry;
    private final ScheduledTaskEventPublisher eventPublisher;
    private final Clock clock;
    private final ExecutorService timeoutExecutor;
    private final ObjectMapper objectMapper;

    /**
     * Coordinators of the runs currently in progress on this instance, so an interrupt arriving on another thread can
     * find them.
     *
     * <p>
     * A task maps to a <em>set</em> of coordinators rather than to one, because "one run per task at a time" is not
     * this class's guarantee to make: it belongs to {@link ScheduledExecutionGuard}, whose contract explicitly admits
     * a caller that wants overlap ({@link ScheduledExecutionGuard#ALLOW_ALL}). Keying on the task and holding every
     * run under it means an interrupt stops all of that task's work here, which is what the request asks for, however
     * many runs the guard let through.
     */
    private final Map<ScheduledTaskId, Set<InterruptCoordinator>> inFlightRuns = new ConcurrentHashMap<>();

    /**
     * RoutineExecutor를 생성한다 (system UTC clock 사용).
     */
    public RoutineExecutor(@ExternallyManaged AgentRuntimeRegistry agentRuntimeRegistry,
            ScheduledTaskEventPublisher eventPublisher) {
        this(agentRuntimeRegistry, eventPublisher, Clock.systemUTC());
    }

    /**
     * RoutineExecutor를 생성한다.
     *
     * <p>
     * 결정론적 테스트를 위해 {@link Clock}을 주입할 수 있다. 프로덕션 코드에서는 일반적으로
     * {@link #RoutineExecutor(AgentRuntimeRegistry, ScheduledTaskEventPublisher)} 오버로드를 통해
     * {@link Clock#systemUTC()}을 사용한다.
     */
    public RoutineExecutor(@ExternallyManaged AgentRuntimeRegistry agentRuntimeRegistry,
            ScheduledTaskEventPublisher eventPublisher, Clock clock) {
        this.agentRuntimeRegistry = Objects.requireNonNull(agentRuntimeRegistry, "Context registry cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "Event publisher cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        objectMapper = JsonMapper.builder().build();
        timeoutExecutor = Executors.newCachedThreadPool(r -> {
            final Thread thread = new Thread(r, "routine-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Executes a routine for the given scheduled task.
     *
     * @param task
     *            the scheduled task to execute
     * @return the routine execution result
     */
    public RoutineResult execute(ScheduledTask task) {
        Objects.requireNonNull(task, "Task cannot be null");

        final InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        registerRun(task.getId(), coordinator);
        try {
            return runRoutine(task, coordinator);
        } finally {
            // Deregister before closing, so an interrupt racing this teardown either finds the coordinator and trips
            // a run that is already unwinding (harmless), or does not find it at all. Closing first would leave a
            // window where the registry hands out a coordinator that rejects new registrars.
            deregisterRun(task.getId(), coordinator);
            coordinator.close();
        }
    }

    /**
     * Interrupts every run of {@code taskId} currently in progress <b>on this instance</b>.
     *
     * <p>
     * Cooperative, in both senses. The call returns as soon as the signal is tripped rather than waiting for the run
     * to unwind, so a run may still be in progress for a moment afterwards; and how quickly it unwinds is set by the
     * step it is in, which is the propagation ladder described on this class. What is guaranteed is that no
     * <em>further</em> step starts.
     *
     * @param taskId
     *            the task whose runs should be stopped (must not be null)
     * @param reason
     *            why (must not be null)
     * @return {@code true} if at least one in-flight run was signalled here, {@code false} if nothing of this task is
     *         running on this instance — including the case where it runs on another node
     * @throws NullPointerException
     *             if either argument is null
     */
    public boolean interrupt(ScheduledTaskId taskId, InterruptReason reason) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");

        final Set<InterruptCoordinator> runs = inFlightRuns.get(taskId);
        if (runs == null || runs.isEmpty()) {
            log.debug("No in-flight run of task '{}' on this instance; interrupt({}) is a no-op", taskId, reason);
            return false;
        }

        int signalled = 0;
        for (InterruptCoordinator coordinator : runs) {
            coordinator.requestInterrupt(reason);
            signalled++;
        }

        // The set can empty out between the check above and this loop, in which case nothing was signalled and this
        // is the same non-event as an unknown task id — say so at the same level rather than announcing zero runs.
        if (signalled == 0) {
            log.debug("The last run of task '{}' finished before interrupt({}) reached it", taskId, reason);
            return false;
        }

        log.info("Interrupted {} in-flight run(s) of task '{}' ({})", signalled, taskId, reason);
        return true;
    }

    /**
     * Reports whether a run of {@code taskId} is in progress on this instance. Intended for tests and diagnostics —
     * do not gate control flow on it, since the answer can change between the read and the act.
     *
     * @param taskId
     *            the task id to check (must not be null)
     * @return {@code true} if at least one run of the task is in progress here
     */
    public boolean isRunning(ScheduledTaskId taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        final Set<InterruptCoordinator> runs = inFlightRuns.get(taskId);
        return runs != null && !runs.isEmpty();
    }

    private void registerRun(ScheduledTaskId taskId, InterruptCoordinator coordinator) {
        inFlightRuns.computeIfAbsent(taskId, id -> ConcurrentHashMap.newKeySet()).add(coordinator);
    }

    private void deregisterRun(ScheduledTaskId taskId, InterruptCoordinator coordinator) {
        inFlightRuns.computeIfPresent(taskId, (id, runs) -> {
            runs.remove(coordinator);
            // Drop the entry rather than leaving an empty set behind: the map is keyed by task id and a long-lived
            // engine would otherwise accumulate one entry per task ever fired.
            return runs.isEmpty() ? null : runs;
        });
    }

    private void interruptAll(InterruptReason reason) {
        for (Set<InterruptCoordinator> runs : inFlightRuns.values()) {
            for (InterruptCoordinator coordinator : runs) {
                coordinator.requestInterrupt(reason);
            }
        }
    }

    private RoutineResult runRoutine(ScheduledTask task, InterruptCoordinator coordinator) {
        final CancellationSignal signal = coordinator.getSignal();
        final Instant startedAt = clock.instant();
        final List<StepResult> stepResults = new ArrayList<>();
        final Map<String, String> stepOutputs = new HashMap<>();

        log.info("Starting routine execution for task '{}'", task.getId());
        logDefinitionDrift(task);
        eventPublisher.publish(new TaskStartedEvent(task));

        final ToolContext context = buildToolContext(task, signal);

        for (int i = 0; i < task.getRoutine().size(); i++) {
            // Step boundary gate. Every step from here on is simply not started, which is why the run stops within
            // one step of the interrupt even when its tools ignore cancellation entirely. The per-step machinery
            // below only shortens that last step; this is what makes stopping correct rather than merely prompt.
            if (signal.isCancelled()) {
                return cancelled(task, stepResults, signal, startedAt);
            }

            final RoutineStep step = task.getRoutine().get(i);
            final Optional<StepResult> attempted = executeStep(task, step, i, context, stepOutputs, coordinator);
            if (attempted.isEmpty()) {
                // An interrupt arrived between the gate above and the step's own, so the step never started. Record
                // nothing for it: a run's step results are what it attempted, and counting a step it never began
                // would make getTotalStepCount() describe the routine's length rather than the run.
                return cancelled(task, stepResults, signal, startedAt);
            }

            final StepResult stepResult = attempted.get();
            stepResults.add(stepResult);

            if (stepResult.isSuccess()) {
                final int stepIndex = i;
                stepResult.getStdout().ifPresent(stdout -> stepOutputs.put(String.valueOf(stepIndex), stdout));
                eventPublisher.publish(new StepCompletedEvent(task, step, stepResult, i));
            } else {
                // An interrupt that lands inside a step arrives here as an ordinary step failure — a terminated tool
                // returns an error like any other. Reading the signal before the failure is what keeps a stopped run
                // from being filed as a broken one; the step's own error is preserved in stepResults regardless.
                if (signal.isCancelled()) {
                    return cancelled(task, stepResults, signal, startedAt);
                }

                eventPublisher.publish(new StepFailedEvent(task, step, stepResult, i));

                final Instant completedAt = clock.instant();
                final String errorMessage = stepResult.getErrorMessage().orElse("Step " + i + " failed");
                final RoutineResult result = RoutineResult.failure(task.getId(), stepResults, errorMessage, startedAt,
                        completedAt);

                log.error("Routine execution failed for task '{}' at step {}: {}", task.getId(), i, errorMessage);
                eventPublisher.publish(new TaskFailedEvent(task, result));

                return result;
            }
        }

        final Instant completedAt = clock.instant();
        final RoutineResult result = RoutineResult.success(task.getId(), stepResults, startedAt, completedAt);

        log.info("Routine execution completed successfully for task '{}'", task.getId());
        eventPublisher.publish(new TaskCompletedEvent(task, result));

        return result;
    }

    /**
     * Builds and announces the result of a run that was stopped before it finished.
     *
     * <p>
     * The reason comes from the signal rather than from the caller, so the record names whoever actually tripped it
     * first — a host shutdown that overtakes an owner's cancellation is reported as the shutdown it was.
     */
    private RoutineResult cancelled(ScheduledTask task, List<StepResult> stepResults, CancellationSignal signal,
            Instant startedAt) {
        final InterruptReason reason = signal.getReason().orElse(InterruptReason.TASK_CANCELLED);
        final Instant completedAt = clock.instant();
        final RoutineResult result = RoutineResult.cancelled(task.getId(), stepResults, reason, startedAt, completedAt);

        log.info("Routine execution for task '{}' cancelled ({}) after {}/{} step(s)", task.getId(), reason,
                stepResults.size(), task.getRoutine().size());
        eventPublisher.publish(new TaskInterruptedEvent(task, result, reason));

        return result;
    }

    /**
     * Reports whether the bound agent's definition still matches what it was when the task was scheduled.
     *
     * <p>
     * A cron task outlives the moment it was created, and the definition it fires against is whatever the registry
     * holds now &mdash; someone may have edited the agent's {@code agent.md} in between. That is allowed on purpose:
     * pinning the old definition would mean a task quietly running a prompt its owner has since rewritten, which is
     * the worse outcome. What is owed instead is a record, so that a surprising run can be explained afterwards
     * rather than guessed at.
     *
     * <p>
     * The comparison is skipped, silently, when the task carries no recorded version (created before the field
     * existed, or by a caller that did not supply one) or when the bound runtime is not registered. Neither is this
     * method's business to complain about &mdash; the second is reported with a real failure a few steps later, where
     * the run actually stops.
     */
    private void logDefinitionDrift(ScheduledTask task) {
        final Optional<AgentDefinitionVersion> scheduled = task.getAgentDefinitionVersion();
        if (scheduled.isEmpty()) {
            return;
        }

        final Optional<AgentRuntime> runtime = agentRuntimeRegistry.get(task.getBoundRuntimeId());
        if (runtime.isEmpty()) {
            return;
        }

        final AgentDefinitionVersion current = AgentDefinitionVersion.from(runtime.get().getAgent());
        if (current.equals(scheduled.get())) {
            log.debug("Task '{}' runs the agent definition it was scheduled against (version {})", task.getId(),
                    current);
        } else {
            log.warn(
                    "Agent definition for '{}' changed since task '{}' was scheduled: {} -> {}. "
                            + "The task runs the current definition — scheduling does not pin the old one.",
                    task.getBoundRuntimeId(), task.getId(), scheduled.get(), current);
        }
    }

    /**
     * Builds the {@link ToolContext} shared by every step of one routine run.
     *
     * <p>
     * Four entries are populated. The first two are taken from the task itself, so a cron re-fire long after the
     * originating session ended still carries them:
     *
     * <ul>
     * <li>{@link ToolContextKeys#AGENT_RUNTIME_ID} &mdash; the task's bound runtime. This is the same value the tools
     * are resolved from a few lines below, so a step that reads it can only ever see the runtime that is actually
     * running it. Without it the scheduling tools are unusable from a routine: {@code ScheduleTask} rejects the call
     * outright, and {@code Task} / {@code TaskList} / {@code TaskStop} / {@code AgentOutput} either throw or silently
     * fall back to an unscoped view.</li>
     * <li>{@link ToolContextKeys#PRINCIPAL} &mdash; the task's owner. Previously a routine step that created a nested
     * task recorded {@code Principal.system()} as its owner (the fallback in {@code ScheduleTaskTool}), erasing the
     * human the work was scheduled for.</li>
     * <li>{@link ToolContextKeys#EXECUTION_ID} &mdash; a fresh {@link ExecutionId} per fire, unlike the two above. It
     * is what a step should correlate its own logs and per-run state on: two fires of the same task, or two tasks
     * firing concurrently, share a runtime id and an owner but never an execution id.</li>
     * <li>{@link InterruptToolKeys#CANCELLATION_SIGNAL} &mdash; this run's cancellation signal, so a
     * {@link InterruptBehavior#COOPERATIVE} step can poll it and return early instead of running to completion in a
     * run that has already been stopped. Steps that ignore it are no worse off than before: they finish, and the
     * boundary gate stops the run at the next step.</li>
     * </ul>
     *
     * <p>
     * {@link ToolContextKeys#SESSION_ID} and {@link ToolContextKeys#INVOKING_SESSION_ID} remain deliberately
     * <b>not</b> set, and the execution id is what makes leaving them empty affordable. A scheduled run has no
     * session: nobody is waiting on it and there is no transcript to attribute it to. Minting a synthetic
     * {@code SessionId} would let per-session state (skill approvals among them) key on a value no user ever saw and
     * that changes on every fire &mdash; so a run that needs an identifier gets one that admits what it is.
     *
     * <p>
     * Publishing {@code AGENT_RUNTIME_ID} widens one thing on purpose, and it is not a no-op: a skill invoked from a
     * routine step now reaches {@code AgentApprovalStore}, which it could not do while the context was empty. The
     * remaining chain stays fail-closed &mdash; the session-scoped store still misses (no session id) and the
     * rule-based tail still defaults to {@code ASK}, which {@code SkillTool} treats as a denial. What changes is that
     * an explicit "always allow for this agent" grant now also covers this agent's unattended runs, which is precisely
     * what that grant is documented to mean: agent-wide, no TTL, not cleared by {@code /clear}. Narrow it with
     * {@code /revoke}, or approve per-session instead.
     */
    private static ToolContext buildToolContext(ScheduledTask task, CancellationSignal signal) {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, task.getBoundRuntimeId())
                .put(ToolContextKeys.PRINCIPAL, task.getOwner())
                .put(ToolContextKeys.EXECUTION_ID, ExecutionId.generate("routine:" + task.getId()))
                .put(InterruptToolKeys.CANCELLATION_SIGNAL, signal).build();
    }

    /**
     * Runs one step, retries included.
     *
     * @return the step's result, or empty if the run was cancelled before a single attempt was made — the caller
     *         records nothing for a step that never started
     */
    private Optional<StepResult> executeStep(ScheduledTask task, RoutineStep step, int stepIndex, ToolContext context,
            Map<String, String> stepOutputs, InterruptCoordinator coordinator) {
        final CancellationSignal signal = coordinator.getSignal();
        final Instant startedAt = clock.instant();
        int attemptCount = 0;
        String lastError = null;

        while (attemptCount <= step.getMaxRetries()) {
            // Retrying a cancelled run spends its whole backoff budget on work nobody is waiting for. The first pass
            // through this gate is normally a formality — the run's own boundary gate already checked — but not
            // always: an interrupt can arrive between the two, and every retry after the first reaches it for real.
            if (signal.isCancelled()) {
                break;
            }
            attemptCount++;

            try {
                log.debug("Executing step {} (tool: '{}') (attempt {}/{}) for task '{}'", stepIndex, step.getTool(),
                        attemptCount, step.getMaxRetries() + 1, task.getId());

                final ToolResult toolResult = executeWithTimeout(task.getBoundRuntimeId(), step, context, stepOutputs,
                        coordinator);

                if (toolResult.isSuccess()) {
                    final Instant completedAt = clock.instant();
                    return Optional.of(StepResult.success(stepIndex, step, toolResult.getContent(), attemptCount,
                            startedAt, completedAt));
                } else {
                    lastError = toolResult.getContent();
                    log.warn("Step {} attempt {} failed for task '{}': {}", stepIndex, attemptCount, task.getId(),
                            lastError);
                }
            } catch (TimeoutException e) {
                lastError = "Step timed out after " + step.getTimeout();
                log.warn("Step {} attempt {} timed out for task '{}'", stepIndex, attemptCount, task.getId());
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("Step {} attempt {} threw exception for task '{}': {}", stepIndex, attemptCount, task.getId(),
                        e.getMessage(), e);
            }

            if (attemptCount <= step.getMaxRetries() && !awaitRetryDelay(step.getRetryDelay(), coordinator)) {
                break;
            }
        }

        if (attemptCount == 0) {
            // Only reachable through the gate at the top of the loop on its first pass: the step was never begun.
            return Optional.empty();
        }

        final Instant completedAt = clock.instant();
        return Optional.of(StepResult.failure(stepIndex, step, lastError, attemptCount, startedAt, completedAt));
    }

    /**
     * Waits out a step's retry backoff, returning early if the run is cancelled meanwhile.
     *
     * <p>
     * A plain {@code Thread.sleep} would hold a cancelled run for the rest of the delay. With retry delays configured
     * in minutes that is the difference between a run that stops and one that stops eventually — and it is the
     * cheapest place in the routine to get that wrong, because nothing is executing to notice the signal.
     *
     * <p>
     * The latch is counted down by the signal's own listener, and registering on an already-tripped signal fires the
     * listener synchronously, so an interrupt that arrived before this call needs no separate branch.
     *
     * @return {@code true} if the delay elapsed and the step may be retried, {@code false} if the run was cancelled
     */
    private static boolean awaitRetryDelay(Duration retryDelay, InterruptCoordinator coordinator) {
        final CountDownLatch cancelled = new CountDownLatch(1);
        final CancellationSignal.Registration registration = coordinator.getSignal().onCancel(cancelled::countDown);
        try {
            return !cancelled.await(Math.max(0L, retryDelay.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Somebody interrupted the thread driving this routine without going through the coordinator. The catch
            // has already consumed the flag, so promote it into the run's signal rather than re-arming it: the run
            // then unwinds reporting CANCELLED, which carries the fact the flag no longer can, and no live interrupt
            // rides along into the next step's blocking calls (interrupt design §8.2).
            coordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED);
            return false;
        } finally {
            registration.remove();
        }
    }

    /**
     * Runs one step's tool on the timeout pool, honouring the tool's declared {@link InterruptBehavior}.
     *
     * <p>
     * Only tools that declare themselves terminable get a {@link TerminatorRegistrar}, which is the framework's
     * cooperative-first rule applied here: a tool that has not said it can be stopped mid-call is left to finish, and
     * the run's step-boundary gate is what stops the routine. For a
     * {@link InterruptBehavior#THREAD_INTERRUPT} step the terminator cancels the step's future — the tool runs on a
     * pool worker rather than on this thread, so cancelling the future is what "interrupt the tool thread" means
     * here. {@link InterruptBehavior#EXTERNALLY_TERMINATED} steps register their own handle through the registrar on
     * their {@link ToolContext}.
     */
    private ToolResult executeWithTimeout(AgentRuntimeId boundRuntimeId, RoutineStep step, ToolContext context,
            Map<String, String> stepOutputs, InterruptCoordinator coordinator) throws TimeoutException {
        // Resolve agent runtime at execution time and capture in local variable for thread safety
        final AgentRuntime agentRuntime = agentRuntimeRegistry.get(boundRuntimeId)
                .orElseThrow(() -> new IllegalStateException("No agent runtime registered for binding: "
                        + boundRuntimeId + ". Ensure the context is registered before task execution."));

        final Tool tool = agentRuntime.findToolByName(step.getTool())
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + step.getTool()));

        final ToolInput input = buildToolInput(step, stepOutputs);

        final InterruptBehavior behavior = tool.getInterruptBehavior();
        final boolean needsRegistrar = behavior == InterruptBehavior.THREAD_INTERRUPT
                || behavior == InterruptBehavior.EXTERNALLY_TERMINATED;
        final TerminatorRegistrar registrar = needsRegistrar ? coordinator.newTerminatorRegistrar() : null;

        try {
            final ToolContext stepContext = registrar == null
                    ? context
                    : ToolContext.builder().putAll(context.getContext())
                            .put(InterruptToolKeys.TERMINATOR_REGISTRAR, registrar).build();

            final Future<ToolResult> future = timeoutExecutor.submit(() -> tool.execute(input, stepContext));

            if (registrar != null && behavior == InterruptBehavior.THREAD_INTERRUPT) {
                // Registering after submit is safe by the registrar's contract: on an already-tripped coordinator the
                // terminator fires on this thread immediately, which cancels a future that has only just been queued.
                registrar.register(() -> future.cancel(true));
            }

            try {
                return future.get(step.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new TimeoutException("Tool execution timed out");
            } catch (CancellationException e) {
                // A terminator cancelled this step's future. Report it as a tool error rather than letting an
                // unchecked exception escape with no message: what the run reports is decided one level up, by the
                // gate that reads the signal, and it needs a step result to file either way.
                return ToolResult.error("Step interrupted: the routine run was cancelled");
            } catch (InterruptedException e) {
                // The catch consumed the flag; promote rather than re-arm, for the reason spelled out on
                // awaitRetryDelay. Cancel the future too — nothing is waiting on its result any more.
                future.cancel(true);
                coordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED);
                return ToolResult.error("Step interrupted: the thread driving this routine was interrupted");
            } catch (ExecutionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new RuntimeException("Tool execution failed", cause);
            }
        } finally {
            if (registrar != null) {
                registrar.close();
            }
        }
    }

    private ToolInput buildToolInput(RoutineStep step, Map<String, String> stepOutputs) {
        final String toolParamsJson = step.getToolParams();

        // Parse the JSON string into a Map first
        final Map<String, Object> toolParams;
        if (toolParamsJson.isEmpty()) {
            toolParams = new HashMap<>();
        } else {
            try {
                toolParams = new HashMap<>(objectMapper.readValue(toolParamsJson, MAP_TYPE_REF));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(
                        "Failed to parse tool_params JSON for step '" + step.getTool() + "': " + e.getMessage(), e);
            }
        }

        // Resolve template variables in the parsed params
        resolveTemplateParams(toolParams, stepOutputs);

        // Also inject step outputs for direct access if needed
        if (!stepOutputs.isEmpty()) {
            toolParams.put("_steps", Map.copyOf(stepOutputs));
        }

        return ToolInput.of(toolParams);
    }

    /**
     * Resolves template variables in all string values within a map.
     *
     * <p>
     * Recursively walks through the map and replaces $step.index.result with the actual output from the referenced step
     * in all string values, including those nested in maps and lists.
     * </p>
     *
     * @param params
     *            the map to resolve template variables in (modified in place)
     * @param stepOutputs
     *            map of step index (as string) to output values
     */
    @SuppressWarnings("unchecked")
    private void resolveTemplateParams(Map<String, Object> params, Map<String, String> stepOutputs) {
        if (stepOutputs.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            final Object value = entry.getValue();
            if (value instanceof String stringValue) {
                entry.setValue(resolveTemplateString(stringValue, stepOutputs));
            } else if (value instanceof Map) {
                resolveTemplateParams((Map<String, Object>) value, stepOutputs);
            } else if (value instanceof List) {
                resolveTemplateList((List<Object>) value, stepOutputs);
            }
        }
    }

    /**
     * Resolves template variables in all string values within a list.
     *
     * @param list
     *            the list to resolve template variables in (modified in place)
     * @param stepOutputs
     *            map of step index (as string) to output values
     */
    @SuppressWarnings("unchecked")
    private void resolveTemplateList(List<Object> list, Map<String, String> stepOutputs) {
        for (int i = 0; i < list.size(); i++) {
            final Object value = list.get(i);
            if (value instanceof String stringValue) {
                list.set(i, resolveTemplateString(stringValue, stepOutputs));
            } else if (value instanceof Map) {
                resolveTemplateParams((Map<String, Object>) value, stepOutputs);
            } else if (value instanceof List) {
                resolveTemplateList((List<Object>) value, stepOutputs);
            }
        }
    }

    /**
     * Resolves template variables in a string value.
     *
     * <p>
     * Replaces $step.index.result with the actual output from the referenced step, where index is a 0-based step index.
     * </p>
     *
     * @param value
     *            the string value to process
     * @param stepOutputs
     *            map of step index (as string) to output values
     * @return the resolved string
     */
    private String resolveTemplateString(String value, Map<String, String> stepOutputs) {
        if (stepOutputs.isEmpty() || !value.contains("$step.")) {
            return value;
        }

        final Matcher matcher = TEMPLATE_PATTERN.matcher(value);
        final StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            final String stepIndex = matcher.group(1);
            final String replacement = stepOutputs.getOrDefault(stepIndex, matcher.group(0));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Shuts down the executor.
     *
     * <p>
     * In-flight runs are signalled before the pool is asked to drain. Without that, the grace period below is spent
     * waiting for steps that have never been told to stop, and the shutdown reliably runs out the clock and falls
     * through to {@code shutdownNow()}; with it, cooperative and terminable steps unwind and the pool usually drains
     * at once.
     */
    public void shutdown() {
        interruptAll(InterruptReason.SYSTEM_SHUTDOWN);

        timeoutExecutor.shutdown();
        try {
            if (!timeoutExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                timeoutExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
