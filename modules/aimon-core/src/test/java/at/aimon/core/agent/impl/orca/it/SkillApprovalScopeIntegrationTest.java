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
import at.aimon.core.skill.policy.RuleBasedSkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.agent.ApprovalCachingSkillInvocationPolicy;
import at.aimon.core.skill.policy.agent.InMemoryAgentApprovalStore;
import at.aimon.core.skill.policy.session.InMemorySessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionScopedSkillInvocationPolicy;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;

/**
 * L2 — which sessions a skill approval reaches: the session it was given in, every session of the agent it was given
 * to, and the fork spawned by a session that has one.
 *
 * <p>
 * The scope model states the reach rules ({@code docs/overview/scope-model.md} §6): a decision in the
 * {@code AgentApprovalStore} applies to every session of that agent, a decision in the {@code SessionApprovalStore}
 * applies to one session and to the executions it delegates, and the narrow store is consulted first. Those are three
 * separate claims about a two-layer chain, and none of them is asserted anywhere below this file — the unit tests for
 * each policy see only their own layer.
 *
 * <h2>The fixture is the test</h2>
 *
 * <p>
 * Two configuration mistakes make this whole class vacuous, so both are pinned deliberately rather than left to
 * default.
 *
 * <p>
 * The first is the harness default. An assembled runtime with no policy supplied wires
 * {@code AlwaysAllowSkillInvocationPolicy}, under which every "the grant worked" assertion passes with both approval
 * stores deleted. Every node here therefore supplies its own chain, and every ALLOW assertion is paired with a
 * same-fixture negative control — a second session, a second agent, or an ungranted fork — that must show the refusal.
 *
 * <p>
 * The second is subtler and lives inside the base rule. {@code RuleBasedSkillInvocationPolicy} defaults
 * {@code safeByDefault} to <b>true</b>, and its notion of a safe skill is "inline execution, no hooks" — which is
 * exactly what an ordinary seeded {@code SKILL.md} is. A grant test built on {@code builder().build()} would allow the
 * skill through the base rule and stay green with the session store removed. Hence
 * {@link #denyingBase()}: {@code safeByDefault(false)} plus an explicit {@code DENY}, so ALLOW can only have come from
 * a store.
 *
 * <h2>What a refusal looks like, and what it does not</h2>
 *
 * <p>
 * A rejected skill is a {@code ToolResult.error} — an observation handed back to the model — not a failed turn. So
 * {@code result.isSuccess()} is {@code true} on a denial and asserting it proves nothing; the assertions here read the
 * observation text the model actually received, and check both that the refusal is there and that the skill body is
 * not.
 *
 * <h2>Out of reach from here</h2>
 *
 * <p>
 * {@code ASK} is observable only as {@code SkillTool}'s distinct message ({@link #askIsReportedAsAwaitingApproval}).
 * The suspend-and-resume path behind it — {@code SkillPreflightScanner}, {@code PendingTurnRegistry},
 * {@code SkillApprovalChannel} — needs an executor built through {@code OrcaAgentExecutorFactory}, which is not how
 * this suite assembles nodes. No test here should be read as a claim that ASK suspends a turn.
 */
@DisplayName("RT-IT-L2: skill approval scope — session, agent, and the reach a fork inherits")
class SkillApprovalScopeIntegrationTest {

    private static final String NODE = "agent-a";
    private static final String OTHER_NODE = "agent-b";
    private static final String WORKER = "it-worker";
    private static final String SKILL = "it-guarded";

    /** Only the renderer can put this in an observation, which is what makes it a stronger signal than the header. */
    private static final String BODY = "GUARDED-BODY-8c31";

    private static final String DENIED = "Skill invocation denied by policy: '" + SKILL + "'.";
    private static final String NEEDS_APPROVAL = "Skill '" + SKILL
            + "' requires user approval, but no approval channel is available in this context.";

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private InMemorySessionApprovalStore sessions;
    private InMemoryAgentApprovalStore agents;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        sessions = new InMemorySessionApprovalStore();
        agents = new InMemoryAgentApprovalStore();

        support.seedSkill(NODE, SKILL, guardedSkill());
        node = support.newNode(NODE, llm,
                OrcaRuntimeItSupport.options().skillInvocationPolicy(chain(sessions, agents, denyingBase())));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private static String guardedSkill() {
        return "---\nname: " + SKILL + "\ndescription: Guarded integration-test skill\n---\n\n" + BODY + "\n";
    }

    /**
     * The production chain, narrowest scope outermost: session store, then agent store, then the base rule. Assembling
     * it here rather than in the harness keeps the order visible in the test that depends on it — see
     * {@link #aSessionRefusalOverridesAnAgentWideGrant}, which is the test that fails if the two layers are swapped.
     */
    private static SkillInvocationPolicy chain(InMemorySessionApprovalStore sessionStore,
            InMemoryAgentApprovalStore agentStore, SkillInvocationPolicy base) {
        return new SessionScopedSkillInvocationPolicy(sessionStore,
                new ApprovalCachingSkillInvocationPolicy(agentStore, base));
    }

    /**
     * Refuses everything a store has not spoken for. {@code safeByDefault(false)} is load-bearing: left at its default
     * of {@code true}, an inline hook-free skill — which every skill in this file is — is classified safe and allowed
     * before either store is consulted, and every grant assertion in this class would pass with the stores deleted.
     */
    private static SkillInvocationPolicy denyingBase() {
        return RuleBasedSkillInvocationPolicy.builder().safeByDefault(false)
                .defaultDecision(SkillInvocationDecision.DENY).build();
    }

    /**
     * Scripts one {@code Skill} call and the answer that follows it. The trailing message is not optional: a refusal
     * still consumes the iteration and the model is called again with the error observation, so a one-entry script
     * would exhaust.
     */
    private void scriptSkillCall(String route) {
        llm.script(route, ScriptedLlmClient.callTool("Skill", Map.of("skill", SKILL)),
                ScriptedLlmClient.text("acknowledged"));
    }

    /** The observation the model saw for the {@code Skill} call — the vantage point every assertion here uses. */
    private String skillObservation(SessionId sessionId) {
        return llm.lastCallFor(sessionId.value()).lastObservation();
    }

    private String forkObservation(String route) {
        assertThat(llm.callsFor(route)).as("fork route %s ran no call", route).isNotEmpty();
        return llm.callsFor(route).get(llm.callsFor(route).size() - 1).lastObservation();
    }

    private static InMemorySubagentRegistry workerRegistry() {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        // No allowedTools: an empty allow-list disables permission validation, so the fork can reach Skill and any
        // refusal it reports is the approval policy's rather than the tool gate's.
        registry.register(Subagent.builder().name(WORKER).description("Delegate that invokes the guarded skill")
                .systemPrompt("Invoke the guarded skill.").build());
        return registry;
    }

    /**
     * The gate exists and sits before rendering. Delete it and the observation carries the skill body instead of the
     * refusal, so this is also the test that gives every {@code contains(BODY)} assertion below its meaning.
     */
    @Test
    @DisplayName("a skill nobody approved is refused as an observation and its body never renders")
    void aDeniedSkillIsRefusedAndItsBodyNeverRenders() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptSkillCall(sessionId.value());

        final OrcaAgentExecutionResult result = node.run(sessionId, "use the guarded skill");

        assertThat(skillObservation(sessionId)).isEqualTo(DENIED);
        assertThat(skillObservation(sessionId)).doesNotContain(BODY).doesNotContain("=== Skill Activated ===");
        // A refusal is a turn the model finishes, not a failed turn — which is why no assertion here reads isSuccess.
        assertThat(result.isSuccess()).isTrue();
    }

    /**
     * A grant given in one session applies there and nowhere else. The second session is what makes the
     * {@code SessionId} key load-bearing: a store that ignored its key, or a policy that cached the last answer it
     * gave, would let it through and fail here.
     */
    @Test
    @DisplayName("a session-scoped grant allows that session and no other")
    void aSessionGrantAllowsThatSessionAndNoOther() {
        final SessionId granted = OrcaRuntimeItSupport.session("granted");
        final SessionId other = OrcaRuntimeItSupport.session("other");
        sessions.put(granted, SKILL, SkillInvocationDecision.ALLOW);
        scriptSkillCall(granted.value());
        scriptSkillCall(other.value());

        node.run(granted, "use the guarded skill");
        node.run(other, "use the guarded skill");

        assertThat(skillObservation(granted)).contains("=== Skill Activated ===").contains(BODY);
        assertThat(skillObservation(other)).isEqualTo(DENIED);
    }

    /**
     * The agent store spans sessions and stops at the agent. Both halves need each other: the two sessions of
     * {@code agent-a} prove the reach is wider than a session, and {@code agent-b} — reading the <b>same store
     * instance</b> — proves the key is the runtime id rather than a global yes.
     *
     * <p>
     * The id is read from the runtime ({@code node.runtime().getId()}) rather than spelled out as
     * {@code agent:agent-a}. A hardcoded string would keep passing if the factory's id derivation changed, which is
     * the one thing this test is positioned to notice.
     */
    @Test
    @DisplayName("an agent-scoped grant reaches every session of that agent and stops at the agent boundary")
    void anAgentGrantReachesEverySessionOfThatAgentButNotAnotherAgent() {
        support.seedSkill(OTHER_NODE, SKILL, guardedSkill());
        final OrcaRuntimeItSupport.Node otherAgent = support.newNode(OTHER_NODE, llm, OrcaRuntimeItSupport.options()
                // Its own session store, the SAME agent store: the only reason agent-b could be allowed is a grant
                // that failed to key on the runtime id.
                .skillInvocationPolicy(chain(new InMemorySessionApprovalStore(), agents, denyingBase())));
        agents.put(node.runtime().getId(), SKILL, SkillInvocationDecision.ALLOW);

        final SessionId first = OrcaRuntimeItSupport.session("agent-a-1");
        final SessionId second = OrcaRuntimeItSupport.session("agent-a-2");
        final SessionId foreign = OrcaRuntimeItSupport.session("agent-b-1");
        scriptSkillCall(first.value());
        scriptSkillCall(second.value());
        scriptSkillCall(foreign.value());

        node.run(first, "use the guarded skill");
        node.run(second, "use the guarded skill");
        otherAgent.run(foreign, "use the guarded skill");

        assertThat(skillObservation(first)).contains(BODY);
        assertThat(skillObservation(second)).contains(BODY);
        assertThat(skillObservation(foreign)).isEqualTo(DENIED);
    }

    /**
     * The chain order, asserted by making the two layers disagree. The agent store says yes for the whole agent, the
     * session store says no for one session, and the narrow no wins — transpose the two wrappers and the agent grant
     * would answer first, turning this red.
     *
     * <p>
     * The second session is not decoration. Without it, "the narrow no wins" would be satisfiable by an agent store
     * that was never consulted at all; the control proves the grant it is overriding is genuinely in effect.
     */
    @Test
    @DisplayName("a session-scoped refusal overrides an agent-wide grant")
    void aSessionRefusalOverridesAnAgentWideGrant() {
        agents.put(node.runtime().getId(), SKILL, SkillInvocationDecision.ALLOW);
        final SessionId narrowed = OrcaRuntimeItSupport.session("narrowed");
        final SessionId wide = OrcaRuntimeItSupport.session("wide");
        sessions.put(narrowed, SKILL, SkillInvocationDecision.DENY);
        scriptSkillCall(narrowed.value());
        scriptSkillCall(wide.value());

        node.run(narrowed, "use the guarded skill");
        node.run(wide, "use the guarded skill");

        assertThat(skillObservation(narrowed)).isEqualTo(DENIED);
        assertThat(skillObservation(wide)).contains(BODY);
    }

    /**
     * Reach follows delegation. A fork has no {@code SessionId} of its own — it is not a session's turn — so on a
     * denying base the only way it can be allowed is the policy's second lookup, on the {@code invokingSessionId} the
     * fork carries down from the session that spawned it.
     *
     * <p>
     * The ungranted half of the test is what stops this from being satisfiable by forks simply being exempt: same
     * node, same worker, no grant, and the fork is refused.
     */
    @Test
    @DisplayName("a fork is answered by the grant given in the session that spawned it")
    void aForkIsAnsweredByTheGrantOfItsInvokingSession() {
        support.seedSkill("agent-delegating", SKILL, guardedSkill());
        final InMemorySessionApprovalStore forkSessions = new InMemorySessionApprovalStore();
        final OrcaRuntimeItSupport.Node delegating = support.newNode("agent-delegating", llm,
                OrcaRuntimeItSupport.options().codeSubagents(workerRegistry())
                        .skillInvocationPolicy(chain(forkSessions, new InMemoryAgentApprovalStore(), denyingBase())));

        final SessionId granted = OrcaRuntimeItSupport.session("fork-granted");
        final SessionId ungranted = OrcaRuntimeItSupport.session("fork-ungranted");
        forkSessions.put(granted, SKILL, SkillInvocationDecision.ALLOW);
        scriptDelegation(granted);
        scriptDelegation(ungranted);

        delegating.run(granted, "delegate the guarded skill");
        delegating.run(ungranted, "delegate the guarded skill");

        assertThat(forkObservation(ScriptedLlmClient.forkRoute(granted.value(), WORKER)))
                .contains("=== Skill Activated ===").contains(BODY);
        assertThat(forkObservation(ScriptedLlmClient.forkRoute(ungranted.value(), WORKER))).isEqualTo(DENIED);
    }

    /** Main agent delegates to the worker; the worker invokes the skill. Two routes, scripted as one unit. */
    private void scriptDelegation(SessionId sessionId) {
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("Task",
                        Map.of("subagent_name", WORKER, "prompt", "use the guarded skill", "description", "delegate")),
                ScriptedLlmClient.text("delegated"));
        llm.script(ScriptedLlmClient.forkRoute(sessionId.value(), WORKER),
                ScriptedLlmClient.callTool("Skill", Map.of("skill", SKILL)), ScriptedLlmClient.text("worker done"));
    }

    /**
     * {@code ASK} and {@code DENY} are not the same answer and must not read as the same one — "waiting on you" and
     * "no" call for different next moves from the model. Collapse the two branches of
     * {@code SkillTool#formatPolicyRejection} into one message and this goes red.
     *
     * <p>
     * This is also the honest upper bound on what the suite can say about ASK: no approval channel and no suspend path
     * exist in this assembly, so the second half asserts only the part that is real here — a pre-existing stored answer
     * short-circuits the ASK before the base rule is ever reached, which is the mechanism an approval channel would
     * write into.
     */
    @Test
    @DisplayName("ASK is reported as awaiting approval rather than as a refusal, and a stored answer pre-empts it")
    void askIsReportedAsAwaitingApproval() {
        support.seedSkill("agent-asking", SKILL, guardedSkill());
        final InMemorySessionApprovalStore askSessions = new InMemorySessionApprovalStore();
        final OrcaRuntimeItSupport.Node asking = support.newNode("agent-asking", llm,
                OrcaRuntimeItSupport.options().skillInvocationPolicy(
                        chain(askSessions, new InMemoryAgentApprovalStore(), RuleBasedSkillInvocationPolicy.builder()
                                .safeByDefault(false).defaultDecision(SkillInvocationDecision.ASK).build())));

        final SessionId asked = OrcaRuntimeItSupport.session("asked");
        final SessionId answered = OrcaRuntimeItSupport.session("answered");
        askSessions.put(answered, SKILL, SkillInvocationDecision.ALLOW);
        scriptSkillCall(asked.value());
        scriptSkillCall(answered.value());

        asking.run(asked, "use the guarded skill");
        asking.run(answered, "use the guarded skill");

        assertThat(skillObservation(asked)).isEqualTo(NEEDS_APPROVAL);
        assertThat(skillObservation(asked)).doesNotContain("denied by policy").doesNotContain(BODY);
        assertThat(skillObservation(answered)).contains(BODY);
    }
}
