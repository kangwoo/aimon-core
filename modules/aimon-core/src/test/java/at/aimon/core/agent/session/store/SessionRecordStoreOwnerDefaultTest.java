package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * The one defaulted write on {@link SessionRecordStore}: a store written before owners existed must keep working for
 * every owner-less call and must refuse an owner loudly rather than drop it.
 */
@DisplayName("SessionRecordStore.provision(id, agentRef, owner) default")
class SessionRecordStoreOwnerDefaultTest {

    private static final SessionId ID = SessionId.of("legacy");

    @Test
    @DisplayName("without an owner it is the two-argument provision")
    void delegatesWithoutAnOwner() {
        final SessionRecordStore legacy = mock(SessionRecordStore.class, CALLS_REAL_METHODS);
        final SessionRecordView record = StoredSessionRecord.empty(ID, "agent-a");
        doReturn(record).when(legacy).provision(ID, "agent-a");

        assertThat(legacy.provision(ID, "agent-a", null)).isSameAs(record);
    }

    @Test
    @DisplayName("with an owner it throws instead of silently dropping it")
    void refusesAnOwner() {
        final SessionRecordStore legacy = mock(SessionRecordStore.class, CALLS_REAL_METHODS);

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> legacy.provision(ID, "agent-a", Principal.user("alice")))
                .withMessageContaining("does not persist session owners");
    }

    @Test
    @DisplayName("a view that does not know owners reports none")
    void viewDefault() {
        final SessionRecordView view = mock(SessionRecordView.class, CALLS_REAL_METHODS);

        assertThat(view.getOwner()).isEmpty();
    }
}
