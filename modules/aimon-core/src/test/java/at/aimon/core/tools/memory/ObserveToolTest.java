package at.aimon.core.tools.memory;

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
import at.aimon.core.memory.ObservationRecorder;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.redaction.DefaultRedactionPolicy;

@DisplayName("ObserveTool")
class ObserveToolTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView AGENT = PeerView.of(WS, Principal.system());

    private InMemoryObservationStore store;
    private ObserveTool tool;

    @BeforeEach
    void setUp() {
        store = new InMemoryObservationStore();
        tool = ObserveTool.overStore(store);
    }

    @Test
    @DisplayName("records an observation with default type and confidence")
    void recordsWithDefaults() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "alice prefers green tea")), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Observation recorded", "subject:", "type: DEDUCTIVE",
                "confidence: 0.70", "alice prefers green tea");
        assertThat(store.size()).isEqualTo(1);
        Observation saved = store.findBySubject(ALICE, 10).get(0);
        assertThat(saved.getType()).isEqualTo(ObservationType.DEDUCTIVE);
        assertThat(saved.getConfidence()).isEqualTo(0.7d);
        assertThat(saved.getMetadata()).containsEntry("source", "ObserveTool");
    }

    @Test
    @DisplayName("uses provided type and confidence")
    void usesProvidedTypeAndConfidence() {
        Map<String, Object> args = new HashMap<>();
        args.put("content", "alice was promoted to CTO");
        args.put("type", "EXPLICIT");
        args.put("confidence", 0.95);

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("type: EXPLICIT", "confidence: 0.95");
        Observation saved = store.findBySubject(ALICE, 10).get(0);
        assertThat(saved.getType()).isEqualTo(ObservationType.EXPLICIT);
        assertThat(saved.getConfidence()).isEqualTo(0.95d);
    }

    @Test
    @DisplayName("blank content yields error")
    void blankContent() {
        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "   ")), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("content");
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("missing content yields invalid-parameter error")
    void missingContent() {
        ToolResult result = tool.execute(ToolInput.of(), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    @Test
    @DisplayName("missing workspace yields error")
    void missingWorkspace() {
        ToolContext ctx = ToolContext.builder().put(ObserveTool.OBSERVER_KEY, AGENT).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "x")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("memory.workspace");
    }

    @Test
    @DisplayName("missing observer yields error")
    void missingObserver() {
        ToolContext ctx = ToolContext.builder().put(ObserveTool.WORKSPACE_KEY, WS).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "x")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("memory.observer");
    }

    @Test
    @DisplayName("subject defaults to observer when absent")
    void subjectDefaultsToObserver() {
        ToolContext ctx = ToolContext.builder().put(ObserveTool.WORKSPACE_KEY, WS).put(ObserveTool.OBSERVER_KEY, ALICE)
                .build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "alice runs marathons")), ctx);

        assertThat(result.isSuccess()).isTrue();
        Observation saved = store.findBySubject(ALICE, 10).get(0);
        assertThat(saved.getSubject()).isEqualTo(ALICE);
        assertThat(saved.getObserver()).isEqualTo(ALICE);
    }

    @Test
    @DisplayName("subject in a different workspace yields error")
    void crossWorkspaceSubjectRejected() {
        Workspace ws2 = Workspace.builder().id("ws-2").build();
        PeerView aliceWs2 = PeerView.of(ws2, Principal.user("alice", "Alice"));
        ToolContext ctx = ToolContext.builder().put(ObserveTool.WORKSPACE_KEY, WS).put(ObserveTool.OBSERVER_KEY, AGENT)
                .put(ObserveTool.SUBJECT_KEY, aliceWs2).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "x")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("subject workspace");
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("observer in a different workspace yields error")
    void crossWorkspaceObserverRejected() {
        Workspace ws2 = Workspace.builder().id("ws-2").build();
        PeerView agentWs2 = PeerView.of(ws2, Principal.system());
        ToolContext ctx = ToolContext.builder().put(ObserveTool.WORKSPACE_KEY, WS)
                .put(ObserveTool.OBSERVER_KEY, agentWs2).put(ObserveTool.SUBJECT_KEY, ALICE).build();

        ToolResult result = tool.execute(ToolInput.of(Map.of("content", "x")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("observer workspace");
    }

    @Test
    @DisplayName("confidence below 0 yields error")
    void confidenceBelowZero() {
        Map<String, Object> args = new HashMap<>();
        args.put("content", "x");
        args.put("confidence", -0.1);

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("confidence");
    }

    @Test
    @DisplayName("confidence above 1 yields error")
    void confidenceAboveOne() {
        Map<String, Object> args = new HashMap<>();
        args.put("content", "x");
        args.put("confidence", 1.5);

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("confidence");
    }

    @Test
    @DisplayName("non-numeric confidence yields invalid-parameter error")
    void nonNumericConfidence() {
        Map<String, Object> args = new HashMap<>();
        args.put("content", "x");
        args.put("confidence", "high");

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    @Test
    @DisplayName("unknown observation type yields invalid-parameter error")
    void unknownType() {
        Map<String, Object> args = new HashMap<>();
        args.put("content", "x");
        args.put("type", "GUESS");

        ToolResult result = tool.execute(ToolInput.of(args), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    /**
     * The values the enum has but the schema does not offer. Before the enum was widened these were rejected by
     * {@code ObservationType.valueOf} without anyone writing a check; afterwards they were accepted, and the schema
     * gate's default {@code WARN} mode logs a mismatch and runs the tool anyway — so an invented kind reached the
     * store. The advertisement is the contract, and this pins it.
     */
    @Test
    @DisplayName("an observation type the schema does not advertise is refused, even though the enum has it")
    void unadvertisedEnumValueIsRefused() {
        for (String unadvertised : new String[]{"INDUCTIVE", "CONTRADICTION", "inductive"}) {
            Map<String, Object> args = new HashMap<>();
            args.put("content", "x");
            args.put("type", unadvertised);

            ToolResult result = tool.execute(ToolInput.of(args), context());

            assertThat(result.isError()).as("type=%s", unadvertised).isTrue();
            assertThat(result.getContent()).contains("Invalid parameter", "EXPLICIT", "DEDUCTIVE");
            assertThat(store.findSubjects(WS, 10)).as("nothing was written for type=%s", unadvertised).isEmpty();
        }
    }

    @Test
    @DisplayName("the two advertised types still pass, in either case, and the schema still offers exactly them")
    void advertisedTypesStillPass() {
        for (String advertised : new String[]{"EXPLICIT", "DEDUCTIVE", "explicit"}) {
            Map<String, Object> args = new HashMap<>();
            args.put("content", "x");
            args.put("type", advertised);

            assertThat(tool.execute(ToolInput.of(args), context()).isSuccess()).as("type=%s", advertised).isTrue();
        }
        assertThat(typeEnum(tool)).containsExactly("EXPLICIT", "DEDUCTIVE");
    }

    /**
     * The narrowing must not disturb the other schema decision this tool makes — dropping {@code confidence} when the
     * backend does not store it.
     */
    @Test
    @DisplayName("a backend that drops confidence still gets the narrowed type enum and no confidence parameter")
    void confidenceNarrowingIsUnaffected() {
        ObserveTool dropping = new ObserveTool(new ObservationRecorder() {
            @Override
            public Observation observe(at.aimon.core.memory.ObservationDraft draft) {
                throw new UnsupportedOperationException("not exercised here");
            }

            @Override
            public boolean storesConfidence() {
                return false;
            }
        });

        assertThat(typeEnum(dropping)).containsExactly("EXPLICIT", "DEDUCTIVE");
        assertThat(properties(dropping)).containsKeys("content", "type").doesNotContainKey("confidence");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(ObserveTool tool) {
        return (Map<String, Object>) tool.getDefinition().getInputSchema().get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> typeEnum(ObserveTool tool) {
        return (List<String>) ((Map<String, Object>) properties(tool).get("type")).get("enum");
    }

    @Test
    @DisplayName("redaction policy rewrites content and stamps metadata")
    void redactionRewritesContent() {
        ObserveTool secured = ObserveTool.overStore(store, new DefaultRedactionPolicy());

        ToolResult result = secured.execute(ToolInput.of(Map.of("content", "alice token is AKIAIOSFODNN7EXAMPLE")),
                context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("[REDACTED:AWS_KEY]", "redacted: AWS_KEY");
        Observation saved = store.findBySubject(ALICE, 10).get(0);
        assertThat(saved.getContent()).contains("[REDACTED:AWS_KEY]");
        assertThat(saved.getContent()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(saved.getMetadata()).containsEntry("redacted", "true").containsEntry("redaction.categories",
                "AWS_KEY");
    }

    private static ToolContext context() {
        return ToolContext.builder().put(ObserveTool.WORKSPACE_KEY, WS).put(ObserveTool.OBSERVER_KEY, AGENT)
                .put(ObserveTool.SUBJECT_KEY, ALICE).build();
    }
}
