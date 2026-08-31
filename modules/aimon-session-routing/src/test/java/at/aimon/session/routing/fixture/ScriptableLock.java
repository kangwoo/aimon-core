package at.aimon.session.routing.fixture;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.LeaseHolder;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionLeaseStore;

/**
 * Test-only {@link SessionLeaseStore} that delegates to a real backend but lets the test count {@code extend}
 * calls
 * and flip them to start failing on demand.
 *
 * <p>
 * It wraps the <em>backend</em> rather than the {@code SessionStore} above it deliberately: renewal failure has to
 * be
 * injected at the shared authority, which is where a real takeover would be observed. Faking it one layer up would let
 * the
 * store keep believing it holds a lease it lost, which is the opposite of what these tests are checking.
 */
public final class ScriptableLock implements SessionLeaseStore {

    private final SessionLeaseStore delegate;
    private final AtomicInteger extendCalls = new AtomicInteger();
    private final AtomicBoolean failExtends = new AtomicBoolean(false);
    private final AtomicReference<String> lastExtendThreadName = new AtomicReference<>();
    private final ConcurrentMap<SessionId, AtomicInteger> extendCallsBySession = new ConcurrentHashMap<>();
    private final AtomicReference<SessionId> blockedSession = new AtomicReference<>();
    private final CountDownLatch blockedExtendEntered = new CountDownLatch(1);
    private final CountDownLatch blockedExtendReleased = new CountDownLatch(1);

    public ScriptableLock(SessionLeaseStore delegate) {
        this.delegate = delegate;
    }

    public int extendCalls() {
        return extendCalls.get();
    }

    /**
     * How many times {@code extend} has been called for one session, as opposed to for all of them.
     *
     * <p>
     * The aggregate counter cannot answer whether a <em>particular</em> session is still being renewed while another
     * one's renewal is stuck, which is the only question a head-of-line test has.
     *
     * @param id
     *            the session
     * @return the count, zero if that session has never been renewed
     */
    public int extendCallsFor(SessionId id) {
        final AtomicInteger counter = extendCallsBySession.get(id);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Makes the next {@code extend} for {@code id} park until {@link #releaseBlockedExtends()}, standing in for a
     * lease backend that has stopped answering for one session — a stalled connection, a lock row another transaction
     * is sitting on.
     *
     * <p>
     * Blocking rather than failing is the point: a refused renewal is a decision the store acts on and moves past,
     * whereas a renewal that never returns holds the thread that made it, which is what a shared renewal pool has to
     * survive.
     *
     * @param id
     *            the session whose renewal should hang
     */
    public void blockExtendsFor(SessionId id) {
        blockedSession.set(id);
    }

    /** Waits until a renewal has actually entered the block, so a test never asserts on a hang that has not started. */
    public boolean awaitExtendBlocked() throws InterruptedException {
        return blockedExtendEntered.await(5, TimeUnit.SECONDS);
    }

    /** Lets the parked renewal — and any that queued behind it — return. */
    public void releaseBlockedExtends() {
        blockedSession.set(null);
        blockedExtendReleased.countDown();
    }

    /**
     * Name of the thread the most recent {@code extend} ran on, or {@code null} before the first one.
     *
     * <p>
     * The renewal tick is the one periodic task in the manager for which lateness is indistinguishable from a stolen
     * lease, so it runs on a scheduler of its own. That is a structural claim about wiring, and the thread name is the
     * only place it is observable from outside.
     *
     * @return the thread name, or {@code null}
     */
    public String lastExtendThreadName() {
        return lastExtendThreadName.get();
    }

    public void startFailingExtends() {
        failExtends.set(true);
    }

    @Override
    public Optional<SessionLease> tryAcquire(SessionId id, String holderId, Duration lease) {
        return delegate.tryAcquire(id, holderId, lease);
    }

    @Override
    public Optional<LeaseHolder> findHolder(SessionId id) {
        return delegate.findHolder(id);
    }

    @Override
    public boolean extend(SessionLease lease, Duration duration) {
        lastExtendThreadName.set(Thread.currentThread().getName());
        extendCalls.incrementAndGet();
        extendCallsBySession.computeIfAbsent(lease.getSessionId(), k -> new AtomicInteger()).incrementAndGet();
        if (lease.getSessionId().equals(blockedSession.get())) {
            blockedExtendEntered.countDown();
            try {
                blockedExtendReleased.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (failExtends.get()) {
            return false;
        }
        return delegate.extend(lease, duration);
    }

    @Override
    public void release(SessionLease lease) {
        delegate.release(lease);
    }
}
