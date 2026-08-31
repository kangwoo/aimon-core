package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolContextEnrichmentInfo;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.todo.InMemoryTodoRepository;
import at.aimon.core.tools.todo.Todo;
import at.aimon.core.tools.todo.TodoWriteTool;

/**
 * Guards the resume branch of {@link DefaultSubagentExecutor#execute}: what happens when a request carries a previous
 * snapshot.
 *
 * <p>
 * The branch is small and easy to "simplify" away, and its two halves are what make a suspended fork's identity
 * survive a restart (design &sect;6-4). A fresh run mints an {@link ExecutionId} and writes it into the transcript
 * label; a resumed run reads that label back out with {@link ExecutionId#of(String)} instead of minting a second one.
 * The round trip has to be identity-preserving, because per-run tool state — the todo list is the concrete case — is
 * partitioned on that id, so a resume that minted a new id would silently start from an empty bucket rather than the
 * one the earlier run filled.
 *
 * <p>
 * Everything here drives the executor through its public surface ({@code execute} plus what the run publishes to its
 * tools and returns on its result), so it does not pin the private shape of the branch, only the behaviour that has to
 * hold whatever that shape becomes.
 */
@DisplayName("DefaultSubagentExecutor resume (previous snapshot) branch")
class DefaultSubagentExecutorResumeTest {

    private static final String SUBAGENT_NAME = "resumer";
    private static final String BASE_PROMPT = "you are " + SUBAGENT_NAME;

    // ── identity round trip ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a resumed run keeps the execution id of the run it continues")
    void resumeKeepsTheExecutionIdOfTheRunItContinues() {
        final ProbeTool firstProbe = new ProbeTool();
        final SubagentExecutionResult first = runWithProbe(firstProbe, null, null);
        final ExecutionId firstId = capturedExecutionId(firstProbe);

        // The fresh run minted its own id; the label it wrote is what the snapshot now carries.
        assertThat(firstId.value()).startsWith("subagent:" + SUBAGENT_NAME + ":");
        assertThat(first.getSnapshot().getSessionId().value()).isEqualTo(firstId.value());

        final ProbeTool resumedProbe = new ProbeTool();
        runWithProbe(resumedProbe, first.getSnapshot(), null);

        assertThat(capturedExecutionId(resumedProbe))
                .as("generate -> transcript label -> snapshot -> ExecutionId.of must round trip unchanged")
                .isEqualTo(firstId);
    }

    @Test
    @DisplayName("the resumed id is read verbatim out of the snapshot label, not re-derived from the subagent name")
    void resumeReadsTheIdOutOfTheSnapshotLabelVerbatim() {
        // A label that no generate() call could have produced: if the branch minted instead of reading, the assertion
        // below would see a fresh "subagent:resumer:<uuid>".
        final SessionId label = SessionId.of("subagent:resumer:label-from-an-earlier-node");
        final SessionSnapshot snapshot = SessionSnapshot.of(label, BASE_PROMPT, List.of());

        final ProbeTool probe = new ProbeTool();
        runWithProbe(probe, snapshot, null);

        assertThat(capturedExecutionId(probe)).isEqualTo(ExecutionId.of(label.value()));
    }

    @Test
    @DisplayName("two fresh runs get distinct ids while a resume gets the earlier one back")
    void freshRunsGetDistinctIdsWhileAResumeGetsTheOldOne() {
        final ProbeTool probeA = new ProbeTool();
        final ProbeTool probeB = new ProbeTool();
        final SubagentExecutionResult runA = runWithProbe(probeA, null, null);
        runWithProbe(probeB, null, null);

        final ExecutionId idA = capturedExecutionId(probeA);
        final ExecutionId idB = capturedExecutionId(probeB);
        assertThat(idA).as("concurrent forks of one subagent must not share a run bucket").isNotEqualTo(idB);

        final ProbeTool resumedProbe = new ProbeTool();
        runWithProbe(resumedProbe, runA.getSnapshot(), null);

        assertThat(capturedExecutionId(resumedProbe)).isEqualTo(idA).isNotEqualTo(idB);
    }

    @Test
    @DisplayName("the identity survives repeated suspend/resume cycles, not just the first one")
    void identitySurvivesRepeatedResumes() {
        final ProbeTool firstProbe = new ProbeTool();
        final SubagentExecutionResult first = runWithProbe(firstProbe, null, null);

        final ProbeTool secondProbe = new ProbeTool();
        final SubagentExecutionResult second = runWithProbe(secondProbe, first.getSnapshot(), null);

        final ProbeTool thirdProbe = new ProbeTool();
        final SubagentExecutionResult third = runWithProbe(thirdProbe, second.getSnapshot(), null);

        final ExecutionId firstId = capturedExecutionId(firstProbe);
        assertThat(capturedExecutionId(secondProbe)).isEqualTo(firstId);
        assertThat(capturedExecutionId(thirdProbe)).isEqualTo(firstId);
        // The label must be written back out on every hop, or the third resume would have nothing to read.
        assertThat(third.getSnapshot().getSessionId()).isEqualTo(first.getSnapshot().getSessionId());
    }

    // ── transcript continuity ───────────────────────────────────────────────────

    @Test
    @DisplayName("a resumed run continues the snapshot's transcript instead of starting a fresh buffer")
    void resumedRunContinuesTheSnapshotTranscript() {
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("subagent:resumer:prior-run"), BASE_PROMPT,
                List.of(Message.user("what is 2 plus 2"), Message.assistant("4")));

        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.responses.add(LlmResponse.text("12"));

        final SubagentExecutionResult result = execute(llm, new DefaultToolRegistry(), List.of(), snapshot, null,
                "and times 3?");

        assertThat(result.isSuccess()).isTrue();
        assertThat(llm.seenMessages).hasSize(1);
        assertThat(llm.seenMessages.get(0)).extracting(Message::getContent)
                .as("the resumed run must send the earlier exchange to the model, then the new goal")
                .containsSubsequence("what is 2 plus 2", "4", "and times 3?");
        assertThat(result.getConversationHistory()).extracting(Message::getContent)
                .containsSubsequence("what is 2 plus 2", "4", "and times 3?", "12");
    }

    @Test
    @DisplayName("a fresh run starts from an empty transcript (the contrast that makes the resume assertion mean something)")
    void freshRunStartsFromAnEmptyTranscript() {
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.responses.add(LlmResponse.text("12"));

        final SubagentExecutionResult result = execute(llm, new DefaultToolRegistry(), List.of(), null, null,
                "and times 3?");

        assertThat(result.isSuccess()).isTrue();
        assertThat(llm.seenMessages.get(0)).extracting(Message::getContent).doesNotContain("what is 2 plus 2", "4");
        assertThat(result.getConversationHistory()).extracting(Message::getContent).doesNotContain("what is 2 plus 2");
    }

    @Test
    @DisplayName("a resumed run rebuilds its system prompt rather than replaying the snapshot's stale one")
    void resumedRunRebuildsItsSystemPromptInsteadOfReplayingTheStaleOne() {
        // TranscriptBuffer.fromSnapshot carries the snapshot's system prompt over, and toSnapshot writes back whatever
        // the buffer ends up holding — so without the overwrite the stale prompt would be persisted again on every
        // resume and never age out, while the model was being sent the freshly built one. The two would drift apart.
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("subagent:resumer:prior-run"),
                "STALE PROMPT FROM AN OLDER BUILD", List.of(Message.user("earlier"), Message.assistant("ok")));

        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = execute(llm, new DefaultToolRegistry(), List.of(), snapshot, null,
                "carry on");

        assertThat(result.getSnapshot().getSystemPrompt()).contains(BASE_PROMPT)
                .doesNotContain("STALE PROMPT FROM AN OLDER BUILD");
        assertThat(llm.seenSystemPrompts.get(0)).contains(BASE_PROMPT)
                .doesNotContain("STALE PROMPT FROM AN OLDER BUILD");
    }

    // ── what the id is actually for: per-run tool state ─────────────────────────

    @Test
    @DisplayName("per-run todo state lands in the same bucket across a suspend/resume")
    void perRunTodoStateSurvivesTheResume() {
        final InMemoryTodoRepository repository = new InMemoryTodoRepository();

        final SubagentExecutionResult first = runWithTodoWrite(repository, null, "step one");
        assertThat(repository.size()).isEqualTo(1);

        runWithTodoWrite(repository, first.getSnapshot(), "step two");

        assertThat(repository.size()).as("a resume must reuse the earlier run's todo bucket, not open a second one")
                .isEqualTo(1);
        final List<Todo> resumedTodos = repository.get(first.getSnapshot().getSessionId().value()).orElseThrow();
        assertThat(resumedTodos).extracting(Todo::getContent).containsExactly("step two");
    }

    @Test
    @DisplayName("two fresh runs keep separate todo buckets")
    void twoFreshRunsKeepSeparateTodoBuckets() {
        final InMemoryTodoRepository repository = new InMemoryTodoRepository();

        runWithTodoWrite(repository, null, "step one");
        runWithTodoWrite(repository, null, "step two");

        assertThat(repository.size()).isEqualTo(2);
    }

    // ── identity claims the resume must NOT acquire ─────────────────────────────

    @Test
    @DisplayName("a resumed fork is still nobody's session and follows the resumer's invoking session")
    void resumedForkIsStillSessionlessAndFollowsTheResumer() {
        final SessionId originalInvoker = SessionId.generate();
        final SessionId resumingInvoker = SessionId.generate();

        final ProbeTool firstProbe = new ProbeTool();
        final SubagentExecutionResult first = runWithProbe(firstProbe, null, originalInvoker);

        final ProbeTool resumedProbe = new ProbeTool();
        runWithProbe(resumedProbe, first.getSnapshot(), resumingInvoker);

        final ToolContext captured = resumedProbe.captured.get();
        assertThat(captured.get(ToolContextKeys.SESSION_ID))
                .as("resuming restores a run identity, never a session identity").isEmpty();
        assertThat(captured.get(ToolContextKeys.INVOKING_SESSION_ID))
                .as("the decisions that apply on a resume are the resumer's").contains(resumingInvoker);
        assertThat(capturedExecutionId(resumedProbe).value()).isNotEqualTo(resumingInvoker.value())
                .isNotEqualTo(originalInvoker.value());
    }

    @Test
    @DisplayName("an enricher on a resumed run is handed the continued run id and no session id")
    void enricherOnAResumedRunSeesTheContinuedRunId() {
        final CapturingEnricher firstEnricher = new CapturingEnricher();
        final SubagentExecutionResult first = execute(scripted("done"), new DefaultToolRegistry(),
                List.of(firstEnricher), null, null, "go");

        final CapturingEnricher resumedEnricher = new CapturingEnricher();
        execute(scripted("done"), new DefaultToolRegistry(), List.of(resumedEnricher), first.getSnapshot(), null, "go");

        assertThat(firstEnricher.info).as("the fresh run must have reached its enricher first").isNotNull();
        assertThat(resumedEnricher.info).isNotNull();
        assertThat(resumedEnricher.info.getExecutionId()).isEqualTo(firstEnricher.info.getExecutionId());
        assertThat(resumedEnricher.info.getSessionId()).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Runs one execution whose single tool call is the probe, so the assembled {@link ToolContext} is observable. */
    private SubagentExecutionResult runWithProbe(ProbeTool probe, SessionSnapshot previousSnapshot,
            SessionId invokingSessionId) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(probe);

        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", ProbeTool.TOOL_NAME, Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        return execute(llm, registry, List.of(), previousSnapshot, invokingSessionId, "probe it");
    }

    /** Runs one execution whose single tool call writes {@code todoContent} through the real {@link TodoWriteTool}. */
    private SubagentExecutionResult runWithTodoWrite(InMemoryTodoRepository repository,
            SessionSnapshot previousSnapshot, String todoContent) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new TodoWriteTool(repository));

        final Map<String, Object> todos = Map.of("todos",
                List.of(Map.of("content", todoContent, "status", "in_progress", "activeForm", "doing " + todoContent)));
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", TodoWriteTool.TOOL_NAME, todos))));
        llm.responses.add(LlmResponse.text("done"));

        return execute(llm, registry, List.of(), previousSnapshot, null, "plan it");
    }

    private SubagentExecutionResult execute(ScriptedLlmClient llm, ToolRegistry toolRegistry,
            List<ToolContextEnricher> enrichers, SessionSnapshot previousSnapshot, SessionId invokingSessionId,
            String goal) {
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(toolRegistry)
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .toolContextEnrichers(enrichers).parentCancellationSignal(NoopCancellationSignal.INSTANCE).build();

        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal(goal)
                .invokingSessionId(invokingSessionId).previousSnapshot(previousSnapshot).build();

        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager())
                .execute(context, request);
    }

    private static ScriptedLlmClient scripted(String finalAnswer) {
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.responses.add(LlmResponse.text(finalAnswer));
        return llm;
    }

    private static ExecutionId capturedExecutionId(ProbeTool probe) {
        assertThat(probe.captured.get()).as("the probe tool must have run").isNotNull();
        return probe.captured.get().get(ToolContextKeys.EXECUTION_ID).orElseThrow();
    }

    private Subagent subagent() {
        return Subagent.of(SUBAGENT_NAME, SubagentMetadata.builder().description("d").maxIterations(5).build(),
                SubagentContent.of(BASE_PROMPT));
    }

    /** Captures the assembled {@link ToolContext} — the only way to observe the ids the run publishes to its tools. */
    private static final class ProbeTool extends AbstractTool {
        private static final String TOOL_NAME = "Probe";

        private final AtomicReference<ToolContext> captured = new AtomicReference<>();

        ProbeTool() {
            super(TOOL_NAME, "captures the tool context", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            captured.set(context);
            return ToolResult.success("ok");
        }
    }

    /** Records the enrichment info handed to it; the executor builds exactly one per execution. */
    private static final class CapturingEnricher implements ToolContextEnricher {
        private ToolContextEnrichmentInfo info;

        @Override
        public void enrich(ToolContext.Builder builder, ToolContextEnrichmentInfo info) {
            this.info = info;
        }
    }

    /** Scriptable LLM client that also records the system prompt and message list of every call it served. */
    private static final class ScriptedLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private final List<String> seenSystemPrompts = new ArrayList<>();
        private final List<List<Message>> seenMessages = new ArrayList<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            seenSystemPrompts.add(systemPrompt);
            seenMessages.add(List.copyOf(messages));
            return responses.isEmpty() ? LlmResponse.text("done") : responses.poll();
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }

    }
}
