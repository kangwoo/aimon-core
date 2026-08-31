package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;

/**
 * Verifies that {@link ScopedSessionSnapshotStore} confines resume loads to the bound agent runtime
 * while
 * passing saves and evictions straight through.
 */
class ScopedSessionSnapshotStoreTest {

    private static final AgentRuntimeId OWNER = AgentRuntimeId.of("agent:reviewer");
    private static final AgentRuntimeId OTHER = AgentRuntimeId.of("agent:planner");

    private SessionSnapshotStore delegate;

    @BeforeEach
    void setUp() {
        delegate = new InMemorySessionSnapshotStore();
    }

    private static SessionSnapshot snapshot() {
        return SessionSnapshot.of(SessionId.generate(), "sys", List.of());
    }

    @Test
    void loadSurfacesTranscriptOwnedByBoundContext() {
        delegate.save("t1", "Explore", OWNER, snapshot());
        SessionSnapshotStore scoped = new ScopedSessionSnapshotStore(delegate, OWNER);

        Optional<ResumableSession> loaded = scoped.load("t1");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSubagentName()).isEqualTo("Explore");
        assertThat(loaded.get().getAgentRuntimeId()).contains(OWNER);
    }

    @Test
    void loadHidesTranscriptOwnedByAnotherContext() {
        delegate.save("t1", "Explore", OTHER, snapshot());
        SessionSnapshotStore scoped = new ScopedSessionSnapshotStore(delegate, OWNER);

        assertThat(scoped.load("t1")).isEmpty();
    }

    @Test
    void loadHidesUntaggedTranscript() {
        // A transcript recorded without a context (unverifiable ownership) must not resume under any scope.
        delegate.save("t1", "Explore", snapshot());
        SessionSnapshotStore scoped = new ScopedSessionSnapshotStore(delegate, OWNER);

        assertThat(scoped.load("t1")).isEmpty();
    }

    @Test
    void loadOfUnknownIdIsEmpty() {
        SessionSnapshotStore scoped = new ScopedSessionSnapshotStore(delegate, OWNER);

        assertThat(scoped.load("nope")).isEmpty();
    }

    @Test
    void saveDelegatesAndTagsWithGivenContext() {
        SessionSnapshotStore scoped = new ScopedSessionSnapshotStore(delegate, OWNER);

        scoped.save("t1", "Explore", OWNER, snapshot());

        // Visible through the raw delegate with its context preserved.
        assertThat(delegate.load("t1")).isPresent();
        assertThat(delegate.load("t1").get().getAgentRuntimeId()).contains(OWNER);
    }

    @Test
    void evictDelegates() {
        delegate.save("t1", "Explore", OWNER, snapshot());
        SessionSnapshotStore scoped = new ScopedSessionSnapshotStore(delegate, OWNER);

        scoped.evict("t1");

        assertThat(delegate.load("t1")).isEmpty();
    }

    @Test
    void scopeOrPassThroughReturnsRawDelegateWhenContextAbsent() {
        SessionSnapshotStore result = ScopedSessionSnapshotStore.scopeOrPassThrough(delegate, Optional.empty());

        assertThat(result).isSameAs(delegate);
    }

    @Test
    void scopeOrPassThroughWrapsWhenContextPresent() {
        delegate.save("t1", "Explore", OTHER, snapshot());

        SessionSnapshotStore result = ScopedSessionSnapshotStore.scopeOrPassThrough(delegate, Optional.of(OWNER));

        assertThat(result).isInstanceOf(ScopedSessionSnapshotStore.class);
        // The wrapper enforces scoping: the foreign transcript is hidden.
        assertThat(result.load("t1")).isEmpty();
    }

    @Test
    void constructorRejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new ScopedSessionSnapshotStore(null, OWNER));
        assertThatNullPointerException().isThrownBy(() -> new ScopedSessionSnapshotStore(delegate, null));
    }

    @Test
    void scopeOrPassThroughRejectsNullArguments() {
        assertThatNullPointerException()
                .isThrownBy(() -> ScopedSessionSnapshotStore.scopeOrPassThrough(null, Optional.of(OWNER)));
        assertThatNullPointerException()
                .isThrownBy(() -> ScopedSessionSnapshotStore.scopeOrPassThrough(delegate, null));
    }
}
