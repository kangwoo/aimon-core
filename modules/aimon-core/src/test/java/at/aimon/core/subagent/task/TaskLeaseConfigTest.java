package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskLeaseConfig — zombie-recovery lease configuration (design §5.3.2 ③, §7)")
class TaskLeaseConfigTest {

    @Test
    @DisplayName("defaults() gives 10s heartbeat, 30s TTL (3x margin), 10s sweep")
    void defaults() {
        TaskLeaseConfig config = TaskLeaseConfig.defaults();

        assertThat(config.getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.getLeaseTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.getSweepInterval()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("of(heartbeat, ttl) defaults the sweep interval to the heartbeat interval")
    void ofDefaultsSweepToHeartbeat() {
        TaskLeaseConfig config = TaskLeaseConfig.of(Duration.ofSeconds(2), Duration.ofSeconds(6));

        assertThat(config.getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(config.getLeaseTtl()).isEqualTo(Duration.ofSeconds(6));
        assertThat(config.getSweepInterval()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("builder allows an independent sweep interval")
    void builderIndependentSweep() {
        TaskLeaseConfig config = TaskLeaseConfig.builder().heartbeatInterval(Duration.ofSeconds(5))
                .leaseTtl(Duration.ofSeconds(20)).sweepInterval(Duration.ofSeconds(3)).build();

        assertThat(config.getSweepInterval()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("leaseTtl must be strictly greater than heartbeatInterval")
    void ttlMustExceedHeartbeat() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TaskLeaseConfig.of(Duration.ofSeconds(10), Duration.ofSeconds(10)))
                .withMessageContaining("leaseTtl");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TaskLeaseConfig.of(Duration.ofSeconds(10), Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("non-positive intervals are rejected")
    void nonPositiveRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TaskLeaseConfig.builder().heartbeatInterval(Duration.ZERO).build());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TaskLeaseConfig.builder().leaseTtl(Duration.ofSeconds(-1)).build());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TaskLeaseConfig.builder().sweepInterval(Duration.ZERO).build());
    }

    @Test
    @DisplayName("null durations are rejected")
    void nullRejected() {
        assertThatNullPointerException().isThrownBy(() -> TaskLeaseConfig.builder().heartbeatInterval(null).build());
        assertThatNullPointerException().isThrownBy(() -> TaskLeaseConfig.builder().leaseTtl(null).build());
        assertThatNullPointerException().isThrownBy(() -> TaskLeaseConfig.builder().sweepInterval(null).build());
    }
}
