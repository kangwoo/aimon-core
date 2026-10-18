package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * The owner defaults on {@link SessionRecordStore}. The one defaulted write: a store written before owners existed must
 * keep working for every owner-less call and must refuse an owner loudly rather than drop it. The defaulted read: a
 * store that does not override {@code loadOwner} must still answer it, through {@code load}.
 */
@DisplayName("SessionRecordStore owner defaults")
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
    @DisplayName("loadOwner without an override reads the owner of the loaded record")
    void loadOwnerFallsBackToLoad() {
        final SessionRecordStore legacy = mock(SessionRecordStore.class, CALLS_REAL_METHODS);
        final Principal alice = Principal.user("alice", "Alice");
        doReturn(Optional.of(StoredSessionRecord.empty(ID, "agent-a", alice))).when(legacy).load(ID);

        assertThat(legacy.loadOwner(ID)).contains(alice);
        assertThat(legacy.loadOwner(ID).orElseThrow().getDisplayName()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("loadOwner without an override is empty for a record without an owner")
    void loadOwnerFallbackWithoutAnOwner() {
        final SessionRecordStore legacy = mock(SessionRecordStore.class, CALLS_REAL_METHODS);
        doReturn(Optional.of(StoredSessionRecord.empty(ID, "agent-a"))).when(legacy).load(ID);

        assertThat(legacy.loadOwner(ID)).isEmpty();
    }

    @Test
    @DisplayName("loadOwner without an override is empty when there is no record")
    void loadOwnerFallbackWithoutARecord() {
        final SessionRecordStore legacy = mock(SessionRecordStore.class, CALLS_REAL_METHODS);
        doReturn(Optional.empty()).when(legacy).load(ID);

        assertThat(legacy.loadOwner(ID)).isEmpty();
    }

    @Test
    @DisplayName("loadOwner without an override refuses a null id itself, before it delegates to load")
    void loadOwnerFallbackRefusesANullId() {
        final SessionRecordStore legacy = mock(SessionRecordStore.class, CALLS_REAL_METHODS);

        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> legacy.loadOwner(null));
        verify(legacy, never()).load(any());
    }

    @Test
    @DisplayName("a view that does not know owners reports none")
    void viewDefault() {
        final SessionRecordView view = mock(SessionRecordView.class, CALLS_REAL_METHODS);

        assertThat(view.getOwner()).isEmpty();
    }
}
