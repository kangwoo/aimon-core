package at.aimon.core.memory.deriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QueueStats")
class QueueStatsTest {

    @Test
    @DisplayName("of() exposes the four counts")
    void factory() {
        QueueStats stats = QueueStats.of(3, 2, 100L, 5L);

        assertThat(stats.getQueueSize()).isEqualTo(3);
        assertThat(stats.getActiveWorkers()).isEqualTo(2);
        assertThat(stats.getCompletedTasks()).isEqualTo(100L);
        assertThat(stats.getFailedTasks()).isEqualTo(5L);
    }

    @Test
    @DisplayName("negative counts are rejected")
    void rejectsNegativeCounts() {
        assertThatThrownBy(() -> QueueStats.of(-1, 0, 0L, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueStats.of(0, -1, 0L, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueStats.of(0, 0, -1L, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueStats.of(0, 0, 0L, -1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
