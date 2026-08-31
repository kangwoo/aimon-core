package at.aimon.core.tools.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.fork.NoOpSkillForkExecutor;
import at.aimon.core.skill.hook.NoOpSkillHookActivator;
import at.aimon.core.skill.policy.AlwaysAllowSkillInvocationPolicy;
import at.aimon.core.skill.policy.RuleBasedSkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationRequest;
import at.aimon.core.skill.render.NoOpSkillContentRenderer;
import at.aimon.core.skill.render.SkillContentRenderer;

/**
 * Integration tests for the SK-11.1 policy hook inside {@link SkillTool}.
 *
 * <p>
 * Verifies that the invocation policy is consulted after the registry lookup but before the renderer/fork executor
 * runs, and that {@link SkillInvocationDecision#DENY} / {@link SkillInvocationDecision#ASK} short-circuit cleanly with
 * informative error messages.
 */
class SkillToolPolicyIntegrationTest {

    private MockSkillRegistry registry;
    private RecordingRenderer renderer;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        registry = new MockSkillRegistry();
        renderer = new RecordingRenderer();
        ctx = ToolContext.empty();
        registry.addSkill(skill("commit"));
    }

    @Test
    void allowDecision_LetsSkillRunAndInvokesRenderer() {
        final SkillTool tool = new SkillTool(registry, renderer, new NoOpSkillForkExecutor(),
                new NoOpSkillHookActivator(), AlwaysAllowSkillInvocationPolicy.INSTANCE);

        final ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "commit")), ctx);

        assertThat(result.isError()).isFalse();
        assertThat(renderer.callCount).isEqualTo(1);
    }

    @Test
    void denyDecision_ReturnsErrorWithoutRendering() {
        final SkillInvocationPolicy policy = req -> SkillInvocationDecision.DENY;
        final SkillTool tool = new SkillTool(registry, renderer, new NoOpSkillForkExecutor(),
                new NoOpSkillHookActivator(), policy);

        final ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "commit")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("denied by policy").contains("commit");
        // Renderer must NOT have been touched — that proves the policy ran *before* side-effects.
        assertThat(renderer.callCount).isZero();
    }

    @Test
    void askDecision_TreatedAsDenyWithDistinctMessageUntilSk114() {
        final SkillInvocationPolicy policy = req -> SkillInvocationDecision.ASK;
        final SkillTool tool = new SkillTool(registry, renderer, new NoOpSkillForkExecutor(),
                new NoOpSkillHookActivator(), policy);

        final ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "commit")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("requires user approval").contains("commit")
                .doesNotContain("denied by policy");
        assertThat(renderer.callCount).isZero();
    }

    @Test
    void policySeesRenderedSkillObjectAndArgs() {
        final java.util.concurrent.atomic.AtomicReference<SkillInvocationRequest> seen = new java.util.concurrent.atomic.AtomicReference<>();
        final SkillInvocationPolicy capturing = req -> {
            seen.set(req);
            return SkillInvocationDecision.ALLOW;
        };
        final SkillTool tool = new SkillTool(registry, renderer, new NoOpSkillForkExecutor(),
                new NoOpSkillHookActivator(), capturing);

        tool.execute(ToolInput.of(Map.of("skill", "commit", "args", "--scope=feat")), ctx);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getSkill().getName()).isEqualTo("commit");
        assertThat(seen.get().getArgs()).isEqualTo("--scope=feat");
    }

    @Test
    void unknownSkill_DoesNotConsultPolicy() {
        final boolean[] policyCalled = {false};
        final SkillInvocationPolicy spy = req -> {
            policyCalled[0] = true;
            return SkillInvocationDecision.ALLOW;
        };
        final SkillTool tool = new SkillTool(registry, renderer, new NoOpSkillForkExecutor(),
                new NoOpSkillHookActivator(), spy);

        final ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "ghost")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Skill not found");
        // Policy is consulted only after registry hit — saves churn on bogus skill names.
        assertThat(policyCalled[0]).isFalse();
    }

    @Test
    void ruleBasedPolicy_DenyPatternBlocksMatchingSkill() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().addDenyPattern("commit").build();
        final SkillTool tool = new SkillTool(registry, renderer, new NoOpSkillForkExecutor(),
                new NoOpSkillHookActivator(), policy);

        final ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "commit")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("denied by policy");
    }

    @Test
    void ruleBasedPolicy_SafeByDefaultLetsInlineSkillThrough() {
        // Default RuleBasedSkillInvocationPolicy: no rules + safeByDefault on. An INLINE skill with no hooks should
        // sail through.
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().build();
        final SkillTool tool = new SkillTool(registry, renderer, new NoOpSkillForkExecutor(),
                new NoOpSkillHookActivator(), policy);

        final ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "commit")), ctx);

        assertThat(result.isError()).isFalse();
    }

    private static Skill skill(String name) {
        return Skill.builder().name(name).metadata(SkillMetadata.builder().name(name).description("d").build())
                .content(SkillContent.of("body for " + name)).build();
    }

    private static final class RecordingRenderer implements SkillContentRenderer {
        private int callCount;

        @Override
        public String render(Skill skill, String args, at.aimon.core.skill.render.RenderContext context) {
            callCount++;
            return new NoOpSkillContentRenderer().render(skill, args, context);
        }
    }

    private static final class MockSkillRegistry implements SkillRegistry {
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
            // No-op for tests; skills are added directly via addSkill().
        }

        @Override
        public void reloadAll() {
            // No-op for tests; skills are added directly via addSkill().
        }
    }
}
