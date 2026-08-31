package at.aimon.session.routing;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.OpenAttributes;
import at.aimon.core.agent.session.SessionId;
import at.aimon.session.routing.metrics.SessionMetrics;
import at.aimon.session.routing.metrics.SessionMetrics.CacheEvictionReason;

/**
 * Per-node, per-session handle cache backed by Caffeine.
 *
 * <p>
 * Applies two simultaneous caps per design §5.2:
 *
 * <ul>
 * <li>{@code idleTtl} — idle-after-access TTL ensures heavy session-scoped resources (MCP connections, knowledge
 * stores) do not linger indefinitely. A session with a turn running on it is not idle, however long that turn takes;
 * see <b>Pinning</b> below.
 * <li>{@code maxEntries} — LRU bound prevents one-off session bursts from exhausting heap.
 * </ul>
 *
 * <p>
 * Caffeine has no background sweeper; TTL eviction is lazy. Callers must invoke {@link #sweep()} periodically (the
 * manager runs a {@code ScheduledExecutorService} to do this) to ensure idle entries' close listeners fire promptly.
 *
 * <p>
 * The cache is thread-safe, and close is single-shot however many sweepers, {@link #evict(SessionId)} calls and
 * {@link #closeAll()} calls race.
 *
 * <p>
 * <b>Pinning.</b> Eviction and turn execution are otherwise independent: idle-TTL and LRU eviction both close the
 * session from a sweeper thread with no knowledge of whether a turn is running on it, and closing a session mid-turn
 * corrupts that session's history. (The {@code releaseSession} path already guards against this by
 * interrupting and waiting before it evicts; the sweeper paths had no such guard.) A caller that is about to run work
 * on a session therefore takes a pin via {@link #acquire}, and releases it with {@link SessionEntry#unpin()} when the
 * work is done. A pin does two things, and the difference between them is the difference between the two caps:
 *
 * <ul>
 * <li><b>Idle-TTL: a pin prevents the eviction.</b> Expiry is variable rather than fixed-after-access, and a pinned
 * entry's expiry is pushed out of reach until the last pin drops (at which point the full {@code idleTtl} starts from
 * that moment). Deferring the close would not be enough here: an expired entry leaves the map immediately, so the next
 * submission for the same session would miss the cache and open a <em>second</em> handle while the first is still
 * running its turn. Idle means idle — a running turn is proof this session is not.
 * <li><b>LRU: a pin only defers the close.</b> A pinned entry over the size cap is still removed from the map, because
 * the cap is a heap bound and a lie about it is worse than a late close; the {@code close()} waits for the last pin.
 * The double-handle window that remains is closed one level up, by the manager releasing the session lease inside
 * the same turn gate that unpins.
 * </ul>
 *
 * <p>
 * <b>Close notification.</b> Since Stage 3b the session lease is held for as long as the handle lives, so every
 * path that ends a session is a path that owes the cluster a lease. Rather than have each of those paths — idle-TTL
 * eviction, LRU eviction, an explicit release, a signal, shutdown — remember to hand it back, they all funnel through
 * {@link SessionEntry#doClose()}, which notifies the listener given to
 * {@link #LiveSessionCache(LiveSessionOpener, Duration, int, Ticker, SessionMetrics, Consumer)}. A Caffeine
 * {@code removalListener} would not do: an entry evicted while pinned is removed from the map long before its session
 * actually closes, and returning the lease at removal time would leave the still-running turn writing history it no
 * longer holds.
 */
public final class LiveSessionCache {

    private static final Logger log = LoggerFactory.getLogger(LiveSessionCache.class);

    /** Bound on {@link #acquire} retries; each retry needs a concurrent eviction, so one is already unlikely. */
    private static final int MAX_ACQUIRE_ATTEMPTS = 8;

    /**
     * Expiry of a pinned entry. Caffeine treats {@code Long.MAX_VALUE} nanos as "no expiration"; the point is that no
     * sweep can expire the entry before the pin is released and the real {@code idleTtl} is reinstated.
     */
    private static final long PINNED_EXPIRY_NANOS = Long.MAX_VALUE;

    private final Cache<SessionId, SessionEntry> entries;
    private final Policy.VarExpiration<SessionId, SessionEntry> expiry;
    private final long idleTtlNanos;
    private final LiveSessionOpener opener;
    private final SessionMetrics metrics;
    private final Consumer<SessionId> sessionClosedListener;

    public LiveSessionCache(LiveSessionOpener opener, Duration idleTtl, int maxEntries) {
        this(opener, idleTtl, maxEntries, Ticker.systemTicker(), SessionMetrics.NOOP);
    }

    public LiveSessionCache(LiveSessionOpener opener, Duration idleTtl, int maxEntries, Ticker ticker) {
        this(opener, idleTtl, maxEntries, ticker, SessionMetrics.NOOP);
    }

    public LiveSessionCache(LiveSessionOpener opener, Duration idleTtl, int maxEntries, Ticker ticker,
            SessionMetrics metrics) {
        this(opener, idleTtl, maxEntries, ticker, metrics, id -> {
        });
    }

    /**
     * @param sessionClosedListener
     *            invoked once per entry, on the thread that performed the close, strictly after
     *            {@code LiveSession#close()} returns. Must not throw and must not block: for a deferred close it runs
     *            on the turn thread that released the last pin. Pass a no-op when the caller has nothing to reclaim.
     */
    public LiveSessionCache(LiveSessionOpener opener, Duration idleTtl, int maxEntries, Ticker ticker,
            SessionMetrics metrics, Consumer<SessionId> sessionClosedListener) {
        this.opener = Objects.requireNonNull(opener, "opener must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.sessionClosedListener = Objects.requireNonNull(sessionClosedListener,
                "sessionClosedListener must not be null");
        Objects.requireNonNull(idleTtl, "idleTtl must not be null");
        Objects.requireNonNull(ticker, "ticker must not be null");
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1: " + maxEntries);
        }
        this.idleTtlNanos = toNanosSaturating(idleTtl);
        this.entries = Caffeine.newBuilder().ticker(ticker).expireAfter(new PinAwareExpiry()).maximumSize(maxEntries)
                .executor(Runnable::run).<SessionId, SessionEntry>removalListener((id, entry, cause) -> {
                    if (entry != null) {
                        entry.closeQuietly();
                    }
                    safeRecordEviction(cause);
                }).build();
        this.expiry = entries.policy().expireVariably()
                .orElseThrow(() -> new IllegalStateException("variable expiration must be configured"));
    }

    private static long toNanosSaturating(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            // A TTL past year 2262 is indistinguishable from none, and misconfiguration must not fail the node.
            return PINNED_EXPIRY_NANOS;
        }
    }

    /**
     * Expiry that treats a pinned entry as never idle.
     *
     * <p>
     * The per-event callbacks matter as much as {@link #refreshExpiry}: any read of a pinned entry — a {@link #peek}
     * from the lease bookkeeping, an {@link #ensureOpen} hit — would otherwise recompute the expiry and undo the pin's
     * protection.
     */
    private final class PinAwareExpiry implements Expiry<SessionId, SessionEntry> {

        @Override
        public long expireAfterCreate(SessionId id, SessionEntry entry, long currentTime) {
            return expiryNanosFor(entry);
        }

        @Override
        public long expireAfterUpdate(SessionId id, SessionEntry entry, long currentTime, long currentDuration) {
            return expiryNanosFor(entry);
        }

        @Override
        public long expireAfterRead(SessionId id, SessionEntry entry, long currentTime, long currentDuration) {
            return expiryNanosFor(entry);
        }
    }

    private long expiryNanosFor(SessionEntry entry) {
        return entry != null && entry.isPinned() ? PINNED_EXPIRY_NANOS : idleTtlNanos;
    }

    /**
     * Re-evaluates {@code id}'s expiry after its pin count changed.
     *
     * <p>
     * Needed at both ends. A pin is taken <em>after</em> {@link #ensureOpen} returns, so the read that produced the
     * entry saw it unpinned and left the ordinary TTL on it. And a release has to reinstate that TTL explicitly:
     * nothing else would, since an entry left at {@link #PINNED_EXPIRY_NANOS} that no one reads again never expires —
     * exactly the leak the TTL exists to prevent.
     *
     * @param id
     *            the session whose entry changed pin state
     */
    private void refreshExpiry(SessionId id) {
        final SessionEntry entry = entries.getIfPresent(id);
        if (entry == null) {
            // Already evicted. It is out of the map, so there is no expiry to set; the pin now only governs when the
            // deferred close runs.
            return;
        }
        expiry.setExpiresAfter(id, expiryNanosFor(entry), TimeUnit.NANOSECONDS);
    }

    private void safeRecordEviction(RemovalCause cause) {
        try {
            metrics.onCacheEviction(mapCause(cause));
        } catch (Exception e) {
            log.warn("SessionMetrics.onCacheEviction threw: {}", e.toString());
        }
    }

    private static CacheEvictionReason mapCause(RemovalCause cause) {
        if (cause == null) {
            return CacheEvictionReason.OTHER;
        }
        return switch (cause) {
            case EXPIRED -> CacheEvictionReason.IDLE;
            case SIZE -> CacheEvictionReason.LRU;
            case EXPLICIT -> CacheEvictionReason.EXPLICIT_RELEASE;
            default -> CacheEvictionReason.OTHER;
        };
    }

    /**
     * Convenience overload — equivalent to
     * {@link #ensureOpen(SessionId, AgentRuntimeId, LiveSessionOptions, OpenAttributes)} with
     * {@link OpenAttributes#empty()}. Tests and simple internal callers that do not surface caller-domain attributes
     * can use this form unchanged.
     *
     * @param id
     *            the session (must not be null)
     * @param agentRuntimeId
     *            the agent-scoped runtime id to bind on first open (must not be null)
     * @param options
     *            session options (must not be null)
     * @return the live session entry (never null)
     */
    public SessionEntry ensureOpen(SessionId id, AgentRuntimeId agentRuntimeId, LiveSessionOptions options) {
        return ensureOpen(id, agentRuntimeId, options, OpenAttributes.empty());
    }

    /**
     * Lazily opens a session for {@code id} via the injected factory, or returns the cached entry on hit.
     *
     * <p>
     * {@code openAttributes} are forwarded to the opener only on cache miss; subsequent {@code ensureOpen} calls for
     * the same {@code id} reuse the cached session and ignore any attributes supplied at hit time. This matches the
     * documented {@link OpenAttributes} contract.
     *
     * @param id
     *            the session (must not be null)
     * @param agentRuntimeId
     *            the agent-scoped runtime id to bind on first open (must not be null)
     * @param options
     *            session options (must not be null)
     * @param openAttributes
     *            caller-provided open attributes; pass {@link OpenAttributes#empty()} when none (must not be null)
     * @return the live session entry (never null)
     */
    public SessionEntry ensureOpen(SessionId id, AgentRuntimeId agentRuntimeId, LiveSessionOptions options,
            OpenAttributes openAttributes) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(openAttributes, "openAttributes must not be null");
        final AtomicBoolean miss = new AtomicBoolean(false);
        final SessionEntry entry = entries.get(id, k -> {
            miss.set(true);
            return new SessionEntry(k, opener.open(k, agentRuntimeId, options, openAttributes), sessionClosedListener,
                    this::refreshExpiry);
        });
        try {
            if (miss.get()) {
                metrics.onCacheMiss();
            } else {
                metrics.onCacheHit();
            }
        } catch (Exception e) {
            log.warn("SessionMetrics hit/miss callback threw: {}", e.toString());
        }
        return entry;
    }

    /**
     * Like {@link #ensureOpen(SessionId, AgentRuntimeId, LiveSessionOptions, OpenAttributes)}, but returns an
     * entry that is already <b>pinned</b>: it will not be closed underneath the caller, however long the caller holds
     * it, nor expire as idle while the caller holds it. The caller must call {@link SessionEntry#unpin()} exactly once,
     * from a {@code finally} — the idle TTL only resumes counting there.
     *
     * <p>
     * A cached entry can be evicted and closed between the map lookup and the pin, in which case this retries with a
     * freshly opened session. The retry terminates because an evicted entry is no longer in the map, so the next
     * attempt opens a new one.
     *
     * @param id
     *            the session (must not be null)
     * @param agentRuntimeId
     *            the agent-scoped runtime id to bind on first open (must not be null)
     * @param options
     *            session options (must not be null)
     * @param openAttributes
     *            caller-provided open attributes; pass {@link OpenAttributes#empty()} when none (must not be null)
     * @return a pinned live session entry (never null)
     */
    public SessionEntry acquire(SessionId id, AgentRuntimeId agentRuntimeId, LiveSessionOptions options,
            OpenAttributes openAttributes) {
        for (int attempt = 1;; attempt++) {
            final SessionEntry entry = ensureOpen(id, agentRuntimeId, options, openAttributes);
            if (entry.pin()) {
                refreshExpiry(id);
                return entry;
            }
            // Lost a race with an evictor. Drop the dead entry so the next ensureOpen cannot hand back the same one.
            entries.asMap().remove(id, entry);
            if (attempt >= MAX_ACQUIRE_ATTEMPTS) {
                throw new IllegalStateException(
                        "Could not pin a live session for " + id + " after " + attempt + " attempts");
            }
        }
    }

    /**
     * Returns the cached entry for {@code id}, if any. Does not refresh the access timestamp on miss.
     *
     * @param id
     *            the session (must not be null)
     * @return the cached entry, or empty
     */
    public Optional<SessionEntry> peek(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(entries.getIfPresent(id));
    }

    /**
     * Drop and close the entry for {@code id}, if any.
     *
     * @param id
     *            the session (must not be null)
     */
    public void evict(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        entries.invalidate(id);
    }

    /** Drop and close every cached entry. */
    public void closeAll() {
        entries.invalidateAll();
        entries.cleanUp();
    }

    /**
     * Apply {@code action} to every currently cached session. Used by the manager during graceful shutdown to trip
     * every active turn. The visitor must not throw.
     *
     * @param action
     *            the visitor (must not be null)
     */
    public void forEachSession(Consumer<LiveSession> action) {
        Objects.requireNonNull(action, "action must not be null");
        entries.asMap().values().forEach(entry -> action.accept(entry.getSession()));
    }

    /**
     * Force a synchronous cleanup pass — runs Caffeine's lazy maintenance, including TTL-based eviction. The manager
     * runs this from a periodic {@code ScheduledExecutorService} task.
     */
    public void sweep() {
        entries.cleanUp();
    }

    /**
     * Live entry held by the cache: exposes the {@link LiveSession}, ensures single close, and lets a caller
     * {@link #pin()} the session so eviction cannot close it out from under a running turn.
     *
     * <p>
     * The three flags are guarded by one monitor rather than atomics because the decision "who performs the close" has
     * to be made against a consistent view of all of them: a lock-free version has a window where an evictor sees a
     * non-zero pin count and defers, while the last {@link #unpin()} has already read {@code closeRequested} as false —
     * and the session is then never closed at all.
     *
     * <p>
     * The entry owns the <em>timing</em> of the close notification, which is why the listener lives here rather than on
     * the cache's removal listener: it fires after {@code session.close()} and, for a deferred close, only once that
     * close finally happens.
     */
    public static final class SessionEntry {
        private final SessionId sessionId;
        private final LiveSession session;
        private final Consumer<SessionId> onClosed;
        private final Consumer<SessionId> onLastPinReleased;
        private final Object gate = new Object();
        private int pins;
        private boolean closeRequested;
        private boolean closed;

        SessionEntry(SessionId sessionId, LiveSession session, Consumer<SessionId> onClosed,
                Consumer<SessionId> onLastPinReleased) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
            this.session = Objects.requireNonNull(session, "session must not be null");
            this.onClosed = Objects.requireNonNull(onClosed, "onClosed must not be null");
            this.onLastPinReleased = Objects.requireNonNull(onLastPinReleased, "onLastPinReleased must not be null");
        }

        public LiveSession getSession() {
            return session;
        }

        /**
         * Whether work is currently running on this session. Read by the cache's expiry policy, which is why it must
         * not be derived from anything but the pin count itself.
         *
         * @return {@code true} while at least one pin is outstanding
         */
        boolean isPinned() {
            synchronized (gate) {
                return pins > 0;
            }
        }

        /**
         * Reserves this session against close.
         *
         * @return {@code true} when the pin was taken; {@code false} when this entry is already closing or closed, in
         *         which case the caller must obtain a different entry
         */
        boolean pin() {
            synchronized (gate) {
                if (closed || closeRequested) {
                    return false;
                }
                pins++;
                return true;
            }
        }

        /**
         * Releases a pin taken by {@link LiveSessionCache#acquire}. Performs the deferred close when this was the last
         * pin on an entry that has since been evicted, and otherwise lets the idle TTL start counting again. Safe to
         * call once per successful acquire; extra calls are ignored.
         */
        public void unpin() {
            final boolean closeNow;
            final boolean idleNow;
            synchronized (gate) {
                if (pins > 0) {
                    pins--;
                }
                closeNow = closeRequested && pins == 0 && !closed;
                if (closeNow) {
                    closed = true;
                }
                idleNow = pins == 0 && !closeRequested;
            }
            if (closeNow) {
                doClose();
            } else if (idleNow) {
                // Outside the monitor: the cache reads this entry's pin state to recompute the expiry, and taking the
                // two locks in the other order from a future caller is how this becomes a deadlock.
                try {
                    onLastPinReleased.accept(sessionId);
                } catch (Exception e) {
                    log.warn("Expiry refresh threw for session {}: {}", sessionId, e.toString());
                }
            }
        }

        void closeQuietly() {
            final boolean closeNow;
            synchronized (gate) {
                if (closed) {
                    return;
                }
                closeRequested = true;
                closeNow = pins == 0;
                if (closeNow) {
                    closed = true;
                }
            }
            if (closeNow) {
                doClose();
            } else {
                log.debug("Session close deferred — a turn is still running on this session");
            }
        }

        private void doClose() {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("Session close threw: {}", e.toString());
            }
            // Strictly after the handle is closed. The listener's job is to hand the session lease back, and the
            // lease is what keeps another node from writing this session's history — returning it while this
            // session could still emit is the one ordering that corrupts rather than merely delays.
            try {
                onClosed.accept(sessionId);
            } catch (Exception e) {
                log.warn("Session-closed listener threw for session {}: {}", sessionId, e.toString());
            }
        }
    }
}
