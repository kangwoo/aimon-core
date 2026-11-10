package at.aimon.session.routing.admission;

import java.util.Optional;

import at.aimon.core.agent.session.SessionId;

/** The default {@link TurnAdmission}: admits everything with a no-op permit. */
final class AlwaysAdmit implements TurnAdmission {

    static final TurnAdmissionPermit NOOP_PERMIT = () -> {
    };

    static final AlwaysAdmit INSTANCE = new AlwaysAdmit();

    private static final Optional<TurnAdmissionPermit> ADMITTED = Optional.of(NOOP_PERMIT);

    private AlwaysAdmit() {
    }

    @Override
    public Optional<TurnAdmissionPermit> tryAdmit(SessionId sessionId, Path path) {
        return ADMITTED;
    }
}
