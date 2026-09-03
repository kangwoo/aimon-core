package at.aimon.core.memory.deriver.tool;

import static org.assertj.core.api.Assertions.assertThat;

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
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.redaction.DefaultRedactionPolicy;

@DisplayName("DeriverObservationCreateTool")
class DeriverObservationCreateToolTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView AGENT = PeerView.of(WS, Principal.system());

    private InMemoryObservationStore store;
    private DeriverObservationCreateTool tool;

    @BeforeEach
    void setUp() {
        store = new InMemoryObservationStore();
        tool = new DeriverObservationCreateTool(store);
    }

    @Test
    @DisplayName("creates observation with default type and §4.3 base-score confidence")
    void createsWithDefaults() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "alice prefers tea")), context());

        assertThat(result.isSuccess()).isTrue();
        // Default type DEDUCTIVE → §4.3 base score 0.6 (computed, never self-reported by the LLM).
        assertThat(result.getContent()).contains("observation_id:", "type: DEDUCTIVE", "confidence: 0.60",
                "alice prefers tea");
        Observation saved = store.findBySubject(ALICE, 10).get(0);
        assertThat(saved.getType()).isEqualTo(ObservationType.DEDUCTIVE);
        assertThat(saved.getConfidence()).isEqualTo(0.6d);
        assertThat(saved.getMetadata()).containsEntry("source", "DeriverObservationCreateTool");
    }

    /**
     * This tool is reached by {@code ReActLlmDeriver} calling {@code tool.execute(...)} directly, so it never meets
     * the schema-validation gate — not even in its permissive {@code WARN} default. The schema's {@code enum} is the
     * only statement of what is allowed, and before the enum was widened the parser happened to agree with it.
     */
    @Test
    @DisplayName("an observation type the schema does not advertise is refused, and nothing is written")
    void unadvertisedEnumValueIsRefused() {
        Map<String, Object> args = new HashMap<>();
        args.put("content", "alice deploys on Fridays");
        args.put("type", "INDUCTIVE");

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter", "EXPLICIT", "DEDUCTIVE");
        assertThat(store.findBySubject(ALICE, 10)).isEmpty();
    }

    @Test
    @DisplayName("EXPLICIT type yields §4.3 base-score confidence 0.9")
    void explicitTypeBaseScore() {
        Map<String, Object> args = new HashMap<>();
        args.put("content", "alice said she prefers tea");
        args.put("type", "EXPLICIT");

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isSuccess()).isTrue();
        Observation saved = store.findBySubject(ALICE, 10).get(0);
        assertThat(saved.getType()).isEqualTo(ObservationType.EXPLICIT);
        assertThat(saved.getConfidence()).isEqualTo(0.9d);
    }

    @Test
    @DisplayName("attaches source_message_ids when provided")
    void attachesSourceMessageIds() {
        Map<String, Object> args = new HashMap<>();
        args.put("content", "alice runs marathons");
        args.put("source_message_ids", List.of("msg-1", "msg-2"));

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isSuccess()).isTrue();
        Observation saved = store.findBySubject(ALICE, 10).get(0);
        assertThat(saved.getSourceMessageIds()).containsExactly("msg-1", "msg-2");
    }

    @Test
    @DisplayName("non-string source_message_ids element yields invalid-parameter error")
    void invalidSourceMessageIdsElement() {
        Map<String, Object> args = new HashMap<>();
        args.put("content", "x");
        args.put("source_message_ids", List.of(42));

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    @Test
    @DisplayName("blank content yields error")
    void blankContent() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "   ")), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("content");
    }

    @Test
    @DisplayName("missing workspace yields error")
    void missingWorkspace() {
        ToolContext ctx = ToolContext.builder().put(DeriverObservationCreateTool.OBSERVER_KEY, AGENT).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "x")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("memory.workspace");
    }

    @Test
    @DisplayName("missing observer yields error")
    void missingObserver() {
        ToolContext ctx = ToolContext.builder().put(DeriverObservationCreateTool.WORKSPACE_KEY, WS).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "x")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("memory.observer");
    }

    @Test
    @DisplayName("subject in a different workspace yields error")
    void crossWorkspaceSubjectRejected() {
        Workspace ws2 = Workspace.builder().id("ws-2").build();
        PeerView aliceWs2 = PeerView.of(ws2, Principal.user("alice", "Alice"));
        ToolContext ctx = ToolContext.builder().put(DeriverObservationCreateTool.WORKSPACE_KEY, WS)
                .put(DeriverObservationCreateTool.OBSERVER_KEY, AGENT)
                .put(DeriverObservationCreateTool.SUBJECT_KEY, aliceWs2).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "x")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("subject workspace");
    }

    @Test
    @DisplayName("redaction policy rewrites content and stamps metadata")
    void redactionRewritesContent() {
        DeriverObservationCreateTool secured = new DeriverObservationCreateTool(store, new DefaultRedactionPolicy());

        ToolResult result = secured.execute(ToolInput.of(Map.of("content", "alice token AKIAIOSFODNN7EXAMPLE")),
                context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("[REDACTED:AWS_KEY]");
        Observation saved = store.findBySubject(ALICE, 10).get(0);
        assertThat(saved.getContent()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(saved.getMetadata()).containsEntry("redacted", "true").containsEntry("redaction.categories",
                "AWS_KEY");
    }

    private static ToolContext context() {
        return ToolContext.builder().put(DeriverObservationCreateTool.WORKSPACE_KEY, WS)
                .put(DeriverObservationCreateTool.OBSERVER_KEY, AGENT)
                .put(DeriverObservationCreateTool.SUBJECT_KEY, ALICE).build();
    }
}
