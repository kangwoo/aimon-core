package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.scheduling.ScheduledExecutionGuard.ExecutionLease;

@DisplayName("InMemoryScheduledExecutionGuard")
class InMemoryScheduledExecutionGuardTest {

    private final InMemoryScheduledExecutionGuard guard = new InMemoryScheduledExecutionGuard();
    private final ScheduledTaskId taskA = ScheduledTaskId.of("task-a");
    private final ScheduledTaskId taskB = ScheduledTaskId.of("task-b");

    @Test
    @DisplayName("grants the first claim and denies an overlapping claim for the same task")
    void deniesOverlappingClaim() {
        final Optional<ExecutionLease> first = guard.tryBegin(taskA);
        assertThat(first).isPresent();
        assertThat(guard.isInProgress(taskA)).isTrue();

        // A second, overlapping fire of the same task is denied while the first lease is held.
        assertThat(guard.tryBegin(taskA)).isEmpty();
    }

    @Test
    @DisplayName("releases the claim on lease close, allowing a subsequent execution")
    void releasesOnClose() {
        final ExecutionLease lease = guard.tryBegin(taskA).orElseThrow();
        lease.close();

        assertThat(guard.isInProgress(taskA)).isFalse();
        assertThat(guard.tryBegin(taskA)).isPresent();
    }

    @Test
    @DisplayName("claims are independent per task id")
    void claimsAreIndependentPerTask() {
        assertThat(guard.tryBegin(taskA)).isPresent();
        // A different task is unaffected by taskA's in-flight claim.
        assertThat(guard.tryBegin(taskB)).isPresent();
        assertThat(guard.isInProgress(taskA)).isTrue();
        assertThat(guard.isInProgress(taskB)).isTrue();
    }

    @Test
    @DisplayName("try-with-resources releases the lease even on exceptional exit")
    void tryWithResourcesReleases() {
        try (ExecutionLease ignored = guard.tryBegin(taskA).orElseThrow()) {
            assertThat(guard.isInProgress(taskA)).isTrue();
            throw new IllegalStateException("boom");
        } catch (IllegalStateException expected) {
            // fall through
        }
        assertThat(guard.isInProgress(taskA)).isFalse();
    }

    @Test
    @DisplayName("rejects a null task id")
    void rejectsNull() {
        assertThatThrownBy(() -> guard.tryBegin(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ALLOW_ALL always grants and never dedups")
    void allowAllAlwaysGrants() {
        assertThat(ScheduledExecutionGuard.ALLOW_ALL.tryBegin(taskA)).isPresent();
        // Even without releasing, ALLOW_ALL grants again (no deduplication).
        assertThat(ScheduledExecutionGuard.ALLOW_ALL.tryBegin(taskA)).isPresent();
    }
}
