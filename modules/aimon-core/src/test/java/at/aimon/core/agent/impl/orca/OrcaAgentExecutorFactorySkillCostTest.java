package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.quota.QuotaGuardFactory;
import at.aimon.core.agent.quota.QuotaKey;
import at.aimon.core.agent.quota.QuotaLimit;
import at.aimon.core.agent.quota.QuotaScope;
import at.aimon.core.agent.quota.QuotaUsage;
import at.aimon.core.agent.quota.QuotaWindow;
import at.aimon.core.agent.quota.impl.DefaultQuotaScopeResolver;
import at.aimon.core.agent.quota.impl.InMemoryUsageLedger;
import at.aimon.core.agent.quota.impl.StaticQuotaPolicy;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * UQ-1: {@link OrcaAgentExecutorFactory#withCostEstimator(at.aimon.core.llm.cost.CostEstimator)} reaches the
 * {@code LlmSkillExecutor} inside the factory's default command execution manager, so an inline skill typed as
 * {@code /audit} is charged to the session's quota with its price, as the turn's own calls are.
 */
@DisplayName("OrcaAgentExecutorFactory threads the cost estimator into the /skill loop")
class OrcaAgentExecutorFactorySkillCostTest {

    private static final SessionId SESSION = SessionId.of("conv-skill-cost");
    private static final QuotaKey SESSION_TOTAL = QuotaKey.of(QuotaScope.session(SESSION), QuotaWindow.TOTAL, "");

    @TempDir
    Path tempDir;

    private final InMemoryUsageLedger ledger = new InMemoryUsageLedger();
    private LocalExecutionEnvironmentProvider environmentProvider;

    @AfterEach
    void tearDown() {
        if (environmentProvider != null) {
            environmentProvider.close();
        }
    }

    @Test
    @DisplayName("with a cost estimator wired, a /skill call is charged with its price")
    void slashSkillIsPriced() {
        final QuotaUsage used = runSlashSkill(new OrcaAgentExecutorFactory()
                .withCostEstimator((model, usage) -> Money.usd(0.02)).withQuotaGuardFactory(quotaGuardFactory()));

        assertThat(used.getTokens()).isEqualTo(20);
        assertThat(used.getCost()).isEqualTo(Money.usd(0.02));
    }

    @Test
    @DisplayName("without a cost estimator, a /skill call is charged tokens only")
    void slashSkillIsUnpricedByDefault() {
        final QuotaUsage used = runSlashSkill(
                new OrcaAgentExecutorFactory().withQuotaGuardFactory(quotaGuardFactory()));

        assertThat(used.getTokens()).isEqualTo(20);
        assertThat(used.getCost()).isEqualTo(Money.zeroUsd());
    }

    private QuotaGuardFactory quotaGuardFactory() {
        return QuotaGuardFactory.builder().ledger(ledger)
                .policy(StaticQuotaPolicy.builder()
                        .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(1_000).build()).build())
                .resolver(DefaultQuotaScopeResolver.create()).build();
    }

    private QuotaUsage runSlashSkill(OrcaAgentExecutorFactory factory) {
        final OrcaAgentExecutor executor = factory.create(new SingleAnswerLlmClient(),
                new DefaultTranscriptManager(new InMemorySessionRecordStore()));

        final OrcaAgentExecutionResult result = executor.execute(runtime(),
                OrcaAgentExecutionRequest.builder().userInput("/audit").sessionId(SESSION).build());

        assertThat(result.isSuccess()).isTrue();
        return ledger.get(List.of(SESSION_TOTAL)).get(SESSION_TOTAL);
    }

    private OrcaAgentRuntime runtime() {
        environmentProvider = LocalExecutionEnvironmentProvider.builder().workspaceRoot(tempDir.resolve("workspace"))
                .contentSearch(false).build();
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final SkillRegistry skillRegistry = new SingleSkillRegistry(Skill.builder().name("audit")
                .metadata(SkillMetadata.builder().name("audit").description("fixture — audit")
                        .invokePolicy(InvokePolicy.of(true, true)).build())
                .content(SkillContent.of("Audit the module")).build());
        final DefaultCommandRegistry commandRegistry = new DefaultCommandRegistry(List.of(), skillRegistry, fileSystem,
                ".aimon/commands");
        commandRegistry.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(2).systemPrompt("You are a test agent")
                        .model(LlmModel.builder().name("gpt-4o").build()).build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(commandRegistry).skillRegistry(skillRegistry)
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .controlFileSystem(fileSystem).executionEnvironmentProvider(environmentProvider).build();
    }

    /** Answers every call with one final answer and fixed token usage. */
    private static final class SingleAnswerLlmClient implements LlmClient {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.of("audited", List.of(), TokenUsage.of(10, 10, 20), StopReason.END_TURN);
        }

        @Override
        public String getProviderName() {
            return "SingleAnswer";
        }
    }

    /** A {@link SkillRegistry} holding one skill. */
    private static final class SingleSkillRegistry implements SkillRegistry {
        private final Skill skill;

        SingleSkillRegistry(Skill skill) {
            this.skill = skill;
        }

        @Override
        public Optional<Skill> getSkill(String skillName) {
            return skill.getName().equals(skillName) ? Optional.of(skill) : Optional.empty();
        }

        @Override
        public List<Skill> getAllSkills() {
            return new ArrayList<>(List.of(skill));
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
