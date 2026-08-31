package at.aimon.core.memory.deriver.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("DeriverMessageLinkTool")
class DeriverMessageLinkToolTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView AGENT = PeerView.of(WS, Principal.system());

    private InMemoryObservationStore store;
    private DeriverMessageLinkTool tool;
    private Observation seed;

    @BeforeEach
    void setUp() {
        store = new InMemoryObservationStore();
        tool = new DeriverMessageLinkTool(store);
        seed = store.save(Observation.builder().id(ObservationId.of(WS, "obs-1")).subject(ALICE).observer(AGENT)
                .content("alice prefers tea").type(ObservationType.EXPLICIT).confidence(0.9d)
                .sourceMessageIds(List.of("msg-1")).createdAt(Instant.now()).build());
    }

    @Test
    @DisplayName("appends new message ids preserving existing ones")
    void appendsIds() {
        Map<String, Object> args = new HashMap<>();
        args.put("observation_id", "obs-1");
        args.put("message_ids", List.of("msg-2", "msg-3"));

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("added: 2");
        Observation updated = store.findById(seed.getId()).orElseThrow();
        assertThat(updated.getSourceMessageIds()).containsExactly("msg-1", "msg-2", "msg-3");
    }

    @Test
    @DisplayName("ignores duplicates already linked to the observation")
    void ignoresDuplicates() {
        Map<String, Object> args = new HashMap<>();
        args.put("observation_id", "obs-1");
        args.put("message_ids", List.of("msg-1"));

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("added: 0");
        Observation unchanged = store.findById(seed.getId()).orElseThrow();
        assertThat(unchanged.getSourceMessageIds()).containsExactly("msg-1");
    }

    @Test
    @DisplayName("preserves immutable fields (content, confidence, metadata)")
    void preservesOtherFields() {
        Map<String, Object> args = new HashMap<>();
        args.put("observation_id", "obs-1");
        args.put("message_ids", List.of("msg-2"));

        tool.execute(ToolInput.of(args), context());

        Observation updated = store.findById(seed.getId()).orElseThrow();
        assertThat(updated.getContent()).isEqualTo(seed.getContent());
        assertThat(updated.getConfidence()).isEqualTo(seed.getConfidence());
        assertThat(updated.getType()).isEqualTo(seed.getType());
        assertThat(updated.getMetadata()).isEqualTo(seed.getMetadata());
        assertThat(updated.getCreatedAt()).isEqualTo(seed.getCreatedAt());
    }

    @Test
    @DisplayName("missing observation yields error")
    void notFound() {
        Map<String, Object> args = new HashMap<>();
        args.put("observation_id", "ghost");
        args.put("message_ids", List.of("msg-2"));

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Observation not found");
    }

    @Test
    @DisplayName("missing observation_id yields invalid-parameter error")
    void missingObservationId() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("message_ids", List.of("msg-1"))), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    @Test
    @DisplayName("missing message_ids yields invalid-parameter error")
    void missingMessageIds() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("observation_id", "obs-1")), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    @Test
    @DisplayName("empty message_ids array yields error")
    void emptyMessageIds() {
        Map<String, Object> args = new HashMap<>();
        args.put("observation_id", "obs-1");
        args.put("message_ids", List.of());

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("message_ids");
    }

    @Test
    @DisplayName("non-string element in message_ids yields invalid-parameter error")
    void nonStringElement() {
        Map<String, Object> args = new HashMap<>();
        args.put("observation_id", "obs-1");
        args.put("message_ids", List.of(42));

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    @Test
    @DisplayName("missing workspace context yields error")
    void missingWorkspace() {
        Map<String, Object> args = new HashMap<>();
        args.put("observation_id", "obs-1");
        args.put("message_ids", List.of("msg-2"));

        ToolResult result = tool.execute(ToolInput.of(args), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("memory.workspace");
    }

    private static ToolContext context() {
        return ToolContext.builder().put(DeriverMessageLinkTool.WORKSPACE_KEY, WS).build();
    }
}
