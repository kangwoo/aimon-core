package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.exception.AimonBootstrapException;
import at.aimon.bootstrap.runtime.AgentRuntimeLease;
import at.aimon.bootstrap.spec.AgentDescriptor;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.AimonAgentCustomizer;
import at.aimon.bootstrap.spec.ExecutorSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.SkillApprovalSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.approval.DenyAllSkillApprovalChannel;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.core.tools.knowledge.KnowledgeSearchTool;
import at.aimon.core.tracing.TracePayloadPolicy;

/**
 * Covers the seams an embedding front end reaches for: the approval-channel factory, runtime customizers, and
 * enrolling caller-owned resources in the stack's teardown.
 *
 * <p>
 * Each of these exists because the alternative fails silently rather than loudly — a channel writing to stores
 * nobody reads, a tool registered after the runtime is reachable, a resource closed outside the ordered plan. The
 * assertions are therefore about ordering and effect, not merely about the calls happening.
 */
class AimonStackExtensionPointTest {

    /** Never called — no test here runs a turn. */
    private static final LlmClient STUB_LLM = new LlmClient() {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

    };

    private static AgentBundle bundle(String name) {
        return AgentBundle.builder()
                .agent(DefaultAgent.builder().name(name).systemPrompt("You are a test agent.").maxIterations(5).build())
                .build();
    }

    private static AimonStackSpec.Builder specFor(Path workspace, AgentSpec agent) {
        return AimonStackSpec.builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(STUB_LLM)).agent(agent);
    }

    private static AimonStackSpec.Builder specFor(Path workspace) {
        return specFor(workspace, AgentSpec.of(bundle("ops")));
    }

    // --- Approval channel factory ---------------------------------------------------------------------------

    @Test
    @DisplayName("the channel factory is handed the stack's own approval stores")
    void channelFactoryReceivesTheStacksOwnStores(@TempDir Path workspace) {
        final AtomicReference<SessionApprovalStore> sessionStore = new AtomicReference<>();
        final AtomicReference<AgentApprovalStore> agentStore = new AtomicReference<>();

        final SkillApprovalSpec approval = SkillApprovalSpec.channelFactory((session, agent) -> {
            sessionStore.set(session);
            agentStore.set(agent);
            return new DenyAllSkillApprovalChannel(session, agent);
        });

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace).skillApproval(approval).build())) {
            assertThat(stack.health().isServing()).isTrue();
        }

        // The point is not that they are non-null but that they arrived at all: a caller cannot construct these
        // before the stack exists, which is why the call is inverted rather than the stores being passed in.
        assertThat(sessionStore.get()).isNotNull();
        assertThat(agentStore.get()).isNotNull();
    }

    @Test
    @DisplayName("a factory returning null fails the build instead of producing an unusable channel")
    void nullChannelFromFactoryFailsTheBuild(@TempDir Path workspace) {
        final SkillApprovalSpec approval = SkillApprovalSpec.channelFactory((session, agent) -> null);

        assertThatThrownBy(() -> AimonStackBuilder.build(specFor(workspace).skillApproval(approval).build()))
                .isInstanceOf(AimonBootstrapException.class);
    }

    @Test
    @DisplayName("SUPPLIED_FACTORY without a factory is rejected at spec construction")
    void suppliedFactoryModeRequiresAFactory() {
        assertThatThrownBy(() -> SkillApprovalSpec.channelFactory(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("withDefaultDecision carries the factory across")
    void withDefaultDecisionPreservesTheFactory() {
        final SkillApprovalSpec spec = SkillApprovalSpec.channelFactory((session, agent) -> null)
                .withDefaultDecision(SkillInvocationDecision.DENY);

        assertThat(spec.getChannelMode()).isEqualTo(SkillApprovalSpec.ChannelMode.SUPPLIED_FACTORY);
        assertThat(spec.getChannelFactory()).isPresent();
        assertThat(spec.getDefaultDecision()).isEqualTo(SkillInvocationDecision.DENY);
    }

    @Test
    @DisplayName("a non-positive sweep interval is rejected rather than producing a spinning reaper")
    void nonPositiveSweepIntervalIsRejected() {
        assertThatThrownBy(() -> SkillApprovalSpec.denyAll().withPendingTurnSweepInterval(java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }

    // --- Runtime customizers --------------------------------------------------------------------------------

    @Test
    @DisplayName("customizers run in declaration order and their registrations reach the published runtime")
    void customizersRunInOrderAndAffectThePublishedRuntime(@TempDir Path workspace) {
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger first = new AtomicInteger(-1);
        final AtomicInteger second = new AtomicInteger(-1);

        final AgentSpec agent = AgentSpec.builder().bundle(bundle("ops")).addCustomizer(runtime -> {
            first.set(next.getAndIncrement());
            runtime.getToolRegistry().register(new MarkerTool());
        }).addCustomizer(runtime -> second.set(next.getAndIncrement())).build();

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, agent).build())) {
            assertThat(first.get()).isZero();
            assertThat(second.get()).isEqualTo(1);

            // The runtime the registry hands out is the same object the customizer decorated — so a tool
            // registered there is reachable from the very first turn, with no window in between.
            final OrcaAgentRuntime published = stack.agentRuntimeRegistry().get(stack.primaryRuntimeId())
                    .map(OrcaAgentRuntime.class::cast).orElseThrow();
            assertThat(published.getToolRegistry().findByName(MarkerTool.TOOL_NAME)).isPresent();
            assertThat(stack.runtime(stack.primaryRuntimeId())).containsSame(published);
        }
    }

    @Test
    @DisplayName("a throwing customizer aborts the build rather than publishing a half-configured runtime")
    void throwingCustomizerAbortsTheBuild(@TempDir Path workspace) {
        final AgentSpec agent = AgentSpec.builder().bundle(bundle("ops")).addCustomizer(runtime -> {
            throw new IllegalStateException("customizer boom");
        }).build();

        assertThatThrownBy(() -> AimonStackBuilder.build(specFor(workspace, agent).build()))
                .isInstanceOf(AimonBootstrapException.class).hasRootCauseMessage("customizer boom");
    }

    // --- Agent customizers ----------------------------------------------------------------------------------

    @Test
    @DisplayName("a customizer's tools reach the startup runtime and every tenant of the same agent")
    void agentCustomizerContributionsReachStartupAndTenantRuntimes(@TempDir Path workspace) {
        final AimonAgentCustomizer ticketing = new AimonAgentCustomizer() {

            @Override
            public boolean supports(AgentDescriptor agent) {
                return "ops".equals(agent.getAgentRef());
            }

            @Override
            public List<OrcaToolProvider> toolProviders(AgentDescriptor agent) {
                return List.of((registry, context) -> registry.register(new MarkerTool()));
            }
        };

        try (AimonStack stack = AimonStackBuilder
                .build(specFor(workspace).agent(AgentSpec.of(bundle("audit"))).agentCustomizer(ticketing).build())) {

            assertThat(
                    runtimeOf(stack, AgentRuntimeId.fromName("ops")).getToolRegistry().findByName(MarkerTool.TOOL_NAME))
                    .isPresent();

            // The row this whole seam exists for: the tenant runtime is built on a request thread hours later,
            // from the same one place, so it cannot be given a different tool list than its own agent.
            try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(AgentRuntimeId.fromName("ops", "acme"))) {
                assertThat(((OrcaAgentRuntime) lease.runtime()).getToolRegistry().findByName(MarkerTool.TOOL_NAME))
                        .isPresent();
            }

            // And the agent it did not select keeps the base list — a contribution is not stack-wide.
            assertThat(runtimeOf(stack, AgentRuntimeId.fromName("audit")).getToolRegistry()
                    .findByName(MarkerTool.TOOL_NAME)).isEmpty();
        }
    }

    @Test
    @DisplayName("the descriptor names the ref, the bundle it came from, the tenant and the agent's properties")
    void descriptorCarriesRefBundleDiscriminatorAndProperties(@TempDir Path workspace) {
        final List<AgentDescriptor> seen = Collections.synchronizedList(new ArrayList<>());
        final AgentSpec ops = AgentSpec.builder().name("ops-eu").bundleName("shared").bundle(bundle("shared"))
                .property("tier", "gold").build();

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, ops).agentCustomizer(agent -> {
            seen.add(agent);
            return false;
        }).build())) {
            try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(AgentRuntimeId.fromName("ops-eu", "acme"))) {
                assertThat(lease.runtime()).isNotNull();
            }
        }

        assertThat(seen).hasSize(2);
        final AgentDescriptor startup = seen.get(0);
        assertThat(startup.getAgentRef()).isEqualTo("ops-eu");
        assertThat(startup.getBundleName()).isEqualTo("shared");
        assertThat(startup.getDiscriminator()).isEmpty();
        assertThat(startup.getRuntimeId()).isEqualTo(AgentRuntimeId.fromName("ops-eu"));
        assertThat(startup.getProperty("tier")).contains("gold");

        // Same agent, next tenant: everything but the discriminator has to be identical, or "supports" answers
        // differently for two runtimes of one agent and the two end up with different tools.
        final AgentDescriptor tenant = seen.get(1);
        assertThat(tenant.getAgentRef()).isEqualTo("ops-eu");
        assertThat(tenant.getBundleName()).isEqualTo("shared");
        assertThat(tenant.getDiscriminator()).contains("acme");
        assertThat(tenant.getRuntimeId()).isEqualTo(AgentRuntimeId.fromName("ops-eu", "acme"));
        assertThat(tenant.getProperties()).isEqualTo(startup.getProperties());
    }

    @Test
    @DisplayName("customizers apply in getOrder(), not in the order the host happened to supply them")
    void agentCustomizersApplyInGetOrderOrder(@TempDir Path workspace) {
        final List<String> applied = Collections.synchronizedList(new ArrayList<>());

        // Supplied late-then-early, because bean injection order is what getOrder() exists to stop mattering.
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace)
                .agentCustomizers(
                        List.of(recordingCustomizer(10, "late", applied), recordingCustomizer(-10, "early", applied)))
                .build())) {
            assertThat(applied).containsExactly("early", "late");
        }
    }

    // --- Ref versus bundle ----------------------------------------------------------------------------------

    @Test
    @DisplayName("two refs on one bundle are two runtimes, and the id follows the ref not the definition")
    void twoRefsOnOneBundleBecomeTwoRuntimes(@TempDir Path workspace) {
        final AgentSpec eu = AgentSpec.builder().name("ops-eu").bundleName("shared").bundle(bundle("shared")).build();
        final AgentSpec us = AgentSpec.builder().name("ops-us").bundleName("shared").bundle(bundle("shared")).build();

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, eu).agent(us).build())) {
            assertThat(stack.agentRuntimeRegistry().get(AgentRuntimeId.fromName("ops-eu"))).isPresent();
            assertThat(stack.agentRuntimeRegistry().get(AgentRuntimeId.fromName("ops-us"))).isPresent();

            // The name inside the definition is not an id. Routing on it would mean a host cannot know what to
            // submit against without opening the bundle, and a frontmatter edit would move an agent out from
            // under every caller.
            assertThat(stack.agentRuntimeRegistry().get(AgentRuntimeId.fromName("shared"))).isEmpty();
            assertThat(runtimeOf(stack, AgentRuntimeId.fromName("ops-eu")))
                    .isNotSameAs(runtimeOf(stack, AgentRuntimeId.fromName("ops-us")));
        }
    }

    @Test
    @DisplayName("two agents that resolve to one runtime id are rejected rather than one silently winning")
    void duplicateRuntimeIdsAreRejected(@TempDir Path workspace) {
        // Same ref, different bundles: the identity is the ref, so the second one's bundle, file system and tool
        // set would be discarded on a cache hit nobody sees. Rejected at spec construction, before anything is
        // built; the builder repeats the check against the ids it derives.
        final AgentSpec ops = AgentSpec.builder().name("ops").bundle(bundle("ops")).build();
        final AgentSpec sameRef = AgentSpec.builder().name("ops").bundleName("other").bundle(bundle("other")).build();

        assertThatThrownBy(() -> specFor(workspace, ops).agent(sameRef).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate agent runtime identity");
    }

    // --- Credential store factory ---------------------------------------------------------------------------

    @Test
    @DisplayName("the credential factory is asked per tenant, and a startup runtime is the null tenant")
    void credentialFactoryIsKeyedOnTheTenantAlone(@TempDir Path workspace) {
        final List<String> asked = Collections.synchronizedList(new ArrayList<>());

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace).credentialStoreFactory(discriminator -> {
            asked.add(String.valueOf(discriminator));
            return mock(CredentialStore.class);
        }).build())) {
            // The startup runtimes execute turns like any other, so they have to be answered too — with the
            // deployment's non-tenant credentials, which is what the null stands for.
            assertThat(asked).containsExactly("null");

            try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(AgentRuntimeId.fromName("ops", "acme"))) {
                assertThat(lease.runtime()).isNotNull();
            }
            assertThat(asked).containsExactly("null", "acme");
        }
    }

    @Test
    @DisplayName("a credential store and a factory together are rejected rather than one silently winning")
    void credentialStoreAndFactoryTogetherAreRejected(@TempDir Path workspace) {
        assertThatThrownBy(() -> specFor(workspace).credentialStore(mock(CredentialStore.class))
                .credentialStoreFactory(discriminator -> mock(CredentialStore.class)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not both");
    }

    // --- Caller-owned teardown ------------------------------------------------------------------------------

    @Test
    @DisplayName("enrolled resources close in phase order, interleaved with the stack's own")
    void enrolledResourcesCloseInPhaseOrder(@TempDir Path workspace) {
        final List<String> closed = new ArrayList<>();
        final AimonStack stack = AimonStackBuilder.build(specFor(workspace).build());

        // Registered in an order that does not match the phase order, so passing cannot be an accident of
        // registration sequence.
        stack.own(TeardownPhase.SCRIPT_ENGINES, "scriptEngines", () -> closed.add("scriptEngines"));
        stack.own(TeardownPhase.HOOK_HOT_RELOAD, "hookHotReload", () -> closed.add("hookHotReload"));
        stack.own(TeardownPhase.MEMORY_QUEUE, "memoryQueue", () -> closed.add("memoryQueue"));

        assertThat(stack.teardownPlan()).anyMatch(line -> line.contains("scriptEngines"));

        stack.close();

        assertThat(closed).containsExactly("memoryQueue", "scriptEngines", "hookHotReload");
    }

    @Test
    @DisplayName("enrolling null is ignored so an optional subsystem needs no surrounding if")
    void enrollingNullIsIgnored(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace).build())) {
            final int before = stack.teardownPlan().size();
            assertThat(stack.own(TeardownPhase.SCRIPT_ENGINES, "absent", (AutoCloseable) null)).isNull();
            assertThat(stack.teardownPlan()).hasSize(before);
        }
    }

    @Test
    @DisplayName("enrolling after close fails loudly rather than leaking the resource")
    void enrollingAfterCloseThrows(@TempDir Path workspace) {
        final AimonStack stack = AimonStackBuilder.build(specFor(workspace).build());
        stack.close();

        assertThatThrownBy(() -> stack.own(TeardownPhase.SCRIPT_ENGINES, "late", () -> {
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("already run");
    }

    // --- Executor and tool specs ----------------------------------------------------------------------------

    @Test
    @DisplayName("a payload policy with no tracer is reported as a degradation, not a failure")
    void tracePayloadPolicyWithoutTracerDegrades(@TempDir Path workspace) {
        final ExecutorSpec executor = ExecutorSpec.builder().tracePayloadPolicy(TracePayloadPolicy.summaryOnly())
                .build();

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace).executor(executor).build())) {
            assertThat(stack.degradations().has("tracing")).isTrue();
            assertThat(stack.health().isServing()).isTrue();
        }
    }

    @Test
    @DisplayName("the default executor spec adds no degradation")
    void defaultExecutorSpecIsClean(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace).build())) {
            assertThat(stack.degradations().has("tracing")).isFalse();
            assertThat(stack.spec().getExecutor().isStreaming()).isFalse();
        }
    }

    @Test
    @DisplayName("half an MCP pair is rejected at spec construction")
    void halfAnMcpPairIsRejected() {
        assertThatThrownBy(() -> ToolSpec.builder().mcp(config -> null, null).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both");
    }

    @Test
    @DisplayName("the workflow runner follows the workflow tool unless set apart")
    void workflowRunnerFollowsTheToolUnlessSetApart() {
        assertThat(ToolSpec.defaults().isWorkflowRunnerEnabled()).isFalse();
        assertThat(ToolSpec.builder().workflowToolEnabled(true).build().isWorkflowRunnerEnabled()).isTrue();

        // The case the split exists for: a front end supplying its own workflow tool provider needs the runner
        // without the built-in tool. Conflated, the supplied tool would fail at every invocation.
        final ToolSpec split = ToolSpec.builder().workflowToolEnabled(false).workflowRunnerEnabled(true).build();
        assertThat(split.isWorkflowToolEnabled()).isFalse();
        assertThat(split.isWorkflowRunnerEnabled()).isTrue();
    }

    // --- Knowledge store factory ----------------------------------------------------------------------------

    @Test
    @DisplayName("the knowledge store factory is handed the registry the runtimes register into")
    void knowledgeStoreFactoryReceivesTheRegistryRuntimesRegisterInto(@TempDir Path workspace) {
        final AtomicReference<AgentRuntimeRegistry> captured = new AtomicReference<>();

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace).knowledgeStoreFactory(registry -> {
            captured.set(registry);
            return mock(KnowledgeStore.class);
        }).build())) {

            // Not merely non-null: it has to be the same registry the runtime lands in, or every page lookup
            // resolves nothing and an empty knowledge base is indistinguishable from an unpopulated one.
            assertThat(captured.get()).isSameAs(stack.agentRuntimeRegistry());
            assertThat(captured.get().get(stack.primaryRuntimeId())).isPresent();
        }
    }

    @Test
    @DisplayName("a knowledge store puts the tool that reaches it in every runtime, tenant runtimes included")
    void aKnowledgeStoreImpliesTheToolThatSearchesIt(@TempDir Path workspace) {
        // The defect this pins was invisible from every angle except an agent's. OrcaAgentRuntimeFactory's
        // default provider list has no knowledge provider, so a store set on the spec reached the tool context
        // and the registry and was never searchable — an index nothing could query, reported healthy.
        try (AimonStack stack = AimonStackBuilder
                .build(specFor(workspace).knowledgeStore(mock(KnowledgeStore.class)).build())) {

            assertThat(runtimeOf(stack, stack.primaryRuntimeId()).getToolRegistry()
                    .findByName(KnowledgeSearchTool.TOOL_NAME)).isPresent();

            // Tenant runtimes are provisioned later from the same provisioner, which is the only reason one
            // assertion here is enough to cover a runtime built on a request thread.
            try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(AgentRuntimeId.fromName("ops", "acme"))) {
                assertThat(((OrcaAgentRuntime) lease.runtime()).getToolRegistry()
                        .findByName(KnowledgeSearchTool.TOOL_NAME)).isPresent();
            }
        }
    }

    @Test
    @DisplayName("no store means no knowledge tool, rather than a tool that answers nothing")
    void withoutAStoreTheKnowledgeToolIsAbsent(@TempDir Path workspace) {
        // The other half, and the reason the provider is conditional rather than always registered: a
        // KnowledgeSearch offered to a model with no store behind it is a capability advertised and then
        // declined, which costs an iteration every time the model believes the tool list.
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace).build())) {
            assertThat(runtimeOf(stack, stack.primaryRuntimeId()).getToolRegistry()
                    .findByName(KnowledgeSearchTool.TOOL_NAME)).isEmpty();
        }
    }

    @Test
    @DisplayName("a factory returning null fails the build")
    void nullKnowledgeStoreFromFactoryFailsTheBuild(@TempDir Path workspace) {
        assertThatThrownBy(
                () -> AimonStackBuilder.build(specFor(workspace).knowledgeStoreFactory(registry -> null).build()))
                .isInstanceOf(AimonBootstrapException.class);
    }

    @Test
    @DisplayName("a store and a factory together are rejected rather than one silently winning")
    void knowledgeStoreAndFactoryTogetherAreRejected(@TempDir Path workspace) {
        assertThatThrownBy(() -> specFor(workspace).knowledgeStore(mock(KnowledgeStore.class))
                .knowledgeStoreFactory(registry -> mock(KnowledgeStore.class)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not both");
    }

    // --- Supplied skill parser ------------------------------------------------------------------------------

    @Test
    @DisplayName("a supplied skill parser means the stack opens no shell of its own")
    void suppliedSkillParserSkipsTheStacksOwnShell(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder
                .build(specFor(workspace).skillParser(new MarkdownSkillParser()).build())) {
            // The shell is what the entry stands for: a second one would be a second process pool that nothing
            // on this plan ever closes.
            assertThat(stack.teardownPlan()).noneMatch(line -> line.contains("skillHookShell"));
        }
    }

    @Test
    @DisplayName("without one, the stack owns its shell and closes it last")
    void withoutASuppliedParserTheStackOwnsTheShell(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace).build())) {
            assertThat(stack.teardownPlan()).anyMatch(line -> line.contains("skillHookShell"));
            assertThat(stack.teardownPlan().get(stack.teardownPlan().size() - 1)).contains("skillHookShell");
        }
    }

    private static OrcaAgentRuntime runtimeOf(AimonStack stack, AgentRuntimeId agentRuntimeId) {
        return stack.agentRuntimeRegistry().get(agentRuntimeId).map(OrcaAgentRuntime.class::cast).orElseThrow();
    }

    /**
     * A customizer that records when it was applied. Recorded from {@code registerHooks} because hook order is
     * the reason the ordering is specified at all.
     */
    private static AimonAgentCustomizer recordingCustomizer(int order, String name, List<String> applied) {
        return new AimonAgentCustomizer() {

            @Override
            public boolean supports(AgentDescriptor agent) {
                return true;
            }

            @Override
            public void registerHooks(AgentDescriptor agent, HookRegistry hooks) {
                applied.add(name);
            }

            @Override
            public int getOrder() {
                return order;
            }
        };
    }

    /** Minimal tool used only to prove a customizer's registration survived into the published runtime. */
    private static final class MarkerTool extends AbstractTool {

        private static final String TOOL_NAME = "Marker";

        private MarkerTool() {
            super(TOOL_NAME, "Marks that a customizer ran.", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("marked");
        }
    }
}
