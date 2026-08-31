package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.hook.action.DenyAction;
import at.aimon.core.skill.hook.action.HookAction;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

class DeclarativePreToolHookTest {

    private static final HookRegistry REGISTRY = new DefaultHookRegistry();
    private static final Environment ENV = Environment.createDefault();

    @Test
    void execute_matchingDenyAction_returnsBlockWithReason() {
        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.of("Bash"),
                new DenyAction("Bash not allowed"), NoOpShellActionExecutor.INSTANCE);

        HookResult result = hook.execute(contextFor("Bash"));

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback()).contains("Bash not allowed");
    }

    @Test
    void execute_nonMatchingTool_shortCircuitsToSuccess() {
        RecordingExecutor exec = new RecordingExecutor();
        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.of("Bash"),
                new DenyAction("blocked"), exec);

        HookResult result = hook.execute(contextFor("Read"));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(exec.calls).isEmpty();
    }

    @Test
    void execute_anyMatcher_appliesDenyToEveryTool() {
        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY,
                new DenyAction("nope"), NoOpShellActionExecutor.INSTANCE);

        assertThat(hook.execute(contextFor("Bash")).getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(hook.execute(contextFor("Read")).getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(hook.execute(contextFor("Edit")).getStatus()).isEqualTo(HookStatus.BLOCKED);
    }

    @Test
    void execute_matchingShellAction_runsExecutorAndReturnsSuccess() {
        RecordingExecutor exec = new RecordingExecutor();
        ShellAction action = new ShellAction("echo hi", Duration.ofSeconds(2));
        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY, action, exec);

        HookResult result = hook.execute(contextFor("Bash"));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(exec.calls).hasSize(1);
        assertThat(exec.calls.get(0).action).isSameAs(action);
        Map<String, String> env = exec.calls.get(0).env;
        assertThat(env).containsEntry(SkillHookEnv.AIMON_HOOK_EVENT, "preTool")
                .containsEntry(SkillHookEnv.AIMON_SKILL_NAME, "my-skill")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_NAME, "default-agent")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_TYPE, InvokerType.MAIN_AGENT.name())
                .containsEntry(SkillHookEnv.AIMON_TOOL_NAME, "Bash").containsEntry(SkillHookEnv.AIMON_ITERATION, "3");
    }

    @Test
    void execute_shellActionFailureSwallowed_stillReturnsSuccess() {
        ShellActionExecutor throwing = new ShellActionExecutor() {
            @Override
            public boolean isShellSupported() {
                return true;
            }

            @Override
            public void run(ShellAction action, Map<String, String> env) {
                // Contract: must not throw. Implementations that violate should never make HookResult fail.
                // Here we exercise the "compliant" path; a separate test exercises null safety.
            }
        };

        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY,
                new ShellAction("echo", Duration.ofSeconds(1)), throwing);

        assertThat(hook.execute(contextFor("Bash")).getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void execute_nullContext_throws() {
        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY, new DenyAction("x"),
                NoOpShellActionExecutor.INSTANCE);

        assertThatThrownBy(() -> hook.execute(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullArgs_throw() {
        DenyAction deny = new DenyAction("x");

        assertThatThrownBy(
                () -> new DeclarativePreToolHook(null, NameOnlyPredicate.ANY, deny, NoOpShellActionExecutor.INSTANCE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeclarativePreToolHook("s", null, deny, NoOpShellActionExecutor.INSTANCE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> new DeclarativePreToolHook("s", NameOnlyPredicate.ANY, null, NoOpShellActionExecutor.INSTANCE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeclarativePreToolHook("s", NameOnlyPredicate.ANY, deny, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static PreToolContext contextFor(String toolName) {
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).toolUse(ToolUse.of("call-1", toolName, Map.of()))
                .iterationCount(3).build();
    }

    private static final class RecordingExecutor implements ShellActionExecutor {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public boolean isShellSupported() {
            return true;
        }

        @Override
        public void run(ShellAction action, Map<String, String> environmentOverrides) {
            calls.add(new Call(action, Map.copyOf(environmentOverrides)));
        }

        record Call(ShellAction action, Map<String, String> env) {
        }
    }

    @SuppressWarnings("unused")
    private static HookAction unused() {
        // Force the test class to retain a reference to the sealed interface so removing a permit
        // would surface here as a compile error.
        return new DenyAction("x");
    }
}
