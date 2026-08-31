package at.aimon.session.redis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;

@DisplayName("BackgroundTaskCodec — lossless JSON round-trip, and frozen wire keys the round-trip cannot see")
class BackgroundTaskCodecTest {

    private final BackgroundTaskCodec codec = new BackgroundTaskCodec(
            new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    @DisplayName("a fully-populated snapshot round-trips to an equal object")
    void fullRoundTrip() {
        BackgroundTask task = BackgroundTask.builder().taskId("t-1").subagentName("explore").description("investigate")
                .state(BackgroundTaskState.RUNNING).startTime(Instant.parse("2026-07-07T10:15:30.123456789Z"))
                .endTime(Instant.parse("2026-07-07T10:16:00Z")).outputOffset(4096L).owner(Principal.user("alice"))
                .agentRuntimeId(AgentRuntimeId.of("agent:ops:prod"))
                .lastHeartbeat(Instant.parse("2026-07-07T10:15:59.5Z")).build();

        BackgroundTask decoded = codec.decode(codec.encode(task));

        assertThat(decoded).isEqualTo(task);
        assertThat(decoded.getLastHeartbeat()).contains(Instant.parse("2026-07-07T10:15:59.5Z"));
    }

    @Test
    @DisplayName("a snapshot without a heartbeat round-trips with an empty lastHeartbeat (backward-compatible)")
    void noHeartbeatRoundTrip() {
        BackgroundTask task = BackgroundTask.builder().taskId("t-hb").subagentName("explore").description("d")
                .state(BackgroundTaskState.RUNNING).startTime(Instant.parse("2026-07-07T10:15:30Z")).build();

        BackgroundTask decoded = codec.decode(codec.encode(task));

        assertThat(decoded).isEqualTo(task);
        assertThat(decoded.getLastHeartbeat()).isEmpty();
    }

    @Test
    @DisplayName("a minimal snapshot (no endTime / owner / agentRuntimeId) round-trips to an equal object")
    void minimalRoundTrip() {
        BackgroundTask task = BackgroundTask.builder().taskId("t-2").subagentName("explore").description("d")
                .state(BackgroundTaskState.PENDING).startTime(Instant.parse("2026-07-07T10:15:30Z")).build();

        BackgroundTask decoded = codec.decode(codec.encode(task));

        assertThat(decoded).isEqualTo(task);
        assertThat(decoded.getEndTime()).isEmpty();
        assertThat(decoded.getOwner()).isEmpty();
        assertThat(decoded.getAgentRuntimeId()).isEmpty();
    }

    @Test
    @DisplayName("owner type is preserved across the round-trip (group, not user)")
    void ownerTypePreserved() {
        BackgroundTask task = BackgroundTask.builder().taskId("t-3").subagentName("explore").description("d")
                .state(BackgroundTaskState.COMPLETED).startTime(Instant.now())
                .owner(Principal.builder().type(Principal.Type.GROUP).id("sre").displayName("SRE").build()).build();

        BackgroundTask decoded = codec.decode(codec.encode(task));

        assertThat(decoded.getOwner()).isPresent();
        assertThat(decoded.getOwner().orElseThrow().getType()).isEqualTo(Principal.Type.GROUP);
        assertThat(decoded.getOwner().orElseThrow().getId()).isEqualTo("sre");
    }

    @Test
    @DisplayName("the owning runtime id is encoded under the frozen wire key \"contextId\"")
    void contextIdKeyIsFrozenOnEncode() throws IOException {
        // FROZEN WIRE FORMAT. AgentExecutionContext became AgentRuntime in Java, but the Redis payload key stayed
        // "contextId" on purpose (CHANGELOG, "Not changed (deliberately frozen)") — these STRINGs outlive the process
        // that wrote them and are read by every other node in the cluster. None of the round-trip tests above can
        // protect the name: renaming encode() and decode() together keeps them all green while every task snapshot
        // already in Redis, and every one written by a node still on the old build, loses its owning runtime.
        BackgroundTask task = BackgroundTask.builder().taskId("t-5").subagentName("explore").description("d")
                .state(BackgroundTaskState.RUNNING).startTime(Instant.parse("2026-07-07T10:15:30Z"))
                .agentRuntimeId(AgentRuntimeId.of("agent:ops:prod")).build();

        JsonNode root = new ObjectMapper().readTree(codec.encode(task));

        List<String> fields = new ArrayList<>();
        root.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).contains("contextId");
        assertThat(root.get("contextId").asText()).isEqualTo("agent:ops:prod");
    }

    @Test
    @DisplayName("a payload already in Redis under \"contextId\" still decodes to its owning runtime")
    void contextIdKeyIsFrozenOnDecode() {
        // The reader half of the freeze: guarding only encode() would still let a decode()-side rename orphan every
        // snapshot already persisted. minimalRoundTrip above asserts an EMPTY agentRuntimeId, which is also what a
        // renamed reader yields when its key is missing, so it survives the rename this test exists to catch.
        BackgroundTask decoded = codec.decode("{\"taskId\":\"t-6\",\"subagentName\":\"explore\",\"state\":\"RUNNING\","
                + "\"startTime\":\"2026-07-07T10:15:30Z\",\"outputOffset\":0,\"description\":\"d\","
                + "\"contextId\":\"agent:ops:prod\"}");

        assertThat(decoded.getAgentRuntimeId()).contains(AgentRuntimeId.of("agent:ops:prod"));
    }

    @Test
    @DisplayName("the state field is emitted verbatim so the store's terminal-guard Lua can match it as a substring")
    void stateTokenIsVerbatim() {
        // A description crafted to look like a terminal state must NOT be able to forge the guard token: Jackson
        // backslash-escapes the quotes inside the user string, so the literal token appears only for the real field.
        BackgroundTask task = BackgroundTask.builder().taskId("t-4").subagentName("explore")
                .description("\"state\":\"KILLED\"").state(BackgroundTaskState.RUNNING).startTime(Instant.now())
                .build();

        String json = codec.encode(task);

        assertThat(json).contains("\"state\":\"RUNNING\"");
        assertThat(json).doesNotContain("\"state\":\"KILLED\"");
    }
}
