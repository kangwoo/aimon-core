package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.bootstrap.spec.SkillApprovalChannelFactory;
import at.aimon.bootstrap.spec.SkillApprovalSpec;
import at.aimon.core.skill.policy.approval.SkillApprovalChannel;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;

/**
 * One case per {@code aimon.skill.approval.mode} value, plus the two keys that cut across all four.
 *
 * <p>
 * The subject is the {@link SkillApprovalSpec} the starter contributes, not the answer a skill eventually
 * gets: what a channel decides is {@code aimon-core}'s subject, and asserting it here would only re-test the
 * policy chain through four extra layers. What the starter owns is the translation — which mode produces
 * which channel shape, which bean it is taken from, and which combinations are refused rather than guessed
 * at.
 *
 * <p>
 * Each case still assembles a real stack (the spec bean and the stack bean live in the same
 * autoconfiguration), so a mode that produced a spec the builder cannot assemble would fail here rather than
 * at a deployment's first skill.
 */
class AimonSkillApprovalTest {

    private static final String AGENT = "test-agent";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(AimonLlmAutoConfiguration.class, AimonFileSystemAutoConfiguration.class,
                    AimonSessionAutoConfiguration.class, AimonSchedulingAutoConfiguration.class,
                    AimonKnowledgeAutoConfiguration.class, AimonMemoryAutoConfiguration.class,
                    AimonObservabilityAutoConfiguration.class, AimonAutoConfiguration.class));

    private ApplicationContextRunner minimal(Path workspace) {
        return runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.llm.api-key=test-key",
                "aimon.agent-defaults.default-agent=" + AGENT);
    }

    private static SkillApprovalSpec approvalOf(ApplicationContext ctx) {
        return ctx.getBean(AimonStackSpec.class).getSkillApproval();
    }

    @Test
    @DisplayName("the unconfigured default denies, and says so out loud")
    void defaultIsDenyAll(@TempDir Path workspace) {
        // Fail-closed is only half of it: a stack that denies every skill in silence is indistinguishable from
        // one whose skills are merely unused, so the degradation is the part a deployment can act on.
        minimal(workspace).run(ctx -> {
            assertThat(approvalOf(ctx).getChannelMode()).isEqualTo(SkillApprovalSpec.ChannelMode.DENY_ALL);
            assertThat(ctx.getBean(AimonStack.class).degradations().has("skill-approval")).isTrue();
        });
    }

    @Test
    @DisplayName("allow-list binds the named skills")
    void allowListBindsTheNames(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.skill.approval.mode=allow-list",
                "aimon.skill.approval.allow=deploy,rollback").run(ctx -> {
                    final SkillApprovalSpec approval = approvalOf(ctx);
                    assertThat(approval.getChannelMode()).isEqualTo(SkillApprovalSpec.ChannelMode.ALLOW_LIST);
                    assertThat(approval.getAllowedSkills()).containsExactlyInAnyOrder("deploy", "rollback");
                    assertThat(ctx.getBean(AimonStack.class).degradations().has("skill-approval")).isFalse();
                });
    }

    @Test
    @DisplayName("an allow-list with nothing on it is deny-all wearing another name, and is reported")
    void emptyAllowListDegrades(@TempDir Path workspace) {
        // The likely cause is a missing key rather than a deliberate empty list, and the two are
        // indistinguishable after binding — so the stack reports instead of guessing which one it is.
        minimal(workspace).withPropertyValues("aimon.skill.approval.mode=allow-list").run(ctx -> {
            assertThat(approvalOf(ctx).getAllowedSkills()).isEmpty();
            assertThat(ctx.getBean(AimonStack.class).degradations().describe()).contains("allow-list is empty");
        });
    }

    @Test
    @DisplayName("suspend withholds the channel and publishes the registry that holds the turn")
    void suspendWithholdsTheChannel(@TempDir Path workspace) {
        // The registry bean is the whole usable surface of this mode: with no channel, the only way a
        // suspended turn is ever answered is an application reaching into it out of band.
        minimal(workspace).withPropertyValues("aimon.skill.approval.mode=suspend").run(ctx -> {
            assertThat(approvalOf(ctx).getChannelMode()).isEqualTo(SkillApprovalSpec.ChannelMode.SUSPEND);
            assertThat(ctx.getBean(PendingTurnRegistry.class))
                    .isSameAs(ctx.getBean(AimonStack.class).pendingTurnRegistry());
            assertThat(ctx.getBean(AimonStack.class).degradations().describe()).contains("pending-turn registry");
        });
    }

    @Test
    @DisplayName("channel takes the application's channel bean")
    void channelModeTakesTheChannelBean(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.skill.approval.mode=channel")
                .withUserConfiguration(ApplicationChannelConfiguration.class).run(ctx -> {
                    final SkillApprovalSpec approval = approvalOf(ctx);
                    assertThat(approval.getChannelMode()).isEqualTo(SkillApprovalSpec.ChannelMode.SUPPLIED);
                    assertThat(approval.getSuppliedChannel()).contains(ApplicationChannelConfiguration.INSTANCE);
                });
    }

    @Test
    @DisplayName("channel takes a factory bean when the channel needs the stack's own approval stores")
    void channelModeTakesTheFactoryBean(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.skill.approval.mode=channel")
                .withUserConfiguration(ApplicationChannelFactoryConfiguration.class).run(ctx -> {
                    final SkillApprovalSpec approval = approvalOf(ctx);
                    assertThat(approval.getChannelMode()).isEqualTo(SkillApprovalSpec.ChannelMode.SUPPLIED_FACTORY);
                    assertThat(approval.getChannelFactory()).contains(ApplicationChannelFactoryConfiguration.INSTANCE);
                });
    }

    @Test
    @DisplayName("both channel shapes at once are refused by naming both types")
    void bothChannelShapesAreRefused(@TempDir Path workspace) {
        // Preferring one silently would leave the other bean defined, wired to nothing, and looking used.
        minimal(workspace).withPropertyValues("aimon.skill.approval.mode=channel")
                .withUserConfiguration(ApplicationChannelConfiguration.class,
                        ApplicationChannelFactoryConfiguration.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(SkillApprovalChannel.class.getName())
                        .hasStackTraceContaining(SkillApprovalChannelFactory.class.getName()));
    }

    @Test
    @DisplayName("channel with no channel bean fails instead of quietly denying")
    void channelModeWithoutABeanFails(@TempDir Path workspace) {
        // Falling back to deny-all would leave a deployment believing its own channel was being asked.
        minimal(workspace).withPropertyValues("aimon.skill.approval.mode=channel")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SKILL_APPROVAL_MODE)
                        .hasStackTraceContaining(SkillApprovalChannel.class.getName()));
    }

    @Test
    @DisplayName("the pending-turn TTL reaches the spec under a mode that never suspends")
    void pendingTurnTtlIsModeIndependent(@TempDir Path workspace) {
        // Deny, not suspend, on purpose: a TTL that survives only the mode it is used by is a setting that
        // silently reverts when a deployment flips the mode back.
        minimal(workspace).withPropertyValues("aimon.skill.approval.pending-turn-ttl=5m")
                .run(ctx -> assertThat(approvalOf(ctx).getPendingTurnTtl()).contains(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("an unset TTL leaves the framework default to the executor")
    void unsetTtlIsNotRestatedByTheStarter(@TempDir Path workspace) {
        // Empty rather than 30 minutes: the default lives in OrcaAgentExecutor, and a starter that repeated it
        // would be a second place to change it.
        minimal(workspace).run(ctx -> assertThat(approvalOf(ctx).getPendingTurnTtl()).isEmpty());
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationChannelConfiguration {

        static final SkillApprovalChannel INSTANCE = mock(SkillApprovalChannel.class);

        @Bean
        SkillApprovalChannel applicationApprovalChannel() {
            return INSTANCE;
        }
    }

    /** Stands in for a channel that must record its answer into the stack's own approval stores. */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationChannelFactoryConfiguration {

        static final SkillApprovalChannelFactory INSTANCE = (session, agent) -> mock(SkillApprovalChannel.class);

        @Bean
        SkillApprovalChannelFactory applicationApprovalChannelFactory() {
            return INSTANCE;
        }
    }
}
