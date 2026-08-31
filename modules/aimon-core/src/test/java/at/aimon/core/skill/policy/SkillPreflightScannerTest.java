package at.aimon.core.skill.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.policy.approval.SkillApprovalChannel;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;

/** Unit tests for {@link SkillPreflightScanner}. */
class SkillPreflightScannerTest {

    private static final AgentRuntimeId CTX = AgentRuntimeId.of("agent:test-1");

    @Test
    void allAllowedReturnsProceed() {
        final SkillPreflightScanner scanner = scannerWith(Map.of("commit", SkillInvocationDecision.ALLOW),
                List.of("commit"));

        final SkillPreflightScanResult result = scanner.scan(List.of(skillUse("tu1", "commit", "")), CTX, null, null);

        assertThat(result.shouldSuspend()).isFalse();
        assertThat(result).isSameAs(SkillPreflightScanResult.proceed());
    }

    @Test
    void anyAskTriggersSuspend() {
        final SkillPreflightScanner scanner = scannerWith(
                Map.of("commit", SkillInvocationDecision.ALLOW, "deploy", SkillInvocationDecision.ASK),
                List.of("commit", "deploy"));

        final SkillPreflightScanResult result = scanner
                .scan(List.of(skillUse("tu1", "commit", ""), skillUse("tu2", "deploy", "--prod")), CTX, null, null);

        assertThat(result.shouldSuspend()).isTrue();
        assertThat(result.getPendingSkills()).hasSize(1);
        final PendingSkillRequest pending = result.getPendingSkills().get(0);
        assertThat(pending.getToolUseId()).isEqualTo("tu2");
        assertThat(pending.getSkillName()).isEqualTo("deploy");
        assertThat(pending.getArgs()).isEqualTo("--prod");
    }

    @Test
    void multipleAsksAllRecorded() {
        final SkillPreflightScanner scanner = scannerWith(
                Map.of("a", SkillInvocationDecision.ASK, "b", SkillInvocationDecision.ASK), List.of("a", "b"));

        final SkillPreflightScanResult result = scanner
                .scan(List.of(skillUse("tu1", "a", ""), skillUse("tu2", "b", "")), CTX, null, null);

        assertThat(result.getPendingSkills()).extracting(PendingSkillRequest::getSkillName).containsExactly("a", "b");
    }

    @Test
    void denyDoesNotSuspend() {
        // DENY is not suspend-worthy: SkillTool's per-tool execution will surface the policy-rejected error.
        final SkillPreflightScanner scanner = scannerWith(Map.of("commit", SkillInvocationDecision.DENY),
                List.of("commit"));

        final SkillPreflightScanResult result = scanner.scan(List.of(skillUse("tu1", "commit", "")), CTX, null, null);

        assertThat(result.shouldSuspend()).isFalse();
    }

    @Test
    void mixedAllowDenyAskSuspendsOnAskOnly() {
        final SkillPreflightScanner scanner = scannerWith(Map.of("a", SkillInvocationDecision.ALLOW, "b",
                SkillInvocationDecision.DENY, "c", SkillInvocationDecision.ASK), List.of("a", "b", "c"));

        final SkillPreflightScanResult result = scanner.scan(
                List.of(skillUse("tu1", "a", ""), skillUse("tu2", "b", ""), skillUse("tu3", "c", "")), CTX, null, null);

        assertThat(result.getPendingSkills()).extracting(PendingSkillRequest::getSkillName).containsExactly("c");
    }

    @Test
    void nonSkillToolUsesIgnored() {
        final SkillPreflightScanner scanner = scannerWith(Map.of(), List.of());

        final SkillPreflightScanResult result = scanner
                .scan(List.of(ToolUse.of("tu1", "Bash", Map.of("command", "ls"))), CTX, null, null);

        assertThat(result.shouldSuspend()).isFalse();
    }

    @Test
    void unknownSkillIgnored() {
        // Registry has no skill named "ghost" — let SkillTool's "Skill not found" error be the response, don't suspend.
        final SkillPreflightScanner scanner = scannerWith(Map.of(), List.of());

        final SkillPreflightScanResult result = scanner.scan(List.of(skillUse("tu1", "ghost", "")), CTX, null, null);

        assertThat(result.shouldSuspend()).isFalse();
    }

    @Test
    void missingSkillInputIgnored() {
        final SkillPreflightScanner scanner = scannerWith(Map.of(), List.of());

        // Tool_use missing the required "skill" key — don't suspend; let SkillTool emit its IllegalArgumentException.
        final SkillPreflightScanResult result = scanner.scan(List.of(ToolUse.of("tu1", "Skill", Map.of("args", "x"))),
                CTX, null, null);

        assertThat(result.shouldSuspend()).isFalse();
    }

    @Test
    void nonStringSkillInputIgnored() {
        final SkillPreflightScanner scanner = scannerWith(Map.of(), List.of());

        final SkillPreflightScanResult result = scanner.scan(List.of(ToolUse.of("tu1", "Skill", Map.of("skill", 42))),
                CTX, null, null);

        assertThat(result.shouldSuspend()).isFalse();
    }

    @Test
    void emptyToolUsesProceed() {
        final SkillPreflightScanner scanner = scannerWith(Map.of(), List.of());

        assertThat(scanner.scan(List.of(), CTX, null, null).shouldSuspend()).isFalse();
    }

    @Test
    void argsDefaultEmptyWhenAbsent() {
        final SkillPreflightScanner scanner = scannerWith(Map.of("commit", SkillInvocationDecision.ASK),
                List.of("commit"));

        final SkillPreflightScanResult result = scanner
                .scan(List.of(ToolUse.of("tu1", "Skill", Map.of("skill", "commit"))), CTX, null, null);

        assertThat(result.getPendingSkills().get(0).getArgs()).isEmpty();
    }

    @Test
    void contextIdAndPrincipalThreadedIntoPolicyRequest() {
        final RecordingPolicy policy = new RecordingPolicy();
        final SkillPreflightScanner scanner = new SkillPreflightScanner(policy, registryWith(List.of("commit")));

        scanner.scan(List.of(skillUse("tu1", "commit", "args")), CTX, null, null);

        assertThat(policy.lastRequest).isNotNull();
        assertThat(policy.lastRequest.getAgentRuntimeId()).contains(CTX);
        assertThat(policy.lastRequest.getArgs()).isEqualTo("args");
        assertThat(policy.lastRequest.getSkill().getName()).isEqualTo("commit");
    }

    @Test
    void nullToolUsesRejected() {
        final SkillPreflightScanner scanner = scannerWith(Map.of(), List.of());

        assertThatThrownBy(() -> scanner.scan(null, CTX, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullArgs() {
        assertThatThrownBy(() -> new SkillPreflightScanner(null, registryWith(List.of())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SkillPreflightScanner(AlwaysAllowSkillInvocationPolicy.INSTANCE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void approvalChannelInvokedAndScanProceedsWhenAskCollected() {
        // SK-11.6: an inline approval channel resolves the ASK list. The scanner trusts the channel to have persisted
        // ALLOW/DENY (verified by the channel observing the exact pending list it was handed) and returns proceed so
        // the agent loop continues into normal tool execution rather than suspending.
        final RecordingApprovalChannel channel = new RecordingApprovalChannel();
        final SkillPreflightScanner scanner = SkillPreflightScanner.builder()
                .policy(new MapBackedPolicy(Map.of("commit", SkillInvocationDecision.ASK)))
                .registry(registryWith(List.of("commit"))).approvalChannel(channel).build();

        final SkillPreflightScanResult result = scanner.scan(List.of(skillUse("tu1", "commit", "--amend")), CTX, null,
                null);

        assertThat(result.shouldSuspend()).as("channel resolves ASK inline → no suspend").isFalse();
        assertThat(channel.invocations).as("channel must be consulted exactly once for the collected ASK list")
                .hasSize(1);
        assertThat(channel.invocations.get(0)).extracting(PendingSkillRequest::getSkillName).containsExactly("commit");
    }

    @Test
    void approvalChannelNotInvokedWhenNoAskCollected() {
        // Channel must not be touched on the happy ALLOW path — invoking it would be a wasted prompt to the user.
        final RecordingApprovalChannel channel = new RecordingApprovalChannel();
        final SkillPreflightScanner scanner = SkillPreflightScanner.builder()
                .policy(new MapBackedPolicy(Map.of("commit", SkillInvocationDecision.ALLOW)))
                .registry(registryWith(List.of("commit"))).approvalChannel(channel).build();

        final SkillPreflightScanResult result = scanner.scan(List.of(skillUse("tu1", "commit", "")), CTX, null, null);

        assertThat(result.shouldSuspend()).isFalse();
        assertThat(channel.invocations).as("ALLOW path must skip the channel entirely").isEmpty();
    }

    @Test
    void approvalChannelSkippedWhenRuntimeIdNull() {
        // System/scheduled callers pass a null agentRuntimeId. The channel contract requires a non-null agentRuntimeId
        // (it scopes
        // AgentApprovalStore writes), so the scanner must skip the channel entirely and use the suspend path — the
        // SK-11.4 flow generates its own PendingTurnId and does not need the agentRuntimeId.
        final RecordingApprovalChannel channel = new RecordingApprovalChannel();
        final SkillPreflightScanner scanner = SkillPreflightScanner.builder()
                .policy(new MapBackedPolicy(Map.of("commit", SkillInvocationDecision.ASK)))
                .registry(registryWith(List.of("commit"))).approvalChannel(channel).build();

        final SkillPreflightScanResult result = scanner.scan(List.of(skillUse("tu1", "commit", "")), null, null, null);

        assertThat(result.shouldSuspend()).as("null agentRuntimeId must route to suspend, not the channel").isTrue();
        assertThat(channel.invocations).as("channel must not be consulted without a agentRuntimeId to scope writes")
                .isEmpty();
    }

    @Test
    void approvalChannelExceptionFallsBackToSuspend() {
        // Defensive contract: channels must not throw, but a buggy implementation must not destroy the in-flight turn.
        // The scanner catches RuntimeException, logs, and falls back to the legacy suspend path so the user can resolve
        // out-of-band via /approve or /deny.
        final SkillPreflightScanner scanner = SkillPreflightScanner.builder()
                .policy(new MapBackedPolicy(Map.of("commit", SkillInvocationDecision.ASK)))
                .registry(registryWith(List.of("commit"))).approvalChannel((requests, ctxId) -> {
                    throw new RuntimeException("channel unavailable");
                }).build();

        final SkillPreflightScanResult result = scanner.scan(List.of(skillUse("tu1", "commit", "")), CTX, null, null);

        assertThat(result.shouldSuspend()).as("buggy channel falls back to suspend").isTrue();
        assertThat(result.getPendingSkills()).hasSize(1);
    }

    @Test
    void eachRuntimeIsScannedAgainstItsOwnRegistry() {
        // The multi-agent case, and the reason the resolver exists. Agent B's skill is not in agent A's registry;
        // with a fixed registry it would resolve to nothing here, be skipped, and reach SkillTool — which re-checks
        // the policy and refuses on ASK. The user is never asked and the skill never runs. Both agents must reach
        // ASK through their own registry.
        final AgentRuntimeId agentA = AgentRuntimeId.of("agent:alpha");
        final AgentRuntimeId agentB = AgentRuntimeId.of("agent:beta");
        final Map<AgentRuntimeId, SkillRegistry> perAgent = Map.of(agentA, registryWith(List.of("alpha-only")), agentB,
                registryWith(List.of("beta-only")));
        final SkillPreflightScanner scanner = SkillPreflightScanner.builder()
                .policy(new MapBackedPolicy(
                        Map.of("alpha-only", SkillInvocationDecision.ASK, "beta-only", SkillInvocationDecision.ASK)))
                .registries(id -> Optional.ofNullable(perAgent.get(id))).build();

        assertThat(scanner.scan(List.of(skillUse("tu1", "alpha-only", "")), agentA, null, null).getPendingSkills())
                .as("agent A's skill resolves through agent A's registry").extracting(PendingSkillRequest::getSkillName)
                .containsExactly("alpha-only");
        assertThat(scanner.scan(List.of(skillUse("tu2", "beta-only", "")), agentB, null, null).getPendingSkills())
                .as("agent B's skill resolves through agent B's registry — the case a fixed registry loses")
                .extracting(PendingSkillRequest::getSkillName).containsExactly("beta-only");
    }

    @Test
    void aSkillBelongingToAnotherAgentIsNotResolvedFromThisOne() {
        // The other half of the same guarantee: per-runtime resolution must not merge the registries. Agent B asking
        // for agent A's skill is an unknown skill from B's point of view, and SkillTool produces the canonical
        // "not found" error rather than the scanner suspending the turn for something that cannot run.
        final AgentRuntimeId agentA = AgentRuntimeId.of("agent:alpha");
        final AgentRuntimeId agentB = AgentRuntimeId.of("agent:beta");
        final SkillRegistry registryA = registryWith(List.of("alpha-only"));
        final SkillPreflightScanner scanner = SkillPreflightScanner.builder()
                .policy(new MapBackedPolicy(Map.of("alpha-only", SkillInvocationDecision.ASK)))
                .registries(id -> Optional.ofNullable(agentA.equals(id) ? registryA : registryWith(List.of()))).build();

        assertThat(scanner.scan(List.of(skillUse("tu1", "alpha-only", "")), agentB, null, null).shouldSuspend())
                .isFalse();
    }

    @Test
    void anUnresolvableRuntimeProceedsWithoutScanning() {
        // Evicted, invalidated or never registered. Nothing can be decided without a registry, so the scan proceeds
        // and the invocation reaches SkillTool. Pinned because the alternative — throwing — would take down an
        // in-flight turn for a condition the turn cannot do anything about.
        final SkillPreflightScanner scanner = SkillPreflightScanner.builder()
                .policy(new MapBackedPolicy(Map.of("commit", SkillInvocationDecision.ASK)))
                .registries(id -> Optional.empty()).build();

        final SkillPreflightScanResult result = scanner.scan(List.of(skillUse("tu1", "commit", "")), CTX, null, null);

        assertThat(result.shouldSuspend()).isFalse();
    }

    @Test
    void theResolverIsNotConsultedWhenNoSkillIsInvoked() {
        // Most responses contain no Skill tool_use at all. Resolving anyway would put a registry lookup on every
        // iteration of every turn, and would log the unresolvable-runtime warning for runtimes that never asked.
        final boolean[] consulted = {false};
        final SkillPreflightScanner scanner = SkillPreflightScanner.builder().policy(new MapBackedPolicy(Map.of()))
                .registries(id -> {
                    consulted[0] = true;
                    return Optional.of(registryWith(List.of()));
                }).build();

        scanner.scan(List.of(ToolUse.of("tu1", "Bash", Map.of("command", "ls"))), CTX, null, null);

        assertThat(consulted[0]).isFalse();
    }

    @Test
    void builderEnforcesNonNullPolicyAndRegistry() {
        assertThatThrownBy(() -> SkillPreflightScanner.builder().registry(registryWith(List.of())).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> SkillPreflightScanner.builder().policy(AlwaysAllowSkillInvocationPolicy.INSTANCE).build())
                .isInstanceOf(NullPointerException.class);
    }

    private static final class RecordingApprovalChannel implements SkillApprovalChannel {
        final java.util.List<List<PendingSkillRequest>> invocations = new java.util.ArrayList<>();

        @Override
        public void requestApproval(List<PendingSkillRequest> pendingRequests, AgentRuntimeId agentRuntimeId) {
            invocations.add(List.copyOf(pendingRequests));
        }
    }

    private static ToolUse skillUse(String id, String skillName, String args) {
        return ToolUse.of(id, "Skill", Map.of("skill", skillName, "args", args));
    }

    private static SkillPreflightScanner scannerWith(Map<String, SkillInvocationDecision> decisions,
            List<String> registeredSkills) {
        return new SkillPreflightScanner(new MapBackedPolicy(decisions), registryWith(registeredSkills));
    }

    private static SkillRegistry registryWith(List<String> names) {
        final TestSkillRegistry registry = new TestSkillRegistry();
        for (String name : names) {
            registry.addSkill(
                    Skill.builder().name(name).metadata(SkillMetadata.builder().name(name).description("desc").build())
                            .content(SkillContent.of("body")).build());
        }
        return registry;
    }

    private static final class MapBackedPolicy implements SkillInvocationPolicy {
        private final Map<String, SkillInvocationDecision> decisions;

        MapBackedPolicy(Map<String, SkillInvocationDecision> decisions) {
            this.decisions = decisions;
        }

        @Override
        public SkillInvocationDecision check(SkillInvocationRequest request) {
            return decisions.getOrDefault(request.getSkill().getName(), SkillInvocationDecision.DENY);
        }
    }

    private static final class RecordingPolicy implements SkillInvocationPolicy {
        SkillInvocationRequest lastRequest;

        @Override
        public SkillInvocationDecision check(SkillInvocationRequest request) {
            lastRequest = request;
            return SkillInvocationDecision.ALLOW;
        }
    }

    private static final class TestSkillRegistry implements SkillRegistry {
        private final Map<String, Skill> skills = new HashMap<>();

        void addSkill(Skill skill) {
            skills.put(skill.getName(), skill);
        }

        @Override
        public Optional<Skill> getSkill(String name) {
            return Optional.ofNullable(skills.get(name));
        }

        @Override
        public List<Skill> getAllSkills() {
            return List.copyOf(skills.values());
        }

        @Override
        public void reloadSkill(String skillName) {
            // no-op
        }

        @Override
        public void reloadAll() {
            // no-op
        }
    }
}
