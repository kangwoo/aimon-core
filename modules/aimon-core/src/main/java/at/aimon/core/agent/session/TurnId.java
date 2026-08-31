package at.aimon.core.agent.session;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object identifying a single <em>turn</em> — one user input processed to completion by one session.
 *
 * <p>
 * A {@link SessionId} identifies the durable session and a {@link LiveSession} is the node-local handle
 * that
 * runs turns against it; neither of them can say <em>which</em> turn a signal, event, or interrupt belongs to. That gap
 * is what this type closes. Two places need it:
 *
 * <ul>
 * <li><b>Event demultiplexing.</b> The cross-node event relay is created per turn, so every
 * {@code SignalKind.EVENT} payload it publishes can be stamped with the turn that produced it. Without the stamp a
 * subscriber that attached during turn 2 cannot tell turn 1's frames from its own.
 * <li><b>Turn-scoped interrupt.</b> {@code interrupt(sessionId, reason)} trips whatever turn happens to be running
 * when the signal lands. A caller that means "cancel the turn I submitted" has to name it, or a slow network hop can
 * cancel the turn <em>after</em> the intended one.
 * </ul>
 *
 * <p>
 * <b>Lifetime: turn.</b> Per {@code docs/overview/scope-model.md} a turn is an execution unit, not a scope that owns
 * components — a {@code TurnId} is issued when a turn starts and is meaningless once it settles. Do not persist it as
 * session state and do not reuse one across turns.
 *
 * <p>
 * <b>Not {@code PendingTurnId}.</b> {@code at.aimon.core.skill.policy.pending.PendingTurnId} names a turn that is
 * <em>suspended</em> awaiting user approval and is issued only if that suspension happens; it is user-visible and is
 * the
 * handle for {@code /approve}. A {@code TurnId} is issued for every turn at submit time regardless of outcome. The two
 * are unrelated identifiers for the same turn.
 *
 * <p>
 * Instances are immutable and safe to use as map keys.
 *
 * <pre>
 * {
 *     &#64;code
 *     TurnId turnId = TurnId.generate();
 *     manager.interrupt(sessionId, turnId, InterruptReason.USER_SIGINT);
 * }
 * </pre>
 */
public final class TurnId {

    private final String value;

    /**
     * Creates a new turn id with validation.
     *
     * @param value
     *            the turn identifier value (must not be null or blank)
     * @throws NullPointerException
     *             if value is null
     * @throws IllegalArgumentException
     *             if value is blank
     */
    public TurnId(String value) {
        Objects.requireNonNull(value, "Turn ID cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Turn ID cannot be blank");
        }
        this.value = value;
    }

    /**
     * Creates a {@code TurnId} from an existing identifier value — used when decoding a turn id that crossed a node
     * boundary (inbox envelope, signal payload).
     *
     * @param value
     *            the turn ID value (must not be null or blank)
     * @return a TurnId carrying the given value
     */
    public static TurnId of(String value) {
        return new TurnId(value);
    }

    /**
     * Issues a fresh random turn id. Called once per submitted turn by whoever starts it.
     *
     * @return a new TurnId with a random UUID value
     */
    public static TurnId generate() {
        return new TurnId(UUID.randomUUID().toString());
    }

    /**
     * Returns the turn ID value.
     *
     * @return the identifier value (never null or blank)
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TurnId that = (TurnId) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
