package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;

/**
 * L1 — the {@code Task} tool: forking a subagent from inside a turn.
 *
 * <p>
 * A fork is the one place where <b>two independent ReAct loops share one {@link ScriptedLlmClient}</b>, so the harness
 * has to be able to tell their calls apart. It cannot do that by trace id: a fork <em>inherits</em> the parent's, since
 * {@code SubagentLlmDefaults.effectiveMetadata} builds {@code component=<subagentName>, feature="subagent"} and then
 * calls {@code withDefaults(parentMetadata)}, which copies the parent's {@code traceId} across. The harness therefore
 * routes on {@code traceId + "#" + component} whenever {@code feature} is {@code "subagent"} — see
 * {@link ScriptedLlmClient#forkRoute}.
 *
 * <p>
 * {@link #forkCallsAreRoutedSeparatelyFromTheParent} is the test that holds that rule up: it scripts the parent and the
 * fork with <em>different</em> answers and requires each to receive its own. Were the routing ever to collapse back to
 * the bare trace id, the fork would consume the parent's next scripted response and the two loops would interleave.
 */
@DisplayName("RT-IT-L1: Task / subagent fork through an assembled runtime")
class SubagentTurnIntegrationTest {

    private static final String NODE = "agent-a";

    /** Registered in Java via {@code withCodeSubagentRegistry} — the layer a bootstrap owns. */
    private static final String WORKER = "it-worker";

    /** Defined only as {@code .aimon/agents/<name>.md} — never registered in Java. */
    private static final String MARKDOWN_WORKER = "it-markdown-worker";

    /** Defined in <b>both</b> layers, to pin which one wins. */
    private static final String SHADOWED = "it-shadowed";

    private static final String MARKDOWN_PROMPT = "You are the markdown-defined worker.";
    private static final String CODE_SHADOW_PROMPT = "You are the code-defined shadowing worker.";
    private static final String MARKDOWN_SHADOW_PROMPT = "You are the markdown file that must lose.";

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();

        // Seeded before newNode: DefaultSubagentRegistry loads every definition in its constructor, so a file written
        // afterwards would be invisible without an explicit reload.
        support.seedSubagent(NODE, MARKDOWN_WORKER,
                subagentMarkdown("Integration-test worker defined only in markdown", MARKDOWN_PROMPT));
        support.seedSubagent(NODE, SHADOWED,
                subagentMarkdown("Same name as a code-registered subagent", MARKDOWN_SHADOW_PROMPT));

        final InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
        codeSubagents.register(Subagent.builder().name(WORKER).description("Integration-test worker subagent")
                .systemPrompt("You are the integration-test worker.").maxIterations(4).build());
        codeSubagents.register(Subagent.builder().name(SHADOWED).description("Shadows a same-named markdown file")
                .systemPrompt(CODE_SHADOW_PROMPT).maxIterations(4).build());

        node = support.newNode(NODE, llm, OrcaRuntimeItSupport.options().codeSubagents(codeSubagents)
                .extraToolProvider(ContextProbeTool.provider()));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    /** Assembles an agents/*.md definition. The name comes from the file name, so only a description is needed. */
    private static String subagentMarkdown(String description, String systemPrompt) {
        return "---\ndescription: " + description + "\n---\n\n" + systemPrompt + "\n";
    }

    private static Map<String, Object> taskInput(String prompt) {
        return taskInput(WORKER, prompt);
    }

    private static Map<String, Object> taskInput(String subagentName, String prompt) {
        return Map.of("subagent_name", subagentName, "prompt", prompt, "description", "delegate a unit of work");
    }

    @Test
    @DisplayName("the fork really runs and its answer returns as the parent's observation")
    void forkResultReturnsToTheParentTurn() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", taskInput("do the work")),
                ScriptedLlmClient.text("delegated"));
        llm.script(ScriptedLlmClient.forkRoute(sessionId.value(), WORKER),
                ScriptedLlmClient.text("SENTINEL-FORK-2e58"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "delegate this");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("delegated");
        // The fork's answer must travel back as a tool observation — that is the entire point of Task.
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("SENTINEL-FORK-2e58"));
    }

    @Test
    @DisplayName("fork LLM calls route separately from the parent's, despite sharing the parent's trace id")
    void forkCallsAreRoutedSeparatelyFromTheParent() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", taskInput("work")),
                ScriptedLlmClient.text("PARENT-ANSWER"));
        llm.script(forkRoute, ScriptedLlmClient.text("FORK-ANSWER"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "delegate");

        // Each loop consumed only its own script. If the routing collapsed, the fork would have taken the parent's
        // second response ("PARENT-ANSWER") and the parent would have run out of script.
        assertThat(result.getFinalAnswer()).isEqualTo("PARENT-ANSWER");
        assertThat(llm.callCount(sessionId.value())).isEqualTo(2);
        assertThat(llm.callCount(forkRoute)).isEqualTo(1);
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("FORK-ANSWER"));

        // The fork's own metadata carries the distinguishing marks the routing relies on.
        assertThat(llm.lastCallFor(forkRoute).metadata().getFeature()).contains("subagent");
        assertThat(llm.lastCallFor(forkRoute).metadata().getComponent()).contains(WORKER);
        assertThat(llm.lastCallFor(forkRoute).metadata().getTraceId()).contains(sessionId.value());
    }

    @Test
    @DisplayName("the fork gets its own system prompt, not the parent agent's")
    void forkRunsUnderItsOwnSystemPrompt() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", taskInput("work")),
                ScriptedLlmClient.text("done"));
        llm.script(forkRoute, ScriptedLlmClient.text("ok"));

        node.run(sessionId, "delegate");

        assertThat(llm.lastCallFor(forkRoute).systemPrompt()).contains("You are the integration-test worker.");
        // And the parent's prompt is the agent's — the two must not be the same string.
        assertThat(llm.lastCallFor(sessionId.value()).systemPrompt()).contains("You are agent-a");
    }

    @Test
    @DisplayName("the fork's prompt carries the task text the parent passed")
    void forkReceivesTheParentsPrompt() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", taskInput("SENTINEL-PROMPT-9a41")),
                ScriptedLlmClient.text("done"));
        llm.script(forkRoute, ScriptedLlmClient.text("ok"));

        node.run(sessionId, "delegate");

        assertThat(llm.lastCallFor(forkRoute).allText()).contains("SENTINEL-PROMPT-9a41");
    }

    /**
     * The markdown layer is the one every other test here bypasses by registering subagents in Java, so nothing else in
     * this file exercises {@code VfsSubagentRepository} enumerating {@code .aimon/agents} or
     * {@code MarkdownSubagentParser} reading the frontmatter. A regression in either is invisible to a code-registered
     * fixture — the same reason {@code SkillTurnIntegrationTest} seeds SKILL.md files rather than building
     * {@code Skill} objects.
     */
    @Test
    @DisplayName("a subagent defined only as agents/*.md is discovered and forkable")
    void markdownDefinedSubagentIsDiscoveredAndForkable() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), MARKDOWN_WORKER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", taskInput(MARKDOWN_WORKER, "do the work")),
                ScriptedLlmClient.text("delegated to markdown"));
        llm.script(forkRoute, ScriptedLlmClient.text("SENTINEL-MARKDOWN-FORK-8b30"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "delegate to the markdown worker");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("delegated to markdown");
        // The body below the frontmatter became the fork's system prompt, so the file really was parsed rather than
        // merely listed.
        assertThat(llm.lastCallFor(forkRoute).systemPrompt()).contains(MARKDOWN_PROMPT);
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("SENTINEL-MARKDOWN-FORK-8b30"));
    }

    /**
     * The composite registry places the code layer <b>last</b> and resolves later layers first, so a code-registered
     * subagent shadows a same-named markdown file. That is the opposite of how skills and commands resolve, which is
     * exactly why it is worth pinning: the asymmetry is deliberate but easy to "fix" into a collision.
     */
    @Test
    @DisplayName("a code-registered subagent shadows a same-named markdown file")
    void codeDefinedSubagentShadowsASameNamedMarkdownFile() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), SHADOWED);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", taskInput(SHADOWED, "work")),
                ScriptedLlmClient.text("done"));
        llm.script(forkRoute, ScriptedLlmClient.text("ok"));

        node.run(sessionId, "delegate to the shadowed name");

        assertThat(llm.lastCallFor(forkRoute).systemPrompt()).contains(CODE_SHADOW_PROMPT)
                .doesNotContain(MARKDOWN_SHADOW_PROMPT);
    }

    @Test
    @DisplayName("an unknown subagent name is an observation, not a turn failure")
    void unknownSubagentIsObservedNotFatal() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("Task",
                        Map.of("subagent_name", "no-such-worker", "prompt", "x", "description", "y")),
                ScriptedLlmClient.text("recovered"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "delegate to a ghost");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("recovered");
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("Subagent not found: no-such-worker"));
    }

    @Test
    @DisplayName("a fork that fails does not kill the parent turn")
    void forkFailureDoesNotKillTheParentTurn() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", taskInput("work")),
                ScriptedLlmClient.text("carried on"));
        // The fork's very first LLM call blows up. The parent must still finish its own turn.
        llm.scriptDynamic(forkRoute, call -> {
            throw new IllegalStateException("SENTINEL-FORK-BOOM");
        });

        final OrcaAgentExecutionResult result = node.run(sessionId, "delegate to a doomed worker");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("carried on");
        // The failure is reported to the parent rather than swallowed — otherwise the model would believe the work
        // succeeded and build on a result that never existed.
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("SENTINEL-FORK-BOOM"));
    }
}
