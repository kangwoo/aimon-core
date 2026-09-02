package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueRepository;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.agent.InMemoryAgentApprovalStore;
import at.aimon.core.skill.policy.pending.InMemoryPendingTurnRegistry;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.InMemorySessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/**
 * What {@code aimonApplicationContributions} owes the beans it resolves: an order to be destroyed in, and a
 * refusal to guess.
 *
 * <p>
 * Neither is visible in the value the method returns, which is why they get a test of their own. The first
 * was believed rather than measured for as long as it was only written down — an {@code ObjectProvider}'s
 * {@code getIfAvailable()} reads like dependency resolution and is not: it hands over an instance without
 * telling the factory who asked, so nothing constrains the teardown order and reverse creation order decides
 * it. Reverse creation order happens to be right here, which is exactly what makes the belief survive.
 * Asserting the edge is what turns "right" into "right for a reason".
 */
class AimonApplicationContributionsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(AimonLlmAutoConfiguration.class, AimonFileSystemAutoConfiguration.class,
                    AimonSessionAutoConfiguration.class, AimonSchedulingAutoConfiguration.class,
                    AimonKnowledgeAutoConfiguration.class, AimonMemoryAutoConfiguration.class,
                    AimonObservabilityAutoConfiguration.class, AimonAutoConfiguration.class));

    private ApplicationContextRunner minimal(Path workspace) {
        return runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.llm.api-key=test-key",
                "aimon.agent-defaults.default-agent=test-agent");
    }

    private static String[] dependentsOf(ConfigurableApplicationContext ctx, String beanName) {
        final ConfigurableListableBeanFactory beanFactory = ctx.getBeanFactory();
        return beanFactory.getDependentBeans(beanName);
    }

    @Test
    @DisplayName("every contributed store is destroyed after the contributions that resolved it")
    void contributedStoresAreDestroyedAfterTheStack(@TempDir Path workspace) {
        // The edge points at the contributions bean, which the spec depends on, which the stack depends on:
        // Spring destroys dependents first, so the whole stack is down before any of these four is closed. A
        // store backed by a connection is the case that cares — the stack is still writing approvals into it
        // during its own ordered teardown.
        minimal(workspace).withUserConfiguration(StoreConfiguration.class).run(ctx -> {
            final ConfigurableApplicationContext context = (ConfigurableApplicationContext) ctx
                    .getSourceApplicationContext();
            for (String bean : new String[]{"agentStore", "sessionStore", "registry", "queue"}) {
                assertThat(dependentsOf(context, bean)).as("destruction edge for %s", bean)
                        .contains(AimonAutoConfiguration.EnabledConfiguration.CONTRIBUTIONS_BEAN);
            }
        });
    }

    @Test
    @DisplayName("the registry re-exported from the stack is not given an edge back into the stack")
    void theReExportedRegistryGetsNoEdge(@TempDir Path workspace) {
        // The other half of the cycle rule. With no application registry the only bean of that type is the
        // stack's own re-export, and an edge from it to the contributions would invert the teardown of the
        // thing it was read out of.
        minimal(workspace).run(ctx -> {
            final ConfigurableApplicationContext context = (ConfigurableApplicationContext) ctx
                    .getSourceApplicationContext();
            assertThat(dependentsOf(context, AimonAutoConfiguration.EnabledConfiguration.PENDING_TURN_REGISTRY_BEAN))
                    .doesNotContain(AimonAutoConfiguration.EnabledConfiguration.CONTRIBUTIONS_BEAN);
        });
    }

    @Test
    @DisplayName("two application registries and no primary is refused, not silently narrowed to one")
    void ambiguousRegistryIsRefused(@TempDir Path workspace) {
        // The lookup this one goes through is by bean definition rather than through a provider, so the
        // provider's refusal to guess is not inherited and has to be reproduced. Taking the first would be the
        // worst outcome available: the re-export has already backed off, so the application's own injection
        // point throws while the stack quietly suspends turns into whichever one came first.
        minimal(workspace).withUserConfiguration(TwoRegistryConfiguration.class).run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class));
    }

    @Test
    @DisplayName("two application registries with a primary resolve to the primary")
    void primaryRegistryWins(@TempDir Path workspace) {
        minimal(workspace).withUserConfiguration(PrimaryRegistryConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(AimonStackSpec.class).getSkillApproval().getPendingTurnRegistry())
                        .contains(PrimaryRegistryConfiguration.PREFERRED));
    }

    /** Stands in for an application that backs the four stores with shared infrastructure. */
    @Configuration(proxyBeanMethods = false)
    static class StoreConfiguration {

        @Bean
        AgentApprovalStore agentStore() {
            return new InMemoryAgentApprovalStore();
        }

        @Bean
        SessionApprovalStore sessionStore() {
            return new InMemorySessionApprovalStore();
        }

        @Bean
        PendingTurnRegistry registry() {
            return new InMemoryPendingTurnRegistry();
        }

        @Bean
        MessageQueueRepository queue() {
            return new InMemoryMessageQueueRepository();
        }
    }

    /** Two registries and nothing to choose between them. */
    @Configuration(proxyBeanMethods = false)
    static class TwoRegistryConfiguration {

        @Bean
        PendingTurnRegistry firstRegistry() {
            return new InMemoryPendingTurnRegistry();
        }

        @Bean
        PendingTurnRegistry secondRegistry() {
            return new InMemoryPendingTurnRegistry();
        }
    }

    /** Two registries with the choice made. */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryRegistryConfiguration {

        static final PendingTurnRegistry PREFERRED = new InMemoryPendingTurnRegistry();

        @Bean
        PendingTurnRegistry plainRegistry() {
            return new InMemoryPendingTurnRegistry();
        }

        @Bean
        @Primary
        PendingTurnRegistry preferredRegistry() {
            return PREFERRED;
        }
    }
}
