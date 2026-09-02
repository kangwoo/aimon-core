package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.bootstrap.spec.SkillApprovalSpec;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.agent.InMemoryAgentApprovalStore;
import at.aimon.core.skill.policy.approval.DenyAllSkillApprovalChannel;
import at.aimon.core.skill.policy.pending.InMemoryPendingTurnRegistry;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.InMemorySessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.session.routing.DeploymentMode;

/**
 * The approval axis' four stores, supplied rather than defaulted.
 *
 * <p>
 * Every case here answers one of two questions, and the first is the one a spec seam can pass while being
 * useless: does the instance the caller handed over actually reach the collaborator that reads it? A spec that
 * carried a store and a builder that dropped it would satisfy any assertion made against the spec alone, so
 * each store is observed through the thing that uses it — the channel factory is handed the two approval
 * stores, the stack publishes the registry, the queue manager writes into the repository.
 *
 * <p>
 * The second half is about what the stack <em>says</em>. Supplying these stores is the only way a distributed
 * deployment stops re-asking approvals it already has, so the {@code distributed-approvals} degradation has to
 * shrink as they arrive and disappear when all three are there. A degradation that kept naming a store the
 * caller had replaced would train a reader to ignore it.
 */
class AimonStackApprovalSeamTest {

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

    private static AimonStackSpec.Builder specFor(Path workspace) {
        return AimonStackSpec.builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(STUB_LLM))
                .agent(AgentSpec.of(bundle("ops")));
    }

    /**
     * A distributed session spec whose five collaborators are stand-ins.
     *
     * <p>
     * None of them is exercised: assembly wires the router and stops, and no test here opens a session. What the
     * mode is for is the branch that decides whether to announce node-local approvals.
     */
    private static SessionSpec distributedSession() {
        return SessionSpec.builder().mode(DeploymentMode.DISTRIBUTED).nodeId("pod-a")
                .recordStore(mock(SessionRecordStore.class)).leaseStore(mock(SessionLeaseStore.class))
                .signalBus(mock(SessionSignalBus.class)).inbox(mock(SessionInbox.class))
                .idempotencyStore(mock(IdempotencyStore.class)).build();
    }

    @Test
    @DisplayName("the supplied approval stores are the pair the channel is built over")
    void suppliedApprovalStoresReachTheChannel(@TempDir Path workspace) {
        // The channel factory is the one place the builder hands both stores to caller code, and the policy chain
        // is wired from the same two locals — so seeing them here is seeing what the chain reads.
        final SessionApprovalStore sessionStore = new InMemorySessionApprovalStore();
        final AgentApprovalStore agentStore = new InMemoryAgentApprovalStore();
        final SessionApprovalStore[] seenSession = new SessionApprovalStore[1];
        final AgentApprovalStore[] seenAgent = new AgentApprovalStore[1];

        final SkillApprovalSpec approval = SkillApprovalSpec.channelFactory((session, agent) -> {
            seenSession[0] = session;
            seenAgent[0] = agent;
            return new DenyAllSkillApprovalChannel(session, agent);
        }).withSessionApprovalStore(sessionStore).withAgentApprovalStore(agentStore);

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace).skillApproval(approval).build())) {
            assertThat(stack).isNotNull();
            assertThat(seenSession[0]).isSameAs(sessionStore);
            assertThat(seenAgent[0]).isSameAs(agentStore);
        }
    }

    @Test
    @DisplayName("the supplied pending-turn registry is the one the stack publishes")
    void suppliedPendingTurnRegistryIsPublished(@TempDir Path workspace) {
        // What the stack publishes is also what the reaper sweeps and what /approve looks in — one field, three
        // readers — so identity here is the whole wiring.
        final PendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        final SkillApprovalSpec approval = SkillApprovalSpec.denyAll().withPendingTurnRegistry(registry);

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace).skillApproval(approval).build())) {
            assertThat(stack.pendingTurnRegistry()).isSameAs(registry);
        }
    }

    @Test
    @DisplayName("the supplied message queue repository is the one the manager writes into")
    void suppliedMessageQueueRepositoryReceivesEnqueuedInput(@TempDir Path workspace) {
        final InMemoryMessageQueueRepository repository = new InMemoryMessageQueueRepository();

        try (AimonStack stack = AimonStackBuilder
                .build(specFor(workspace).messageQueueRepository(repository).build())) {
            final AgentRuntimeId runtimeId = stack.primaryRuntimeId();
            stack.messageQueueManager()
                    .enqueue(QueuedInput.builder().inputText("deploy").agentRuntimeId(runtimeId).build());

            assertThat(repository.size()).isEqualTo(1);
            assertThat(repository.peek(input -> true)).get().extracting(QueuedInput::getInputText).isEqualTo("deploy");
        }
    }

    @Test
    @DisplayName("distributed sessions with all three stores supplied say nothing")
    void distributedWithEveryStoreSuppliedIsSilent(@TempDir Path workspace) {
        // Whether those implementations genuinely span nodes is not something the builder can inspect. Having been
        // handed all three it says nothing rather than guessing in either direction — the same rule the two halves
        // of scheduling durability follow.
        final SkillApprovalSpec approval = SkillApprovalSpec.denyAll()
                .withSessionApprovalStore(new InMemorySessionApprovalStore())
                .withAgentApprovalStore(new InMemoryAgentApprovalStore())
                .withPendingTurnRegistry(new InMemoryPendingTurnRegistry());

        try (AimonStack stack = AimonStackBuilder
                .build(specFor(workspace).session(distributedSession()).skillApproval(approval).build())) {
            assertThat(stack.degradations().has("distributed-approvals")).isFalse();
        }
    }

    @Test
    @DisplayName("distributed sessions with no store supplied name all three")
    void distributedWithNoStoreSuppliedNamesAllThree(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace).session(distributedSession()).build())) {
            assertThat(stack.degradations().has("distributed-approvals")).isTrue();
            assertThat(stack.degradations().describe()).contains("all 3 of the approval-axis stores")
                    .contains("withSessionApprovalStore").contains("withAgentApprovalStore")
                    .contains("withPendingTurnRegistry");
        }
    }

    @Test
    @DisplayName("a node-local message queue is not announced, because sharing it is not the fix")
    void distributedSaysNothingAboutTheMessageQueue(@TempDir Path workspace) {
        // The queue is the one store of the four whose node-local default is not a degradation to be talked out
        // of. A drain filters on AgentRuntimeId alone and a queued entry carries no SessionId, so a shared
        // repository hands a user's follow-up to whichever node next runs a turn for that agent runtime —
        // possibly into another session's turn. Naming it beside the approval stores would read as advice to
        // share it, and that advice would be wrong.
        final SkillApprovalSpec approval = SkillApprovalSpec.denyAll()
                .withSessionApprovalStore(new InMemorySessionApprovalStore())
                .withAgentApprovalStore(new InMemoryAgentApprovalStore())
                .withPendingTurnRegistry(new InMemoryPendingTurnRegistry());

        try (AimonStack stack = AimonStackBuilder
                .build(specFor(workspace).session(distributedSession()).skillApproval(approval).build())) {
            assertThat(stack.degradations().describe()).doesNotContain("messageQueueRepository")
                    .doesNotContain("queued input");
        }
    }

    @Test
    @DisplayName("a half-configured distributed stack names only what is still node-local")
    void distributedNamesOnlyTheStoresLeftNodeLocal(@TempDir Path workspace) {
        // The half-configured shape is the one worth reading about: shared approvals under a node-local registry
        // stop the re-asking and still leave /approve unable to find the turn. A message that kept naming the
        // store already replaced would be read past.
        final SkillApprovalSpec approval = SkillApprovalSpec.denyAll()
                .withSessionApprovalStore(new InMemorySessionApprovalStore())
                .withAgentApprovalStore(new InMemoryAgentApprovalStore());

        try (AimonStack stack = AimonStackBuilder
                .build(specFor(workspace).session(distributedSession()).skillApproval(approval).build())) {
            assertThat(stack.degradations().has("distributed-approvals")).isTrue();
            assertThat(stack.degradations().describe()).contains("1 of the 3 approval-axis stores")
                    .contains("withPendingTurnRegistry").doesNotContain("withSessionApprovalStore")
                    .doesNotContain("withAgentApprovalStore");
        }
    }
}
