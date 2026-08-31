package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
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
 * L1 — the {@code Skill} tool: markdown skills discovered from the node's file system.
 *
 * <p>
 * Unlike every other L1 group, nothing here is registered in Java: the skills are <b>SKILL.md files seeded under the
 * node's root</b> before the runtime is built, so a passing test proves the whole chain — {@code VfsSkillRepository}
 * enumerating {@code .aimon/skills}, {@code MarkdownSkillParser} reading the frontmatter, and
 * {@code OrcaSkillToolProvider} wiring the registry into the tool. A unit test with a hand-built {@code Skill} object
 * proves none of that.
 *
 * <p>
 * Two properties here are load-bearing beyond "the tool works":
 *
 * <ul>
 * <li>{@link #identityVariablesRenderPerSessionAndPerAgent} pins that {@code ${AIMON_SESSION_ID}} and
 * {@code ${AIMON_AGENT_RUNTIME_ID}} are <em>different axes</em> — the runtime id is agent-scoped and identical across
 * sessions, so a skill body using it as a uniqueness discriminator would collide between concurrent sessions. This is
 * the misnomer called out in {@code docs/overview/scope-model.md} §6, asserted rather than merely documented.
 * <li>{@link #modelInvisibleSkillIsIndistinguishableFromAMissingOne} pins that {@code invoke.model: false} is reported
 * as "not found", not as "denied". The conflation is deliberate — a distinct message would let the model discover that
 * a hidden skill exists by probing names.
 * </ul>
 *
 * <p>
 * Skill <b>approval</b> policy is not covered here. The assembled runtime wires
 * {@code AlwaysAllowSkillInvocationPolicy} unless a bootstrap supplies one, so an approval test would have to inject
 * its own policy plus the session/agent approval stores — session-scoped state, which is L2's subject rather than this
 * file's.
 */
@DisplayName("RT-IT-L1: Skill through an assembled runtime")
class SkillTurnIntegrationTest {

    private static final String NODE = "agent-a";
    private static final String WORKER = "it-worker";

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();

        // Seeded before newNode so every skill is on disk by the time anything looks. DefaultSkillRegistry loads
        // lazily, so this is not strictly required for skills — but the subagent registry next door loads eagerly and
        // uniform ordering keeps the two from drifting apart.
        support.seedSkill(NODE, "it-report",
                skill("it-report", "Emits a fixed integration-test sentinel", "", "Report the finding: BODY-4a17"));
        support.seedSkill(NODE, "it-greet", skill("it-greet", "Substitutes argument placeholders", "",
                "first=$1\nsecond=$2\nall=$ARGUMENTS\ncount=$ARG_COUNT"));
        support.seedSkill(NODE, "it-ids", skill("it-ids", "Renders the runtime identity variables", "",
                "session=${AIMON_SESSION_ID}\nruntime=${AIMON_AGENT_RUNTIME_ID}\nexecution=${AIMON_EXECUTION_ID}"));
        support.seedSkill(NODE, "it-hidden",
                skill("it-hidden", "Never offered to the model", "invoke:\n  model: false\n", "HIDDEN-BODY-9d34"));
        support.seedSkill(NODE, "it-forked", skill("it-forked", "Runs its body in a subagent",
                "execution:\n  mode: fork\n  agent: " + WORKER + "\n", "FORKED-BODY-2b58"));
        support.seedSkill(NODE, "it-forked-ghost", skill("it-forked-ghost", "Names a subagent that does not exist",
                "execution:\n  mode: fork\n  agent: no-such-worker\n", "unreachable"));

        final InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
        codeSubagents.register(Subagent.builder().name(WORKER).description("Integration-test worker subagent")
                .systemPrompt("You are the integration-test worker.").maxIterations(4).build());

        node = support.newNode(NODE, llm, OrcaRuntimeItSupport.options().codeSubagents(codeSubagents));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    /** Assembles a SKILL.md. {@code extraFrontmatter} goes after the two mandatory fields and may be empty. */
    private static String skill(String name, String description, String extraFrontmatter, String body) {
        return "---\nname: " + name + "\ndescription: " + description + "\n" + extraFrontmatter + "---\n\n" + body
                + "\n";
    }

    private static Map<String, Object> invoke(String skillName) {
        return Map.of("skill", skillName);
    }

    private static Map<String, Object> invoke(String skillName, String args) {
        return Map.of("skill", skillName, "args", args);
    }

    @Test
    @DisplayName("a seeded SKILL.md is discovered, activated, and its body returns as an observation")
    void seededSkillIsDiscoveredAndItsBodyRendered() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Skill", invoke("it-report")),
                ScriptedLlmClient.text("followed the skill"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "use the report skill");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("followed the skill");
        final String observation = skillObservation(sessionId);
        assertThat(observation).contains("=== Skill Activated ===").contains("Skill: it-report")
                .contains("Report the finding: BODY-4a17");
    }

    @Test
    @DisplayName("the model is offered the discovered skills in the tool description")
    void discoveredSkillsAreAdvertisedToTheModel() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("nothing to do"));

        node.run(sessionId, "hello");

        // The description is built per call from the registry, so this is the model's only route to the skill list.
        assertThat(skillToolDescription(sessionId)).contains("<available_skills>").contains("it-report")
                .contains("it-greet");
    }

    @Test
    @DisplayName("args are substituted into the placeholders the body declares")
    void argsAreSubstitutedIntoPlaceholders() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Skill", invoke("it-greet", "alpha 'beta gamma'")),
                ScriptedLlmClient.text("greeted"));

        node.run(sessionId, "greet with arguments");

        final String observation = skillObservation(sessionId);
        // Shell quoting is honoured by ShellArgumentTokenizer: the quoted pair is one positional argument, while
        // $ARGUMENTS stays the raw string the model supplied.
        assertThat(lineValue(observation, "first")).isEqualTo("alpha");
        assertThat(lineValue(observation, "second")).isEqualTo("beta gamma");
        assertThat(lineValue(observation, "all")).isEqualTo("alpha 'beta gamma'");
        assertThat(lineValue(observation, "count")).isEqualTo("2");
    }

    @Test
    @DisplayName("args for a body with no placeholders are appended as a trailer rather than dropped")
    void argsWithoutPlaceholdersAreAppended() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Skill", invoke("it-report", "ARGS-7f02")),
                ScriptedLlmClient.text("done"));

        node.run(sessionId, "pass args to a skill that declares none");

        // Silently dropping them would leave the model believing its arguments reached the skill body.
        assertThat(skillObservation(sessionId)).contains("ARGUMENTS: ARGS-7f02");
    }

    @Test
    @DisplayName("${AIMON_SESSION_ID} is per-session while ${AIMON_AGENT_RUNTIME_ID} is shared by the agent")
    void identityVariablesRenderPerSessionAndPerAgent() {
        final SessionId first = OrcaRuntimeItSupport.newSession();
        final SessionId second = OrcaRuntimeItSupport.newSession();
        llm.script(first.value(), ScriptedLlmClient.callTool("Skill", invoke("it-ids")),
                ScriptedLlmClient.text("first"));
        llm.script(second.value(), ScriptedLlmClient.callTool("Skill", invoke("it-ids")),
                ScriptedLlmClient.text("second"));

        node.run(first, "render ids");
        node.run(second, "render ids");

        final String firstObservation = skillObservation(first);
        final String secondObservation = skillObservation(second);

        assertThat(lineValue(firstObservation, "session")).isEqualTo(first.value());
        assertThat(lineValue(secondObservation, "session")).isEqualTo(second.value());
        // Same agent, so the same runtime id in both — which is exactly why a body must not use it to keep two
        // concurrent sessions' scratch space apart.
        assertThat(lineValue(firstObservation, "runtime")).isEqualTo("agent:" + NODE);
        assertThat(lineValue(secondObservation, "runtime")).isEqualTo("agent:" + NODE);
        // A turn is a session's, so it has no execution identity: the variable renders empty rather than falling back
        // to the session id.
        assertThat(lineValue(firstObservation, "execution")).isEmpty();
    }

    @Test
    @DisplayName("an unknown skill name is an observation, not a turn failure")
    void unknownSkillIsObservedNotFatal() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Skill", invoke("no-such-skill")),
                ScriptedLlmClient.text("recovered"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "use a skill that was never seeded");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("recovered");
        assertThat(skillObservation(sessionId)).contains("Skill not found: 'no-such-skill'")
                .contains("Available skills:");
    }

    @Test
    @DisplayName("a model-invisible skill is reported as missing, and never named in the available list")
    void modelInvisibleSkillIsIndistinguishableFromAMissingOne() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Skill", invoke("it-hidden")),
                ScriptedLlmClient.text("moved on"));

        node.run(sessionId, "call the hidden skill by name");

        final String observation = skillObservation(sessionId);
        assertThat(observation).contains("Skill not found: 'it-hidden'");
        // The body must not leak, and neither must the fact that the skill exists — so the available-skills list in
        // the very message denying it must not name it either.
        assertThat(observation).doesNotContain("HIDDEN-BODY-9d34");
        // The marker's presence is asserted before slicing on it. Without this line a denial that stopped listing the
        // alternatives would make indexOf return -1 and substring throw StringIndexOutOfBoundsException — an error
        // about this test's own arithmetic, in place of the assertion failure that says what actually regressed.
        assertThat(observation).contains("Available skills:");
        assertThat(observation.substring(observation.indexOf("Available skills:"))).doesNotContain("it-hidden");
        assertThat(skillToolDescription(sessionId)).doesNotContain("it-hidden");
    }

    @Test
    @DisplayName("a fork-mode skill runs its rendered body in the named subagent")
    void forkModeSkillRunsThroughItsSubagent() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), WORKER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Skill", invoke("it-forked")),
                ScriptedLlmClient.text("fork consumed"));
        llm.script(forkRoute, ScriptedLlmClient.text("SENTINEL-SKILL-FORK-5c19"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "run the forked skill");

        assertThat(result.isSuccess()).isTrue();
        // The rendered body is the fork's goal — that is what makes a fork-mode skill equivalent to an explicit
        // Task(subagent_name=..., prompt=<rendered body>) call.
        assertThat(llm.lastCallFor(forkRoute).allText()).anyMatch(text -> text.contains("FORKED-BODY-2b58"));
        assertThat(skillObservation(sessionId)).contains("=== Skill Forked ===").contains("Agent: " + WORKER)
                .contains("SENTINEL-SKILL-FORK-5c19");
    }

    @Test
    @DisplayName("a fork-mode skill naming an unknown subagent fails before forking, as an observation")
    void forkModeSkillWithUnknownSubagentIsObserved() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Skill", invoke("it-forked-ghost")),
                ScriptedLlmClient.text("recovered"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "run a skill bound to a ghost");

        assertThat(result.isSuccess()).isTrue();
        assertThat(skillObservation(sessionId)).contains("Skill fork failed for 'it-forked-ghost'")
                .contains("unknown subagent 'no-such-worker'");
    }

    /** The observation the {@code Skill} call produced in {@code sessionId}'s most recent turn. */
    private String skillObservation(SessionId sessionId) {
        return llm.lastCallFor(sessionId.value()).lastObservation();
    }

    /** The {@code Skill} tool's description as the model saw it on {@code sessionId}'s most recent call. */
    private String skillToolDescription(SessionId sessionId) {
        final List<String> descriptions = llm.lastCallFor(sessionId.value()).tools().stream()
                .filter(definition -> "Skill".equals(definition.getName()))
                .map(definition -> definition.getDescription()).toList();
        assertThat(descriptions).as("the Skill tool must be registered exactly once").hasSize(1);
        return descriptions.get(0);
    }

    /**
     * Reads {@code <key>=<value>} out of a rendered skill body.
     *
     * @throws AssertionError
     *             when no such line exists — a missing line means the placeholder never rendered, which must not be
     *             read as "it rendered empty"
     */
    private static String lineValue(String observation, String key) {
        for (String line : observation.split("\n")) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        throw new AssertionError("no '" + key + "=' line in observation: " + observation);
    }
}
