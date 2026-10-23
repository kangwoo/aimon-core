package at.aimon.cli.repl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.prompt.SystemReminderFormatter;
import at.aimon.core.agent.queue.MessageQueueListener;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.stream.SubagentTaskCompleted;
import at.aimon.session.routing.wake.IdleSessionWaker;
import at.aimon.session.routing.wake.WakePolicy;

/**
 * The REPL's half of waking an idle session (idle-wake design §8): decides <em>when</em> the session may be woken for
 * its background notifications, and hands the REPL thread what to run. It never runs a turn itself — the REPL does, on
 * its own thread, so a woken turn renders, asks for approvals and answers Ctrl+C exactly like a typed one.
 *
 * <p>
 * The same {@link WakePolicy} as the router's {@link IdleSessionWaker}: which kinds wake, a debounce, an hourly cap
 * and a cap on wakes in a row without a prompt of the user's. One session on one node, so the counters live here.
 *
 * <p>
 * Thread model: queue events arrive on whichever thread enqueued; after the debounce {@code nudge} runs on this class's
 * timer thread and asks the REPL to come back from its prompt; {@link #take()} and {@link #userTurn()} run on the REPL
 * thread. The counters are guarded by this object.
 */
final class ReplWaker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReplWaker.class);

    private static final String KIND_KEY = "kind";
    private static final Duration HOUR = Duration.ofHours(1);

    /** What the REPL runs for one wake: the input, and the attributes its turn carries. */
    static final class Wake {
        private final String input;
        private final String reason;
        private final String taskId;

        private Wake(String input, String reason, String taskId) {
            this.input = input;
            this.reason = reason;
            this.taskId = taskId;
        }

        /** @return the turn's input: each notification as a {@code background-wake} system reminder */
        String getInput() {
            return input;
        }

        /** @return the kinds that woke the session, comma-separated */
        String getReason() {
            return reason;
        }

        /** @return the background task a completion notification names, if one was among them */
        Optional<String> getTaskId() {
            return Optional.ofNullable(taskId);
        }
    }

    private final MessageQueueManager queue;
    private final AgentRuntimeId runtimeId;
    private final SessionId sessionId;
    private final WakePolicy policy;
    private final Clock clock;
    private final Runnable nudge;
    private final MessageQueueListener listener = this::onQueueEvent;
    private final ScheduledExecutorService timer;

    private final Deque<Instant> recentWakes = new ArrayDeque<>();
    private int consecutive;
    private boolean reported;
    private ScheduledFuture<?> pending;

    /**
     * @param queue
     *            the REPL's message queue
     * @param runtimeId
     *            the runtime whose entries this session reads
     * @param sessionId
     *            the REPL's session
     * @param policy
     *            what wakes and how often
     * @param clock
     *            the clock the caps count with
     * @param nudge
     *            asks the REPL thread to come back from its prompt and call {@link #take()}; run on the timer thread
     */
    ReplWaker(MessageQueueManager queue, AgentRuntimeId runtimeId, SessionId sessionId, WakePolicy policy, Clock clock,
            Runnable nudge) {
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nudge = Objects.requireNonNull(nudge, "nudge must not be null");
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "aimon-repl-waker");
            t.setDaemon(true);
            return t;
        });
        queue.addListener(listener);
    }

    /** @return the policy this waker applies */
    WakePolicy getPolicy() {
        return policy;
    }

    private void onQueueEvent(MessageQueueListener.Event event) {
        if (event.getChangeType() == MessageQueueListener.ChangeType.ENQUEUED && wakes(event.getInput())) {
            schedule(policy.getDebounce());
        }
    }

    /**
     * Asks again after {@code delay}, unless a nudge is already waiting. The REPL calls this when it could not come
     * back yet — the user has started typing.
     *
     * @param delay
     *            how long from now
     */
    synchronized void schedule(Duration delay) {
        if (pending != null && !pending.isDone()) {
            return;
        }
        try {
            pending = timer.schedule(this::nudgeQuietly, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closed) {
            pending = null;
        }
    }

    private void nudgeQuietly() {
        // No longer pending once it runs: a nudge that finds a cap holding must be able to ask again for later.
        synchronized (this) {
            pending = null;
        }
        try {
            nudge.run();
        } catch (RuntimeException e) {
            log.warn("Could not ask the REPL to wake for its background notifications: {}", e.toString());
        }
    }

    /**
     * Whether {@link #take()} would wake now — something is waiting and no cap holds — without taking anything. The
     * REPL asks before it gives up its prompt, so a cap does not cost the user a redrawn prompt for every event. Under
     * the hourly cap it also asks to be nudged again when the oldest wake leaves the hour.
     *
     * @return true when a wake is due
     */
    synchronized boolean isDue() {
        final Instant now = clock.instant();
        pruneOlderThanAnHour(now);
        if (queue.snapshot().stream().noneMatch(this::wakes) || consecutive >= policy.getMaxConsecutive()) {
            return false;
        }
        if (recentWakes.size() >= policy.getMaxPerHour()) {
            schedule(Duration.between(now, recentWakes.peekFirst().plus(HOUR)));
            return false;
        }
        return true;
    }

    private void pruneOlderThanAnHour(Instant now) {
        while (!recentWakes.isEmpty() && !recentWakes.peekFirst().isAfter(now.minus(HOUR))) {
            recentWakes.pollFirst();
        }
    }

    /**
     * Takes this session's waiting notifications, when it may be woken for them now. Run on the REPL thread, at its
     * prompt.
     *
     * @return the wake to run, or empty when nothing is waiting or a cap holds
     */
    synchronized Optional<Wake> take() {
        final Instant now = clock.instant();
        pruneOlderThanAnHour(now);
        if (queue.snapshot().stream().noneMatch(this::wakes)) {
            return Optional.empty();
        }
        if (consecutive >= policy.getMaxConsecutive()) {
            if (!reported) {
                reported = true;
                log.info("Reached the limit of {} wakes without a prompt of yours; background notifications wait for"
                        + " your next turn", policy.getMaxConsecutive());
            }
            return Optional.empty();
        }
        if (recentWakes.size() >= policy.getMaxPerHour()) {
            if (!reported) {
                reported = true;
                log.info("Reached the limit of {} wakes an hour", policy.getMaxPerHour());
            }
            // A waiting monitor enqueues nothing new, so no event would come to look again.
            schedule(Duration.between(now, recentWakes.peekFirst().plus(HOUR)));
            return Optional.empty();
        }
        final List<QueuedInput> drained = queue.drainForInjection(this::wakes, QueuedInputPriority.LATER);
        if (drained.isEmpty()) {
            return Optional.empty();
        }
        recentWakes.addLast(now);
        consecutive++;
        return Optional.of(toWake(drained));
    }

    /** A prompt of the user's ran: the streak of wakes is over. Run on the REPL thread. */
    synchronized void userTurn() {
        consecutive = 0;
        reported = false;
    }

    private boolean wakes(QueuedInput entry) {
        final Predicate<QueuedInput> mine = q -> runtimeId.equals(q.getAgentRuntimeId())
                && q.getSessionId().filter(sessionId.value()::equals).isPresent();
        return mine.test(entry) && policy.getKinds().contains(entry.getMetadata().get(KIND_KEY));
    }

    private static Wake toWake(List<QueuedInput> drained) {
        final Set<String> kinds = new LinkedHashSet<>();
        final StringBuilder input = new StringBuilder();
        String taskId = null;
        for (QueuedInput entry : drained) {
            kinds.add(entry.getMetadata().get(KIND_KEY));
            if (taskId == null) {
                taskId = SubagentTaskCompleted.notifiedTaskId(entry.getMetadata()).orElse(null);
            }
            if (input.length() > 0) {
                input.append("\n\n");
            }
            input.append(SystemReminderFormatter.wrap(IdleSessionWaker.WAKE_REMINDER_KEY,
                    SystemReminderFormatter.sanitizeBody(entry.getInputText())));
        }
        return new Wake(input.toString(), String.join(",", kinds), taskId);
    }

    @Override
    public void close() {
        queue.removeListener(listener);
        timer.shutdownNow();
    }
}
