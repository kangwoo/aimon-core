package at.aimon.core.tools.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.tools.BackgroundTaskOwner;

@DisplayName("InMemoryMonitorStore — put-if-absent, find, remove")
class InMemoryMonitorStoreTest {

    private static MonitorRecord record(String id) {
        return MonitorRecord.builder().monitorId(id)
                .owner(BackgroundTaskOwner.of(AgentRuntimeId.of("agent:a"), SessionId.of("s"), null)).nodeId("n")
                .kind("shell").description("d").startedAt(Instant.EPOCH).deadline(Instant.EPOCH).build();
    }

    @Test
    @DisplayName("a taken id is refused, a stored record is found as written, and removal forgets it")
    void lifecycle() {
        final InMemoryMonitorStore store = new InMemoryMonitorStore();

        assertThat(store.putIfAbsent(record("mon_1"))).isTrue();
        assertThat(store.putIfAbsent(record("mon_1"))).isFalse();
        assertThat(store.find("mon_1")).contains(record("mon_1"));

        store.remove("mon_1");
        store.remove("mon_1");
        assertThat(store.find("mon_1")).isEmpty();
    }
}
