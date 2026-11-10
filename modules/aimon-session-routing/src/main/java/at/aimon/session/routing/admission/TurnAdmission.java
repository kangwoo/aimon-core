package at.aimon.session.routing.admission;

import java.util.Optional;

import at.aimon.core.agent.session.SessionId;

/**
 * Node-local gate the router consults immediately before it would start work for a session on <em>this</em> node
 * (routing.md §8.4).
 *
 * <p>
 * <b>What it is asked, and when.</b> Every path that starts turns here goes through one of two entry points, and both
 * ask this gate first: a {@link at.aimon.session.routing.SessionRouter#submit submit} that would run locally
 * ({@link Path#SUBMIT} — a user's turn, an idle-wake, a cold open of an evicted session), and an inbox drain pass
 * opened by a doorbell or a forward's re-ring ({@link Path#INBOX}). The question is asked <b>before</b> the inbox is
 * collected and before a lease is claimed, never after: a deferral therefore never removes, acknowledges or loses a
 * queued message.
 *
 * <p>
 * <b>What a permit covers.</b> One <em>pass</em>: the serial run of turns a node executes for one session while it
 * holds the turn gate — the submitted turn, plus whatever queued messages that pass collects from the inbox before it
 * ends. A pass runs one turn at a time, so {@code N} outstanding permits bound this node to at most {@code N}
 * concurrently executing turns. The router calls {@link TurnAdmissionPermit#release()} exactly once per granted permit:
 * when the pass ends, when it fails, and when it is abandoned before any turn starts (the lease went to a peer, the
 * agent binding conflicted, draining began in between).
 *
 * <p>
 * <b>What a deferral does.</b> Nothing is run here and nothing is lost. A deferred submit is delivered to the inbox
 * (the caller gets a {@code FORWARDED} disposition) and this node hands the session over if it holds it; a deferred
 * drain pass leaves the inbox untouched and hands the session over. A peer with capacity collects it on the doorbell
 * this node re-rings; when no peer does, the forward's polling fallback re-rings it and this gate is asked again, so a
 * deferral is back-pressure, not a refusal.
 *
 * <p>
 * <b>Contract for implementations.</b> {@link #tryAdmit} is called on submit threads and turn threads and must not
 * block. Throwing is treated as a deferral (logged), so a broken gate degrades to "this node runs nothing" rather than
 * to lost work. Implementations must be thread-safe.
 *
 * <p>
 * Configured through {@link at.aimon.session.routing.builder.SessionRouterBuilder#turnAdmission(TurnAdmission)}; the
 * default {@link #alwaysAdmit()} admits everything, which is the behaviour the router had before this SPI existed.
 */
@FunctionalInterface
public interface TurnAdmission {

    /** Which entry point is asking. */
    enum Path {
        /** A submit that would run its turn on this node. */
        SUBMIT,
        /** A drain pass that would collect the session's inbox on this node. */
        INBOX
    }

    /**
     * Decides whether this node may start a pass for {@code sessionId} now.
     *
     * @param sessionId
     *            the session the pass would run for (never null)
     * @param path
     *            which entry point is asking (never null)
     * @return a permit to run, released by the router exactly once when the pass ends; empty to defer
     */
    Optional<TurnAdmissionPermit> tryAdmit(SessionId sessionId, Path path);

    /**
     * The default gate: admits everything, with a permit whose release does nothing.
     *
     * @return a gate that never defers
     */
    static TurnAdmission alwaysAdmit() {
        return AlwaysAdmit.INSTANCE;
    }
}
