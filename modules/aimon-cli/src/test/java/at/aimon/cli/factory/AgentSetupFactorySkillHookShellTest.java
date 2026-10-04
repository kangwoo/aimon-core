package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.base.UserLocale;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.exception.SkillParseException;
import at.aimon.core.skill.parser.SkillParser;

/**
 * EE-12 on the CLI assembly: the skill parser the CLI hands to both the stack and the bundle loader accepts
 * {@code shell} hook actions without being given a shell. Such an action runs in the execution environment of the
 * execution it fires in, so there is no host shell for a skill author to reach.
 */
@DisplayName("AgentSetupFactory skill parser: shell hooks without a host shell")
class AgentSetupFactorySkillHookShellTest {

    private static String skill(String hooks) {
        return "---\nname: sample\ndescription: sample skill\nhooks:\n" + hooks + "---\n\n# sample\n";
    }

    @Test
    @DisplayName("accepts a shell action on an in-execution event")
    void acceptsShellActionsOnInExecutionEvents() {
        final SkillParser parser = AgentSetupFactory.createShellAwareSkillParser();

        final Skill parsed = parser.parse("sample",
                skill("  onStart:\n    - action: { type: shell, command: \"echo hi\" }\n"));

        assertThat(parsed.getMetadata().getHooks().getOnStartHooks()).hasSize(1);
    }

    @Test
    @DisplayName("the parsed hook does not fall back to the host when its context has no execution environment")
    void parsedHookDoesNotRunWithoutAnExecutionEnvironment() {
        final SkillParser parser = AgentSetupFactory.createShellAwareSkillParser();
        final Skill parsed = parser.parse("sample",
                skill("  onStart:\n    - action: { type: shell, command: \"exit 0\" }\n"));

        // On the host "exit 0" would let the turn start. With no environment the command is not run at all, and a
        // guard that could not run blocks (fail-closed) — naming the missing environment, not an exit code.
        final HookResult result = parsed.getMetadata().getHooks().getOnStartHooks().get(0).execute(noEnvironment());

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("guard hook 'sample' (onStart)")
                .contains("no execution environment");
    }

    @Test
    @DisplayName("failOpen: true in frontmatter lets a hook that could not run pass, still without a host fallback")
    void failOpenHookPassesWithoutAnExecutionEnvironment() {
        final SkillParser parser = AgentSetupFactory.createShellAwareSkillParser();
        final Skill parsed = parser.parse("sample",
                skill("  onStart:\n    - action: { type: shell, command: \"exit 2\" }\n      failOpen: true\n"));

        // On the host "exit 2" would block. It is not run, and the hook opted out of blocking when it cannot run.
        assertThat(parsed.getMetadata().getHooks().getOnStartHooks().get(0).execute(noEnvironment()).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    private static OnStartContext noEnvironment() {
        return OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).userLocale(UserLocale.createDefault()).userMessage("hi")
                .build();
    }

    @Test
    @DisplayName("rejects session- and config-lifecycle events, which have no execution environment")
    void rejectsOutOfExecutionEvents() {
        final SkillParser parser = AgentSetupFactory.createShellAwareSkillParser();

        assertThatThrownBy(() -> parser.parse("sample",
                skill("  onSessionStart:\n    - action: { type: shell, command: \"echo hi\" }\n")))
                .isInstanceOf(SkillParseException.class).hasStackTraceContaining("outside any execution");
    }
}
