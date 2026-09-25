package at.aimon.bootstrap.assemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.transcript.SessionLogSegmentSweeper;

class SegmentSweepScheduleTest {

    private final InMemorySessionLogSegmentStore segments = new InMemorySessionLogSegmentStore();
    private final SessionLogSegmentSweeper sweeper = SessionLogSegmentSweeper
            .builder(segments, new InMemorySessionRecordStore()).grace(Duration.ofMinutes(1)).build();

    private SessionId plantOrphan() {
        final SessionId session = SessionId.generate();
        segments.put(SessionLogSegment.builder().sessionId(session).id(SegmentId.generate()).fromSeq(0).toSeq(1)
                .entryCount(1).payload("[]").createdAt(Instant.now().minus(Duration.ofHours(1))).build());
        return session;
    }

    @Test
    @DisplayName("nothing runs before start; after start the orphan is swept; close stops it")
    void runsBetweenStartAndClose() throws Exception {
        final SessionId session = plantOrphan();
        try (SegmentSweepSchedule schedule = new SegmentSweepSchedule(sweeper, Duration.ofMillis(20))) {
            TimeUnit.MILLISECONDS.sleep(100);
            assertThat(segments.list(session)).as("not started").hasSize(1);

            schedule.start();
            schedule.start();
            assertThat(schedule.isRunning()).isTrue();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!segments.list(session).isEmpty() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertThat(segments.list(session)).isEmpty();

            schedule.close();
            assertThat(schedule.isRunning()).isFalse();
            final SessionId afterClose = plantOrphan();
            TimeUnit.MILLISECONDS.sleep(100);
            assertThat(segments.list(afterClose)).as("closed").hasSize(1);
            schedule.start();
            assertThat(schedule.isRunning()).as("a closed schedule does not restart").isFalse();
        }
    }

    @Test
    @DisplayName("a non-positive interval is refused")
    void rejectsNonPositiveInterval() {
        assertThatThrownBy(() -> new SegmentSweepSchedule(sweeper, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
