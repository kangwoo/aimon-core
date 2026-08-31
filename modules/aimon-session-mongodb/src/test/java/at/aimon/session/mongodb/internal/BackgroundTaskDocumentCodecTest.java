package at.aimon.session.mongodb.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;

/**
 * Unit tests for {@link BackgroundTaskDocumentCodec} — a full BSON {@link Document} round-trip, exercised without a
 * container so it runs under the daemonless {@code test} task, plus the frozen wire keys the round-trip cannot see.
 */
@DisplayName("BackgroundTaskDocumentCodec — round-trip, and frozen wire keys the round-trip cannot see")
class BackgroundTaskDocumentCodecTest {

    private final BackgroundTaskDocumentCodec codec = new BackgroundTaskDocumentCodec();

    @Test
    @DisplayName("a fully-populated snapshot survives encode → decode")
    void roundTripFullyPopulated() {
        final BackgroundTask original = BackgroundTask.builder().taskId("t1").subagentName("explore")
                .description("investigate logs").state(BackgroundTaskState.RUNNING)
                .startTime(Instant.parse("2026-07-07T10:00:00Z")).endTime(Instant.parse("2026-07-07T10:05:00Z"))
                .outputOffset(4096L).owner(Principal.user("alice", "Alice"))
                .agentRuntimeId(AgentRuntimeId.of("agent:ops")).lastHeartbeat(Instant.parse("2026-07-07T10:04:30Z"))
                .build();

        final BackgroundTask decoded = codec.decode(codec.encode(original));

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.getEndTime()).contains(Instant.parse("2026-07-07T10:05:00Z"));
        assertThat(decoded.getOwner()).contains(Principal.user("alice", "Alice"));
        assertThat(decoded.getAgentRuntimeId()).contains(AgentRuntimeId.of("agent:ops"));
        assertThat(decoded.getLastHeartbeat()).contains(Instant.parse("2026-07-07T10:04:30Z"));
        assertThat(decoded.getOutputOffset()).isEqualTo(4096L);
    }

    @Test
    @DisplayName("a minimal snapshot omits absent optionals and decodes them empty")
    void roundTripMinimal() {
        final BackgroundTask original = BackgroundTask.builder().taskId("t2").subagentName("explore").description("")
                .state(BackgroundTaskState.PENDING).startTime(Instant.parse("2026-07-07T10:00:00Z")).build();

        final Document doc = codec.encode(original);
        assertThat(doc.containsKey(DocumentKeys.F_END_TIME)).isFalse();
        assertThat(doc.containsKey(DocumentKeys.F_OWNER)).isFalse();
        assertThat(doc.containsKey(DocumentKeys.F_CONTEXT_ID)).isFalse();
        assertThat(doc.containsKey(DocumentKeys.F_LAST_HEARTBEAT)).isFalse();

        final BackgroundTask decoded = codec.decode(doc);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.getDescription()).isEmpty();
        assertThat(decoded.getEndTime()).isEmpty();
        assertThat(decoded.getOwner()).isEmpty();
        assertThat(decoded.getAgentRuntimeId()).isEmpty();
        assertThat(decoded.getLastHeartbeat()).isEmpty();
        assertThat(decoded.getOutputOffset()).isZero();
    }

    @Test
    @DisplayName("the owning runtime id is encoded under the frozen wire key \"contextId\"")
    void contextIdKeyIsFrozenOnEncode() {
        // FROZEN WIRE FORMAT. AgentExecutionContext became AgentRuntime in Java, but the BSON field name stayed
        // "contextId" on purpose (CHANGELOG, "Not changed (deliberately frozen)"). Two things depend on that literal
        // and neither is visible to a round-trip: documents already in the collection, and the `{ contextId: 1 }`
        // index declared in init.js that serves the by-owning-context listing. This assertion deliberately spells the
        // key out instead of going through DocumentKeys.F_CONTEXT_ID — a rename sweep would carry the constant and
        // every reference to it along in lockstep, so a constant-based assertion can never fail.
        final BackgroundTask task = BackgroundTask.builder().taskId("t3").subagentName("explore").description("d")
                .state(BackgroundTaskState.RUNNING).startTime(Instant.parse("2026-07-07T10:00:00Z"))
                .agentRuntimeId(AgentRuntimeId.of("agent:ops:prod")).build();

        final Document doc = codec.encode(task);

        assertThat(doc.keySet()).contains("contextId");
        assertThat(doc.getString("contextId")).isEqualTo("agent:ops:prod");
    }

    @Test
    @DisplayName("a document already stored under \"contextId\" still decodes to its owning runtime")
    void contextIdKeyIsFrozenOnDecode() {
        // The reader half of the freeze: guarding encode() alone would still let a decode()-side rename orphan every
        // document already in the collection. Note roundTripMinimal above does NOT cover this — it asserts an EMPTY
        // agentRuntimeId, which is exactly what a renamed reader yields when it cannot find its key, so it stays green
        // through the very rename this test exists to catch.
        final Document stored = new Document().append("_id", "t4").append("subagentName", "explore")
                .append("description", "d").append("state", "RUNNING")
                .append("startTime", Date.from(Instant.parse("2026-07-07T10:00:00Z"))).append("outputOffset", 0L)
                .append("contextId", "agent:ops:prod");

        final BackgroundTask decoded = codec.decode(stored);

        assertThat(decoded.getAgentRuntimeId()).contains(AgentRuntimeId.of("agent:ops:prod"));
    }
}
