package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.filesystem.VirtualFileSystem;
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
    @DisplayName("the sibling slices register the same edge, for the beans that actually hold connections")
    void siblingSlicesRegisterTheSameEdge(@TempDir Path workspace) {
        // The rule is not the approval axis' — it belongs to every provider-resolved contribution, and the ones
        // most likely to be a live connection are here rather than there: a SessionRecordStore under
        // store=postgres, a VirtualFileSystem over GridFS or S3. Each edge points at the bean that gathered it,
        // and the stack hangs off all of them through the spec.
        minimal(workspace).withUserConfiguration(SliceConfiguration.class).run(ctx -> {
            final ConfigurableApplicationContext context = (ConfigurableApplicationContext) ctx
                    .getSourceApplicationContext();
            assertThat(dependentsOf(context, "sliceRecordStore")).as("destruction edge for the record store")
                    .contains(AimonSessionAutoConfiguration.SESSION_SPEC_BEAN);
            assertThat(dependentsOf(context, "sliceFileSystem")).as("destruction edge for the filesystem")
                    .contains(AimonFileSystemAutoConfiguration.FILE_SYSTEM_SPEC_BEAN);
        });
    }

    @Test
    @DisplayName("a registry the application names after the re-export fails the context instead of diverging")
    void registryNamedAfterTheReExportIsRefused(@TempDir Path workspace) {
        // The one shape the name-based exclusion cannot tell apart, and it is silent on its own: the condition
        // withdraws the re-export by type while the input seam skips the bean by name, so the application injects
        // one registry and the stack suspends into another. /approve then finds nothing, with nothing to read.
        minimal(workspace).withUserConfiguration(ShadowingRegistryConfiguration.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(AimonAutoConfiguration.EnabledConfiguration.PENDING_TURN_REGISTRY_BEAN)
                        .hasMessageContaining("find nothing to release"));
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

    /** Two of the contributions that other slices gather, both of the kind that is really a connection. */
    @Configuration(proxyBeanMethods = false)
    static class SliceConfiguration {

        @Bean
        SessionRecordStore sliceRecordStore() {
            return new InMemorySessionRecordStore();
        }

        @Bean
        VirtualFileSystem sliceFileSystem() {
            // Stubbed rather than bare: assembly reads the working directory, and a mock's null fails the stack
            // before any edge could be asserted.
            final VirtualFileSystem fileSystem = mock(VirtualFileSystem.class);
            when(fileSystem.getWorkingDirectory()).thenReturn("/");
            return fileSystem;
        }
    }

    /** An application registry wearing the name this starter re-exports its own under. */
    @Configuration(proxyBeanMethods = false)
    static class ShadowingRegistryConfiguration {

        @Bean
        PendingTurnRegistry aimonPendingTurnRegistry() {
            return new InMemoryPendingTurnRegistry();
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
