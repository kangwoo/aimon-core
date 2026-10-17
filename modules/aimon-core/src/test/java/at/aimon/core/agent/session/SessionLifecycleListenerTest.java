package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SessionLifecycleListener")
class SessionLifecycleListenerTest {

    private static final SessionDeletedEvent EVENT = SessionDeletedEvent.builder().sessionId(SessionId.of("gone"))
            .deletedAt(Instant.parse("2026-10-17T00:00:00Z")).build();

    @Test
    @DisplayName("whenDeleted(Consumer) builds a listener that hands each deletion to the lambda")
    void lambdaFactoryForwardsDeletions() {
        final List<SessionDeletedEvent> seen = new ArrayList<>();

        SessionLifecycleListener.whenDeleted(seen::add).onDeleted(EVENT);

        assertThat(seen).containsExactly(EVENT);
    }

    @Test
    @DisplayName("whenDeleted(Consumer) refuses a null lambda")
    void lambdaFactoryRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> SessionLifecycleListener.whenDeleted(null))
                .withMessageContaining("onDeleted");
    }

    @Test
    @DisplayName("the default onDeleted is a no-op")
    void defaultIsNoOp() {
        new SessionLifecycleListener() {
        }.onDeleted(EVENT);
    }
}
