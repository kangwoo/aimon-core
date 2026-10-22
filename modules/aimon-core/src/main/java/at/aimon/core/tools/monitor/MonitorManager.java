package at.aimon.core.tools.monitor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.prompt.SystemReminderFormatter;
import at.aimon.core.agent.queue.MessageQueueListener;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.tools.BackgroundTaskOwner;
import at.aimon.core.tools.monitor.exception.MonitorRequestException;

/**
 * Runs background monitors: starts their sources, applies the limits every source shares, and delivers their events
 * to the session that started them.
 *
 * <p>
 * <b>Sources produce, the manager decides.</b> A {@link MonitorSource} only reports events and how it ended. Batching,
 * the rate limit, the timeout, the caps and delivery are all here, so a source registered by the host lives under the
 * same rules as the built-in ones (design {@code docs/design/tool/monitor.md} §3).
 *
 * <p>
 * <b>Delivery.</b> Events go to the stack's {@link MessageQueueManager} as {@link QueuedInputPriority#NEXT} entries
 * addressed to the owner's session ({@link QueuedInput#getSessionId()}): a running turn reads them at its next
 * tool-using iteration, an idle session at the start of its next turn. A monitor keeps <em>at most one</em> batch
 * waiting in the queue (its end notice may follow it): events that arrive while its batch is still waiting are buffered
 * here and sent as one entry once that one has been drained — so a session that is away for a while comes back to one
 * message per monitor, not hundreds (§6.3). The
 * buffer is bounded; when it overflows the oldest events are dropped and the next message says how many.
 *
 * <p>
 * <b>Endings.</b> A monitor ends when its source ends, when its timeout passes, when it exceeds the rate limit, when it
 * is stopped, when its session is deleted, or when the manager closes. The first of these wins and the rest are
 * ignored. The first four are told to the model exactly once, after the last buffered events; a stop is not, since the
 * model asked for it, and neither are a deleted session or a shutdown, which leave no turn to read it.
 *
 * <p>
 * <b>Lifetime.</b> Application-scoped, like {@link at.aimon.core.tools.bash.BackgroundBashManager}: a monitor outlives
 * the eviction of the runtime that started it. Only {@link #close()} stops every monitor. The metadata goes to a
 * {@link MonitorStore}, which can be shared between nodes; the sources themselves stay on this node.
 *
 * <p>
 * Thread-safe. Timers run on one daemon thread of this manager.
 */
public final class MonitorManager implements AutoCloseable {

    /** Metadata key naming what a queued entry is; the value is {@link #QUEUED_KIND}. */
    public static final String QUEUED_KIND_KEY = "kind";

    /** Metadata value of {@link #QUEUED_KIND_KEY} on every entry a monitor queues. */
    public static final String QUEUED_KIND = "monitor-event";

    /** Metadata key carrying the monitor id on every entry a monitor queues. */
    public static final String QUEUED_MONITOR_ID_KEY = "monitorId";

    /** How long a monitor runs when the request names no timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** The longest a monitor may run. */
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);

    /** The shortest timeout a request may ask for. */
    public static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(MonitorManager.class);

    private static final String ID_PREFIX = "mon_";
    private static final int MAX_ID_ATTEMPTS = 16;
    private static final Pattern KIND = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    private static final long CLOSE_WAIT_SECONDS = 5;

    /** How a monitor ended. */
    private enum Ending {
        COMPLETED(true), FAILED(true), TIMEOUT(true), SUPPRESSED(true), STOPPED(false), SHUTDOWN(
                false), SESSION_DELETED(false);

        private final boolean told;

        Ending(boolean told) {
            this.told = told;
        }
    }

    /** What {@link #stop(BackgroundTaskOwner, String)} found. */
    public enum StopOutcome {
        /** The monitor was running here and is now stopped. */
        STOPPED,
        /** No monitor with this id belongs to the caller. */
        NOT_FOUND,
        /** The caller's monitor runs on another node and cannot be stopped from here. */
        RUNNING_ELSEWHERE
    }

    private final MessageQueueManager queue;
    private final MonitorStore store;
    private final String nodeId;
    private final Map<String, MonitorSourceFactory> sources;
    private final Duration batchWindow;
    private final int maxLineChars;
    private final int maxBufferedEvents;
    private final int maxBufferedChars;
    private final int maxEventsPerMinute;
    private final int maxPerOwner;
    private final int maxRunning;
    private final Duration defaultTimeout;
    private final Duration maxTimeout;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService stopper;
    private final List<AutoCloseable> ownedSources;
    private final Map<String, Running> monitors = new ConcurrentHashMap<>();
    private final Object startLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final MessageQueueListener queueListener = this::onQueueEvent;

    private MonitorManager(Builder builder, Map<String, MonitorSourceFactory> sources,
            List<AutoCloseable> ownedSources) {
        this.queue = builder.queue;
        this.store = builder.store != null ? builder.store : new InMemoryMonitorStore();
        this.nodeId = builder.nodeId != null ? builder.nodeId : "node-" + UUID.randomUUID();
        this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
        this.ownedSources = List.copyOf(ownedSources);
        this.batchWindow = builder.batchWindow;
        this.maxLineChars = builder.maxLineChars;
        this.maxBufferedEvents = builder.maxBufferedEvents;
        this.maxBufferedChars = builder.maxBufferedChars;
        this.maxEventsPerMinute = builder.maxEventsPerMinute;
        this.maxPerOwner = builder.maxPerOwner;
        this.maxRunning = builder.maxRunning;
        this.maxTimeout = builder.maxTimeout;
        this.defaultTimeout = builder.defaultTimeout.compareTo(maxTimeout) > 0 ? maxTimeout : builder.defaultTimeout;
        this.clock = builder.clock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "monitor-timers");
            t.setDaemon(true);
            return t;
        });
        // Stopping a source can take a while (a process tree's SIGTERM grace, a consumer's close), and it must not
        // hold up the timer thread every other monitor's batches and timeouts run on.
        this.stopper = Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "monitor-stop");
            t.setDaemon(true);
            return t;
        });
        queue.addListener(queueListener);
    }

    /**
     * Creates a builder over the queue the monitors deliver to — the stack's one {@link MessageQueueManager}.
     *
     * @param queue
     *            the queue (must not be null)
     * @return a new builder with the {@code shell} and {@code websocket} sources registered
     */
    public static Builder builder(MessageQueueManager queue) {
        return new Builder(queue);
    }

    /**
     * @return the registered source factories, in registration order (unmodifiable)
     */
    public List<MonitorSourceFactory> sources() {
        return List.copyOf(sources.values());
    }

    /**
     * @return the timeout a request without one gets
     */
    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    /**
     * @return the longest timeout a request may have; longer ones are cut to it
     */
    public Duration getMaxTimeout() {
        return maxTimeout;
    }

    /**
     * Starts a monitor.
     *
     * @param request
     *            what to watch and for whom (must not be null)
     * @return the started monitor's record
     * @throws MonitorRequestException
     *             if the monitor cannot be started — the owner has no session, the kind is unknown, a cap is reached,
     *             the source refused or failed to start, the store could not record it, or this manager is closed. The
     *             message is written for the model
     */
    public MonitorRecord start(MonitorRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ensureOpen();
        final BackgroundTaskOwner owner = request.getOwner();
        final String sessionId = owner.getSessionId()
                .orElseThrow(() -> new MonitorRequestException("A monitor needs a session to deliver its events to,"
                        + " and this execution acts for none (a scheduled routine or a detached fork) — it would end"
                        + " before the first event arrived."))
                .value();
        final AgentRuntimeId runtimeId = owner.getRuntimeId().orElseThrow(
                () -> new MonitorRequestException("A monitor can only be started from an agent's execution."));
        final MonitorSourceFactory factory = sources.get(request.getKind());
        if (factory == null) {
            throw new MonitorRequestException("Unknown monitor source '" + request.getKind() + "'. Available: "
                    + String.join(", ", sources.keySet()) + '.');
        }
        final Duration timeout = clampTimeout(request.getTimeout().orElse(defaultTimeout));
        // The factory sees the timeout the monitor will actually have, so a source with a limit of its own (the
        // shell's) sets it past this one and the manager's timer is what ends the monitor.
        final MonitorRequest effective = request.withTimeout(timeout);

        final MonitorSource source;
        try {
            source = Objects.requireNonNull(factory.create(effective), "factory returned no source");
        } catch (MonitorRequestException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Monitor source '{}' could not be created", request.getKind(), e);
            throw new MonitorRequestException(
                    "The " + request.getKind() + " source could not be created: " + e.getMessage(), e);
        }

        final Running running;
        synchronized (startLock) {
            ensureOpen();
            if (monitors.size() >= maxRunning) {
                throw new MonitorRequestException(
                        "This node already runs its limit of " + maxRunning + " monitors. Try again later.");
            }
            final long mine = monitors.values().stream().filter(m -> m.record.getOwner().equals(owner)).count();
            if (mine >= maxPerOwner) {
                throw new MonitorRequestException("You already run " + mine + " monitors, the limit. Stop one with"
                        + " MonitorStop before starting another.");
            }
            final Instant now = clock.instant();
            final MonitorRecord record = recordNew(
                    MonitorRecord.builder().owner(owner).nodeId(nodeId).kind(request.getKind())
                            .description(request.getDescription()).startedAt(now).deadline(now.plus(timeout)));
            running = new Running(record, runtimeId, sessionId, timeout);
            monitors.put(record.getMonitorId(), running);
        }
        running.begin(source);
        log.debug("Started monitor {} ({}) for {}", running.record.getMonitorId(), request.getKind(), owner);
        return running.record;
    }

    /**
     * Stops a monitor of the caller's. The model is not told: it asked for this.
     *
     * @param owner
     *            the caller (must not be null) — only the owner the monitor was started for finds it
     * @param monitorId
     *            the monitor id (must not be null)
     * @return what was found
     */
    public StopOutcome stop(BackgroundTaskOwner owner, String monitorId) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(monitorId, "monitorId must not be null");
        final Running running = monitors.get(monitorId);
        if (running != null) {
            if (!running.record.getOwner().equals(owner)) {
                return StopOutcome.NOT_FOUND;
            }
            running.end(Ending.STOPPED, null);
            return StopOutcome.STOPPED;
        }
        final Optional<MonitorRecord> elsewhere;
        try {
            elsewhere = store.find(monitorId);
        } catch (RuntimeException e) {
            log.warn("Monitor store lookup failed for {}: {}", monitorId, e.toString());
            return StopOutcome.NOT_FOUND;
        }
        // A record past its deadline is a node that died without removing it: nothing runs there any more.
        final Instant now = clock.instant();
        return elsewhere.filter(
                r -> r.getOwner().equals(owner) && !r.getNodeId().equals(nodeId) && r.getDeadline().isAfter(now))
                .isPresent() ? StopOutcome.RUNNING_ELSEWHERE : StopOutcome.NOT_FOUND;
    }

    /**
     * Stops every monitor of a session, without telling anyone — the session is gone. Wired to the session router's
     * deletion event by the stack; it reaches only the monitors of this node, the node that deleted the session.
     *
     * @param sessionId
     *            the deleted session (must not be null)
     * @return how many monitors were stopped
     */
    public int stopSession(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        int stopped = 0;
        for (Running running : List.copyOf(monitors.values())) {
            if (running.record.getOwner().getSessionId().filter(sessionId::equals).isPresent()) {
                running.end(Ending.SESSION_DELETED, null);
                stopped++;
            }
        }
        if (stopped > 0) {
            log.debug("Stopped {} monitor(s) of deleted session {}", stopped, sessionId);
        }
        return stopped;
    }

    /**
     * Lists the caller's monitors running on this node.
     *
     * @param owner
     *            the caller (must not be null)
     * @return their records, oldest first
     */
    public List<MonitorRecord> running(BackgroundTaskOwner owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        final List<MonitorRecord> result = new ArrayList<>();
        for (Running running : monitors.values()) {
            if (running.record.getOwner().equals(owner)) {
                result.add(running.record);
            }
        }
        result.sort((a, b) -> a.getStartedAt().compareTo(b.getStartedAt()));
        return result;
    }

    /**
     * Stops every monitor without telling the model, and stops listening to the queue. Waits up to
     * {@value #CLOSE_WAIT_SECONDS} seconds for the sources to stop. Idempotent.
     */
    @Override
    public void close() {
        final List<Running> toEnd;
        // Under the start lock, so a monitor is either registered before this snapshot (and ended here) or refused
        // by start's own check of the flag.
        synchronized (startLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            toEnd = List.copyOf(monitors.values());
        }
        queue.removeListener(queueListener);
        for (Running running : toEnd) {
            running.end(Ending.SHUTDOWN, null);
        }
        scheduler.shutdownNow();
        stopper.shutdown();
        try {
            if (!stopper.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Monitor sources were still stopping after {}s", CLOSE_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (AutoCloseable owned : ownedSources) {
            try {
                owned.close();
            } catch (Exception e) {
                log.warn("Monitor source factory did not close cleanly: {}", e.toString());
            }
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new MonitorRequestException("The monitor service is shut down.");
        }
    }

    private Duration clampTimeout(Duration requested) {
        if (requested.compareTo(MIN_TIMEOUT) < 0) {
            return MIN_TIMEOUT;
        }
        return requested.compareTo(maxTimeout) > 0 ? maxTimeout : requested;
    }

    private MonitorRecord recordNew(MonitorRecord.Builder template) {
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            // 32 random bits are enough: ids are found only by their owner, so it is the owner check, not the id's
            // secrecy, that keeps one session out of another's monitors.
            final String id = ID_PREFIX + String.format("%08x", ThreadLocalRandom.current().nextInt());
            if (monitors.containsKey(id)) {
                continue;
            }
            final MonitorRecord record = template.monitorId(id).build();
            final boolean stored;
            try {
                stored = store.putIfAbsent(record);
            } catch (RuntimeException e) {
                log.warn("Monitor store could not record a new monitor", e);
                throw new MonitorRequestException("The monitor could not be recorded: " + e.getMessage(), e);
            }
            if (stored) {
                return record;
            }
        }
        throw new MonitorRequestException("No free monitor id after " + MAX_ID_ATTEMPTS + " attempts.");
    }

    private void onQueueEvent(MessageQueueListener.Event event) {
        final MessageQueueListener.ChangeType type = event.getChangeType();
        if (type != MessageQueueListener.ChangeType.DRAINED && type != MessageQueueListener.ChangeType.REMOVED) {
            return;
        }
        final Map<String, String> metadata = event.getInput().getMetadata();
        if (!QUEUED_KIND.equals(metadata.get(QUEUED_KIND_KEY))) {
            return;
        }
        final String monitorId = metadata.get(QUEUED_MONITOR_ID_KEY);
        final Running running = monitorId != null ? monitors.get(monitorId) : null;
        if (running != null) {
            running.onDrained(event.getInput().getUuid());
        }
    }

    private void removeRecordQuietly(String monitorId) {
        try {
            store.remove(monitorId);
        } catch (RuntimeException e) {
            log.warn("Monitor store could not remove {}: {}", monitorId, e.toString());
        }
    }

    private void stopAsync(MonitorHandle handle) {
        try {
            stopper.execute(() -> stopQuietly(handle));
        } catch (RejectedExecutionException e) {
            stopQuietly(handle);
        }
    }

    private static void stopQuietly(MonitorHandle handle) {
        try {
            handle.stop();
        } catch (RuntimeException e) {
            log.warn("Monitor source did not stop cleanly: {}", e.toString());
        }
    }

    static String describe(Duration duration) {
        final long seconds = duration.toSeconds();
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /**
     * One running monitor: its buffer, its waiting queue entry, its timers. Every transition happens under its own
     * monitor lock and {@code ended} is absorbing, so a source's completion, the timeout and a stop racing each other
     * end it once. Nothing external is called with the lock held: the source's handle is stopped on the manager's
     * stopper threads, and an entry is queued after the lock is released — with {@code pendingEntry} already set, so a
     * queue listener that drains it synchronously is seen.
     */
    private final class Running implements MonitorEventSink {

        private final MonitorRecord record;
        private final AgentRuntimeId runtimeId;
        private final String sessionId;
        private final Duration timeout;
        private final Deque<String> buffer = new ArrayDeque<>();
        private int bufferedChars;
        private long dropped;
        private long received;
        private Instant windowStart;
        private int windowCount;
        private UUID pendingEntry;
        private MonitorHandle handle;
        private ScheduledFuture<?> timeoutTask;
        private ScheduledFuture<?> flushTask;
        private boolean ended;

        Running(MonitorRecord record, AgentRuntimeId runtimeId, String sessionId, Duration timeout) {
            this.record = record;
            this.runtimeId = runtimeId;
            this.sessionId = sessionId;
            this.timeout = timeout;
            this.windowStart = record.getStartedAt();
        }

        void begin(MonitorSource source) {
            boolean scheduled;
            synchronized (this) {
                try {
                    timeoutTask = scheduler.schedule(() -> end(Ending.TIMEOUT, null), timeout.toMillis(),
                            TimeUnit.MILLISECONDS);
                    scheduled = !ended;
                } catch (RejectedExecutionException e) {
                    scheduled = false;
                }
            }
            if (!scheduled) {
                // close() got here first: it has ended this monitor, or is about to.
                end(Ending.SHUTDOWN, null);
                throw new MonitorRequestException("The monitor service is shut down.");
            }
            final MonitorHandle started;
            try {
                started = Objects.requireNonNull(source.start(this), "source returned no handle");
            } catch (RuntimeException e) {
                end(Ending.STOPPED, null);
                log.warn("Monitor source '{}' did not start", record.getKind(), e);
                throw new MonitorRequestException(
                        "The " + record.getKind() + " source did not start: " + e.getMessage(), e);
            }
            final boolean alreadyEnded;
            synchronized (this) {
                handle = started;
                alreadyEnded = ended;
            }
            if (alreadyEnded) {
                stopAsync(started);
            }
        }

        @Override
        public void emit(String event) {
            if (event == null) {
                return;
            }
            synchronized (this) {
                if (ended) {
                    return;
                }
                final Instant now = clock.instant();
                if (!now.isBefore(windowStart.plus(RATE_WINDOW))) {
                    windowStart = now;
                    windowCount = 0;
                }
                if (++windowCount <= maxEventsPerMinute) {
                    received++;
                    buffer(event);
                    scheduleFlush();
                    return;
                }
            }
            end(Ending.SUPPRESSED, null);
        }

        @Override
        public void complete(String detail) {
            end(Ending.COMPLETED, detail);
        }

        @Override
        public void fail(String reason) {
            end(Ending.FAILED, reason);
        }

        void end(Ending ending, String detail) {
            final MonitorHandle toStop;
            QueuedInput last = null;
            synchronized (this) {
                if (ended) {
                    return;
                }
                ended = true;
                cancel(timeoutTask);
                cancel(flushTask);
                toStop = handle;
                if (ending.told) {
                    final String batch = batchText();
                    final String notice = endLine(ending, detail);
                    last = entry(batch == null ? notice : batch + "\n\n" + notice);
                }
                buffer.clear();
            }
            if (last != null) {
                enqueue(last);
            }
            monitors.remove(record.getMonitorId(), this);
            removeRecordQuietly(record.getMonitorId());
            if (ending == Ending.SESSION_DELETED) {
                discardQueued();
            }
            if (toStop != null) {
                stopAsync(toStop);
            }
            log.debug("Monitor {} ended: {}", record.getMonitorId(), ending);
        }

        synchronized void onDrained(UUID entry) {
            if (entry.equals(pendingEntry)) {
                pendingEntry = null;
                if (!ended && !buffer.isEmpty()) {
                    scheduleFlush();
                }
            }
        }

        private void flush() {
            final QueuedInput next;
            synchronized (this) {
                if (ended || pendingEntry != null) {
                    return;
                }
                final String batch = batchText();
                if (batch == null) {
                    return;
                }
                next = entry(batch);
                pendingEntry = next.getUuid();
                buffer.clear();
                bufferedChars = 0;
                dropped = 0;
            }
            if (!enqueue(next)) {
                synchronized (this) {
                    if (next.getUuid().equals(pendingEntry)) {
                        pendingEntry = null;
                    }
                }
            }
        }

        /** A deleted session will never read what this monitor queued for it; take it back out. */
        private void discardQueued() {
            try {
                queue.drainForInjection(
                        q -> QUEUED_KIND.equals(q.getMetadata().get(QUEUED_KIND_KEY))
                                && record.getMonitorId().equals(q.getMetadata().get(QUEUED_MONITOR_ID_KEY)),
                        QueuedInputPriority.LATER);
            } catch (RuntimeException e) {
                log.warn("Monitor {} could not discard its queued events: {}", record.getMonitorId(), e.toString());
            }
        }

        private void buffer(String event) {
            final String line = event.length() > maxLineChars ? event.substring(0, maxLineChars) + "…" : event;
            while (!buffer.isEmpty()
                    && (buffer.size() >= maxBufferedEvents || bufferedChars + line.length() > maxBufferedChars)) {
                bufferedChars -= buffer.removeFirst().length();
                dropped++;
            }
            buffer.addLast(line);
            bufferedChars += line.length();
        }

        private void scheduleFlush() {
            if (flushTask == null || flushTask.isDone()) {
                try {
                    flushTask = scheduler.schedule(this::flush, batchWindow.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    // The manager is closing; the monitor is about to be ended with it.
                    log.debug("Monitor {} flush not scheduled: manager closing", record.getMonitorId());
                }
            }
        }

        private String batchText() {
            if (buffer.isEmpty() && dropped == 0) {
                return null;
            }
            final StringBuilder text = new StringBuilder(header()).append(" — ").append(plural(buffer.size(), "event"))
                    .append(':');
            if (dropped > 0) {
                text.append("\n(").append(plural(dropped, "earlier event"))
                        .append(" dropped: more arrived than could be held before this message was read)");
            }
            for (String line : buffer) {
                text.append('\n').append(line);
            }
            return text.toString();
        }

        private String endLine(Ending ending, String detail) {
            final String what = switch (ending) {
                case COMPLETED -> "the source ended" + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
                case FAILED ->
                    "the source failed: " + (detail == null || detail.isBlank() ? "no reason given" : detail);
                case TIMEOUT -> "timed out after " + describe(timeout);
                case SUPPRESSED -> "stopped — more than " + maxEventsPerMinute
                        + " events in a minute. Narrow its filter and start it again";
                case STOPPED, SHUTDOWN, SESSION_DELETED -> "stopped";
            };
            return header() + " ended: " + what + ". " + plural(received, "event") + " in total.";
        }

        private String header() {
            return "Monitor " + record.getMonitorId() + " (" + record.getDescription() + ")";
        }

        private QueuedInput entry(String text) {
            return QueuedInput.builder().inputText(SystemReminderFormatter.sanitizeBody(text))
                    .priority(QueuedInputPriority.NEXT).agentRuntimeId(runtimeId).sessionId(sessionId)
                    .metadata(Map.of(QUEUED_KIND_KEY, QUEUED_KIND, QUEUED_MONITOR_ID_KEY, record.getMonitorId()))
                    .build();
        }

        /** Queues an entry; never called with this monitor's lock held. */
        private boolean enqueue(QueuedInput entry) {
            try {
                queue.enqueue(entry);
                return true;
            } catch (RuntimeException e) {
                log.warn("Monitor {} could not queue its events; they are lost: {}", record.getMonitorId(),
                        e.toString());
                return false;
            }
        }

        private void cancel(ScheduledFuture<?> task) {
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    /** Builder for {@link MonitorManager}. */
    public static final class Builder {

        private final MessageQueueManager queue;
        private final Map<String, MonitorSourceFactory> sources = new LinkedHashMap<>();
        private boolean replaceDefaults;
        private final Set<String> excluded = new HashSet<>();
        private MonitorStore store;
        private String nodeId;
        private Duration batchWindow = Duration.ofSeconds(1);
        private int maxLineChars = 2_000;
        private int maxBufferedEvents = 200;
        private int maxBufferedChars = 16_000;
        private int maxEventsPerMinute = 600;
        private int maxPerOwner = 8;
        private int maxRunning = 64;
        private Duration defaultTimeout = DEFAULT_TIMEOUT;
        private Duration maxTimeout = MAX_TIMEOUT;
        private Clock clock = Clock.systemUTC();

        private Builder(MessageQueueManager queue) {
            this.queue = Objects.requireNonNull(queue, "queue must not be null");
        }

        /**
         * Registers a source factory, replacing one of the same kind — a built-in one included.
         *
         * @param factory
         *            the factory (must not be null)
         * @return this builder
         * @throws IllegalArgumentException
         *             if the factory's kind is not lower case letters, digits and {@code -}
         */
        public Builder source(MonitorSourceFactory factory) {
            Objects.requireNonNull(factory, "factory must not be null");
            final String kind = Objects.requireNonNull(factory.kind(), "kind must not be null");
            if (!KIND.matcher(kind).matches()) {
                throw new IllegalArgumentException("Invalid monitor source kind: '" + kind + "'");
            }
            sources.put(kind, factory);
            return this;
        }

        /**
         * Replaces every registered factory, the default {@code shell} and {@code websocket} included, with
         * {@code factories}.
         *
         * @param factories
         *            the factories (must not be null; may be empty, which leaves no kind to start)
         * @return this builder
         */
        public Builder sources(List<? extends MonitorSourceFactory> factories) {
            Objects.requireNonNull(factories, "factories must not be null");
            replaceDefaults = true;
            sources.clear();
            factories.forEach(this::source);
            return this;
        }

        /**
         * Leaves a kind out — a built-in one, typically: an assembly without {@code Bash} excludes {@code shell}, so a
         * monitor cannot run the commands the deployment turned off. Wins over {@link #source(MonitorSourceFactory)}.
         *
         * @param kind
         *            the kind to leave out (must not be null)
         * @return this builder
         */
        public Builder excludeSource(String kind) {
            excluded.add(Objects.requireNonNull(kind, "kind must not be null"));
            return this;
        }

        /**
         * @param store
         *            where monitor metadata is recorded, or null for an in-memory store
         * @return this builder
         */
        public Builder store(MonitorStore store) {
            this.store = store;
            return this;
        }

        /**
         * @param nodeId
         *            this node's id in the store, or null for a random one
         * @return this builder
         */
        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /**
         * @param batchWindow
         *            how long events are collected before they are queued as one message (default 1s; must be
         *            positive)
         * @return this builder
         */
        public Builder batchWindow(Duration batchWindow) {
            this.batchWindow = positive(batchWindow, "batchWindow");
            return this;
        }

        /**
         * @param maxLineChars
         *            how much of one event is kept (default 2,000 characters)
         * @return this builder
         */
        public Builder maxLineChars(int maxLineChars) {
            this.maxLineChars = positive(maxLineChars, "maxLineChars");
            return this;
        }

        /**
         * @param maxBufferedEvents
         *            how many events wait for one message at most (default 200); older ones are dropped
         * @return this builder
         */
        public Builder maxBufferedEvents(int maxBufferedEvents) {
            this.maxBufferedEvents = positive(maxBufferedEvents, "maxBufferedEvents");
            return this;
        }

        /**
         * @param maxBufferedChars
         *            how many characters wait for one message at most (default 16,000); older events are dropped
         * @return this builder
         */
        public Builder maxBufferedChars(int maxBufferedChars) {
            this.maxBufferedChars = positive(maxBufferedChars, "maxBufferedChars");
            return this;
        }

        /**
         * @param maxEventsPerMinute
         *            events per minute beyond which a monitor is stopped as unfiltered (default 600)
         * @return this builder
         */
        public Builder maxEventsPerMinute(int maxEventsPerMinute) {
            this.maxEventsPerMinute = positive(maxEventsPerMinute, "maxEventsPerMinute");
            return this;
        }

        /**
         * @param maxPerOwner
         *            monitors one owner may run at once (default 8)
         * @return this builder
         */
        public Builder maxPerOwner(int maxPerOwner) {
            this.maxPerOwner = positive(maxPerOwner, "maxPerOwner");
            return this;
        }

        /**
         * @param maxRunning
         *            monitors this node runs at once (default 64)
         * @return this builder
         */
        public Builder maxRunning(int maxRunning) {
            this.maxRunning = positive(maxRunning, "maxRunning");
            return this;
        }

        /**
         * @param defaultTimeout
         *            the timeout of a request that names none (default 5 minutes; cut to the maximum)
         * @return this builder
         */
        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = positive(defaultTimeout, "defaultTimeout");
            return this;
        }

        /**
         * @param maxTimeout
         *            the longest a monitor may run (default 30 minutes)
         * @return this builder
         */
        public Builder maxTimeout(Duration maxTimeout) {
            this.maxTimeout = positive(maxTimeout, "maxTimeout");
            return this;
        }

        /**
         * @param clock
         *            the clock for start times and the rate window (must not be null)
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            return this;
        }

        /**
         * Builds the manager. The built-in {@code shell} and {@code websocket} sources are created here — only those
         * not replaced by a source of the same kind, and none after {@link #sources(List)} — and closed with the
         * manager.
         *
         * @return the manager, listening to the queue
         */
        public MonitorManager build() {
            final Map<String, MonitorSourceFactory> resolved = new LinkedHashMap<>();
            final List<AutoCloseable> owned = new ArrayList<>();
            if (!replaceDefaults) {
                if (!sources.containsKey(ShellMonitorSourceFactory.KIND)
                        && !excluded.contains(ShellMonitorSourceFactory.KIND)) {
                    resolved.put(ShellMonitorSourceFactory.KIND, new ShellMonitorSourceFactory());
                }
                if (!sources.containsKey(WebSocketMonitorSourceFactory.KIND)
                        && !excluded.contains(WebSocketMonitorSourceFactory.KIND)) {
                    final WebSocketMonitorSourceFactory webSocket = new WebSocketMonitorSourceFactory();
                    resolved.put(WebSocketMonitorSourceFactory.KIND, webSocket);
                    owned.add(webSocket);
                }
            }
            resolved.putAll(sources);
            resolved.keySet().removeAll(excluded);
            return new MonitorManager(this, resolved, owned);
        }

        private static Duration positive(Duration value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
