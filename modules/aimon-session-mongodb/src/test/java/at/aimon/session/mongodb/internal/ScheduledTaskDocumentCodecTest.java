package at.aimon.session.mongodb.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentDefinitionVersion;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;

/**
 * Unit tests for {@link ScheduledTaskDocumentCodec} — the round-trip without a container, and the stored field names
 * the round-trip cannot see.
 */
@DisplayName("ScheduledTaskDocumentCodec — round-trip, and the frozen keys the round-trip cannot see")
class ScheduledTaskDocumentCodecTest {

    private final ScheduledTaskDocumentCodec codec = new ScheduledTaskDocumentCodec();

    @Test
    @DisplayName("a task with every field set survives encode → decode")
    void roundTrip() {
        final ScheduledTask task = fullTask();

        assertThat(codec.decode(codec.encode(task))).usingRecursiveComparison().isEqualTo(task);
    }

    @Test
    @DisplayName("the stored keys are spelled as documents already on disk spell them")
    void storedKeysAreFrozen() {
        // FROZEN STORAGE FORMAT. Every key below is in documents a deployment already holds, and two of them are in
        // init.js's indexes. Literals on purpose: an assertion that goes through the codec's constants moves with a
        // rename and can never fail.
        final Document doc = codec.encode(fullTask());

        assertThat(doc.keySet()).containsExactlyInAnyOrder("_id", "name", "description", "cronExpression", "timezone",
                "routine", "owner", "boundRuntimeId", "agentDefinitionVersion", "enabled", "createdAt",
                "lastExecutedAt");
        assertThat(doc.get("owner", Document.class).keySet()).containsExactlyInAnyOrder("type", "id", "displayName");
        assertThat(doc.getList("routine", Document.class).get(0).keySet()).containsExactlyInAnyOrder("id", "tool",
                "toolParams", "maxRetries", "retryDelay", "timeout");
        assertThat(doc.getBoolean("enabled")).isTrue();
        assertThat(doc.getString("createdAt")).isEqualTo("2026-10-06T01:02:03.123456789Z");
        assertThat(doc.getList("routine", Document.class).get(0).getString("retryDelay")).isEqualTo("PT1.5S");
    }

    @Test
    @DisplayName("a document already stored under those keys still decodes")
    void storedKeysAreFrozenOnDecode() {
        final Document stored = new Document("_id", "task-9").append("name", "stored")
                .append("cronExpression", "0 2 * * *")
                .append("routine",
                        List.of(new Document("tool", "Bash").append("toolParams", "{}").append("maxRetries", 1)
                                .append("retryDelay", "PT2S").append("timeout", "PT1M")))
                .append("owner", new Document("type", "USER").append("id", "alice").append("displayName", "Alice"))
                .append("boundRuntimeId", "agent:ops").append("enabled", true)
                .append("createdAt", "2026-10-06T00:00:00Z");

        final ScheduledTask task = codec.decode(stored);

        assertThat(task.getId()).isEqualTo(ScheduledTaskId.of("task-9"));
        assertThat(task.getOwner()).isEqualTo(Principal.user("alice"));
        assertThat(task.getBoundRuntimeId()).isEqualTo(AgentRuntimeId.of("agent:ops"));
        assertThat(task.isEnabled()).isTrue();
        assertThat(task.getRoutine()).singleElement().satisfies(step -> {
            assertThat(step.getTool()).isEqualTo("Bash");
            assertThat(step.getRetryDelay()).isEqualTo(Duration.ofSeconds(2));
            assertThat(step.getTimeout()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    @DisplayName("a document without an owner is not a task")
    void missingOwnerIsRejected() {
        final Document doc = codec.encode(fullTask());
        doc.remove("owner");

        assertThatThrownBy(() -> codec.decode(doc)).isInstanceOf(RuntimeException.class);
    }

    private static ScheduledTask fullTask() {
        return ScheduledTask.builder().id(ScheduledTaskId.of("task-1")).name("nightly").description("a description")
                .cronExpression("15 2 * * 1-5").timezone("Asia/Seoul")
                .routine(List.of(RoutineStep.builder().id("s1").tool("Grep").toolParams("{}").maxRetries(7)
                        .retryDelay(Duration.ofMillis(1500)).timeout(Duration.ofSeconds(42)).build()))
                .owner(Principal.service("reporter", "Report Service")).boundRuntimeId(AgentRuntimeId.of("agent:ops"))
                .agentDefinitionVersion(AgentDefinitionVersion.of("v1")).enabled(true)
                .createdAt(Instant.parse("2026-10-06T01:02:03.123456789Z"))
                .lastExecutedAt(Instant.parse("2026-10-06T02:15:00.000000001Z")).build();
    }
}
