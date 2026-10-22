package at.aimon.session.routing.wake;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.prompt.SystemReminderFormatter;
import at.aimon.core.agent.queue.MessageQueueListener;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.LiveSessionStatus;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.stream.SubagentTaskCompleted;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.ClusterSessionStatus;
import at.aimon.session.routing.SessionRouter;
import at.aimon.session.routing.SubmitDisposition;
import at.aimon.session.routing.SubmitRequest;

/**
 * Opens a turn for an idle session when a background notification addressed to it arrives — a monitor's events, by
 * default (design {@code docs/design/session/idle-wake.md}).
 *
 * <p>
 * <b>A client of the router.</b> The turn goes through {@link SessionRouter#submit(SubmitRequest)}, like a user's
 * request: the router reopens an evicted live session, serializes the turn with any other, forwards it to the node
 * holding the session, and relays its events. Its input is the notification itself — the waker drains the session's
 * matching queue entries and submits their text wrapped as {@code <system-reminder key="background-wake">} — so it
 * reaches the session wherever its turn runs. The turn runs as the session's owner, and counts against budgets and
 * quotas as any turn does.
 *
 * <p>
 * <b>Threads.</b> The queue calls {@link #onQueueEvent} on whatever thread enqueued; it only schedules. Checking,
 * draining and submitting happen on this waker's one scheduler thread, and a submitted turn's future is never waited
 * on.
 *
 * <p>
 * <b>Not waking.</b> A session without a record, a bound agent or an owner is not woken; nor one over its caps
 * ({@link WakePolicy}) — its notifications stay queued for the next turn; nor, as a cost optimisation, one this node
 * holds that is running a turn, which reads them itself. When a submit fails synchronously the drained entries go back
 * into the queue and the session is tried again after {@link WakePolicy#getRetryDelay()}.
 *
 * <p>
 * Application-scoped, like the router. Thread-safe.
 */
public final class IdleSessionWaker implements AutoCloseable {

    /** Execution attribute naming what woke a turn: the queue entry kinds, comma-separated. */
    public static final String WAKE_REASON_ATTRIBUTE = "aimon.wake.reason";

    /** The {@code <system-reminder>} key a wake turn's input is wrapped in. */
    public static final String WAKE_REMINDER_KEY = "background-wake";

    private static final Logger log = LoggerFactory.getLogger(IdleSessionWaker.class);

    private static final String KIND_KEY = "kind";
    private static final Duration HOUR = Duration.ofHours(1);

    private final SessionRouter router;
    private final MessageQueueManager queue;
    private final SessionRecordStore records;
    private final WakePolicy policy;
    private final WakeLedger ledger;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<SessionId, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final Map<SessionId, Instant> retryNotBefore = new ConcurrentHashMap<>();
    private final Set<SessionId> reported = ConcurrentHashMap.newKeySet();
    private final MessageQueueListener listener = this::onQueueEvent;
    private final AtomicBoolean closed = new AtomicBoolean();

    private IdleSessionWaker(Builder builder) {
        this.router = Objects.requireNonNull(builder.router, "router must not be null");
        this.queue = Objects.requireNonNull(builder.queue, "queue must not be null");
        this.records = Objects.requireNonNull(builder.records, "records must not be null");
        this.policy = builder.policy;
        this.ledger = builder.ledger != null ? builder.ledger : new InMemoryWakeLedger();
        this.clock = builder.clock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "idle-session-waker");
            t.setDaemon(true);
            return t;
        });
        queue.addListener(listener);
    }

    /**
     * Creates a builder.
     *
     * @param router
     *            the router turns are submitted to (must not be null)
     * @param queue
     *            the stack's message queue, listened to and drained (must not be null)
     * @param records
     *            the session record store, read for the bound agent, the owner and the turn count (must not be null)
     * @return a new builder with the default policy
     */
    public static Builder builder(SessionRouter router, MessageQueueManager queue, SessionRecordStore records) {
        return new Builder(router, queue, records);
    }

    /**
     * @return the policy this waker applies
     */
    public WakePolicy getPolicy() {
        return policy;
    }

    /**
     * Forgets a deleted session: its pending wake is cancelled and its counters dropped.
     *
     * @param sessionId
     *            the deleted session (must not be null)
     */
    public void forget(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final ScheduledFuture<?> scheduled = pending.remove(sessionId);
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        retryNotBefore.remove(sessionId);
        reported.remove(sessionId);
        try {
            ledger.remove(sessionId);
        } catch (RuntimeException e) {
            log.warn("Wake ledger could not forget {}: {}", sessionId, e.toString());
        }
    }

    /**
     * Stops listening to the queue and cancels every pending wake. Turns already submitted run on. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        queue.removeListener(listener);
        pending.values().forEach(scheduled -> scheduled.cancel(false));
        pending.clear();
        scheduler.shutdownNow();
    }

    private void onQueueEvent(MessageQueueListener.Event event) {
        if (event.getChangeType() != MessageQueueListener.ChangeType.ENQUEUED || closed.get()) {
            return;
        }
        final QueuedInput entry = event.getInput();
        if (entry.getSessionId().isEmpty() || !policy.getKinds().contains(entry.getMetadata().get(KIND_KEY))) {
            return;
        }
        final SessionId sessionId = SessionId.of(entry.getSessionId().get());
        final Instant notBefore = retryNotBefore.get(sessionId);
        final Duration untilRetry = notBefore == null ? Duration.ZERO : Duration.between(clock.instant(), notBefore);
        schedule(sessionId, untilRetry.compareTo(policy.getDebounce()) > 0 ? untilRetry : policy.getDebounce());
    }

    private void schedule(SessionId sessionId, Duration delay) {
        pending.compute(sessionId, (id, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            try {
                return scheduler.schedule(() -> wakeQuietly(id), Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                return null;
            }
        });
    }

    private void wakeQuietly(SessionId sessionId) {
        pending.remove(sessionId);
        try {
            wake(sessionId);
        } catch (RuntimeException e) {
            log.warn("Waking session {} failed", sessionId, e);
        }
    }

    private void wake(SessionId sessionId) {
        if (closed.get()) {
            return;
        }
        // Read before anything is drained, and right before the submit: a session deleted meanwhile (on any node) must
        // not be recreated by the claim.
        final Optional<SessionRecordView> record = records.load(sessionId);
        if (record.isEmpty() || record.get().getAgentRef().isEmpty()) {
            return;
        }
        final Optional<Principal> owner = records.loadOwner(sessionId);
        if (owner.isEmpty()) {
            if (reported.add(sessionId)) {
                log.warn(
                        "Session {} has no recorded owner, so its background notifications cannot wake it; they wait"
                                + " for its next turn (record an owner with SubmitRequest.owner on the first submit)",
                        sessionId);
            }
            return;
        }
        final Instant now = clock.instant();
        final WakeState state = refreshed(sessionId, record.get(), now);
        if (state.getRecentWakes().size() >= policy.getMaxPerHour()
                || state.getConsecutive() >= policy.getMaxConsecutive()) {
            ledger.put(sessionId, state);
            if (reported.add(sessionId)) {
                log.info("Session {} reached its wake limit ({}); its notifications wait for its next turn", sessionId,
                        policy);
            }
            return;
        }
        if (runningHere(sessionId)) {
            // Its running turn reads them; look again in a moment in case it ends without a tool-using iteration.
            schedule(sessionId, policy.getDebounce().isZero() ? Duration.ofSeconds(1) : policy.getDebounce());
            return;
        }
        final List<QueuedInput> drained = drain(sessionId);
        if (drained.isEmpty()) {
            return;
        }
        submit(sessionId, record.get().getAgentRef().get(), owner.get(), drained, state, now);
    }

    /** The session's counters, with wakes older than an hour dropped and the streak reset if another turn ran. */
    private WakeState refreshed(SessionId sessionId, SessionRecordView record, Instant now) {
        final WakeState stored = ledger.get(sessionId);
        final List<Instant> recent = new ArrayList<>();
        for (Instant at : stored.getRecentWakes()) {
            if (at.isAfter(now.minus(HOUR))) {
                recent.add(at);
            }
        }
        int consecutive = stored.getConsecutive();
        final int turnCount = record.getSessionTotals().getTurnCount();
        if (stored.getTurnCountAfterWake().isPresent() && turnCount > stored.getTurnCountAfterWake().getAsInt()) {
            consecutive = 0;
            reported.remove(sessionId);
        }
        return WakeState.of(recent, consecutive,
                stored.getTurnCountAfterWake().isPresent() ? stored.getTurnCountAfterWake().getAsInt() : null);
    }

    /** A cost optimisation, never a gate (the router's status is not one): a turn this node is running reads them. */
    private boolean runningHere(SessionId sessionId) {
        final ClusterSessionStatus status = router.status(sessionId);
        if (status.getSource() != ClusterSessionStatus.Source.LOCAL_HOLDER) {
            return false;
        }
        return status.getStatus().map(s -> s.getPhase() == LiveSessionStatus.Phase.RUNNING || s.isInterruptible())
                .orElse(false);
    }

    private List<QueuedInput> drain(SessionId sessionId) {
        final String id = sessionId.value();
        final List<QueuedInput> candidates = queue.snapshot().stream()
                .filter(q -> q.getSessionId().filter(id::equals).isPresent())
                .filter(q -> policy.getKinds().contains(q.getMetadata().get(KIND_KEY))).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        // One runtime per turn, as the executor's own drain: a session is bound to one agent.
        final AgentRuntimeId runtime = candidates.get(0).getAgentRuntimeId();
        return queue
                .drainForInjection(
                        q -> q.getSessionId().filter(id::equals).isPresent() && runtime.equals(q.getAgentRuntimeId())
                                && policy.getKinds().contains(q.getMetadata().get(KIND_KEY)),
                        QueuedInputPriority.LATER);
    }

    private void submit(SessionId sessionId, String agentRef, Principal owner, List<QueuedInput> drained,
            WakeState state, Instant now) {
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
            input.append(SystemReminderFormatter.wrap(WAKE_REMINDER_KEY,
                    SystemReminderFormatter.sanitizeBody(entry.getInputText())));
        }
        final SubmitOptions.Builder options = SubmitOptions.builder().principal(owner)
                .executionAttribute(WAKE_REASON_ATTRIBUTE, String.join(",", kinds));
        if (taskId != null) {
            options.executionAttribute(SubagentTaskCompleted.RECEIVED_TASK_ID_ATTRIBUTE, taskId);
        }
        final AgentRuntimeId runtime = drained.get(0).getAgentRuntimeId();
        final SubmitRequest request = SubmitRequest.builder().sessionId(sessionId).agentRef(agentRef)
                .contextDiscriminator(runtime.discriminator().orElse(null)).userInput(input.toString())
                .submitOptions(options.build()).idempotencyKey("wake:" + drained.get(0).getUuid())
                .initiator(Principal.system()).build();
        final SubmitDisposition disposition;
        try {
            disposition = router.submit(request);
        } catch (RuntimeException e) {
            // Set first: putting the entries back fires ENQUEUED, and that must schedule the retry, not a quick wake.
            retryNotBefore.put(sessionId, now.plus(policy.getRetryDelay()));
            requeue(drained);
            schedule(sessionId, policy.getRetryDelay());
            log.warn("Waking session {} was refused ({}); its notifications are back in the queue, retry in {}",
                    sessionId, e.toString(), policy.getRetryDelay());
            return;
        }
        retryNotBefore.remove(sessionId);
        final List<Instant> recent = new ArrayList<>(state.getRecentWakes());
        recent.add(now);
        ledger.put(sessionId, WakeState.of(recent, state.getConsecutive() + 1,
                state.getTurnCountAfterWake().isPresent() ? state.getTurnCountAfterWake().getAsInt() : null));
        log.debug("Woke session {} for {} ({})", sessionId, kinds, disposition.getKind());
        final CompletionStage<AgentExecutionResult> turn = disposition.getFuture();
        turn.whenComplete((result, error) -> settled(sessionId, error));
    }

    /** After a wake's turn: remember the turn count, so the next wake can tell whether another turn ran since. */
    private void settled(SessionId sessionId, Throwable error) {
        if (error != null) {
            log.warn("The wake turn of session {} did not complete: {}", sessionId, error.toString());
        }
        try {
            final Optional<SessionRecordView> record = records.load(sessionId);
            if (record.isEmpty()) {
                return;
            }
            final WakeState state = ledger.get(sessionId);
            ledger.put(sessionId, WakeState.of(state.getRecentWakes(), state.getConsecutive(),
                    record.get().getSessionTotals().getTurnCount()));
        } catch (RuntimeException e) {
            log.warn("Could not record the turn count after waking session {}: {}", sessionId, e.toString());
        }
    }

    /** Puts drained entries back as they were — same uuid, same metadata — after a refused submit. */
    private void requeue(List<QueuedInput> drained) {
        for (QueuedInput entry : drained) {
            try {
                queue.enqueue(QueuedInput.builder().uuid(entry.getUuid()).inputText(entry.getInputText())
                        .priority(entry.getPriority()).agentRuntimeId(entry.getAgentRuntimeId())
                        .sourceAgentId(entry.getSourceAgentId().orElse(null))
                        .sessionId(entry.getSessionId().orElse(null)).enqueuedAt(entry.getEnqueuedAt())
                        .metadata(entry.getMetadata()).submitOptions(entry.getSubmitOptions()).build());
            } catch (RuntimeException e) {
                log.warn("A drained notification could not be put back and is lost: {}", e.toString());
            }
        }
    }

    /** Builder for {@link IdleSessionWaker}. */
    public static final class Builder {

        private final SessionRouter router;
        private final MessageQueueManager queue;
        private final SessionRecordStore records;
        private WakePolicy policy = WakePolicy.defaults();
        private WakeLedger ledger;
        private Clock clock = Clock.systemUTC();

        private Builder(SessionRouter router, MessageQueueManager queue, SessionRecordStore records) {
            this.router = router;
            this.queue = queue;
            this.records = records;
        }

        /**
         * @param policy
         *            what to wake for and how often (must not be null)
         * @return this builder
         */
        public Builder policy(WakePolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy must not be null");
            return this;
        }

        /**
         * @param ledger
         *            where the per-session counters are kept, or null for an in-memory ledger
         * @return this builder
         */
        public Builder ledger(WakeLedger ledger) {
            this.ledger = ledger;
            return this;
        }

        /**
         * @param clock
         *            the clock for the caps' time window (must not be null)
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            return this;
        }

        /**
         * @return the waker, listening to the queue
         */
        public IdleSessionWaker build() {
            return new IdleSessionWaker(this);
        }
    }
}
