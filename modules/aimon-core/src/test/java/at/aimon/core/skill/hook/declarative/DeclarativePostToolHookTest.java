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
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

class DeclarativePostToolHookTest {

    private static final HookRegistry REGISTRY = new DefaultHookRegistry();
    private static final Environment ENV = Environment.createDefault();

    @Test
    void execute_matching_runsExecutorWithSuccessStatusEnv() {
        RecordingExecutor exec = new RecordingExecutor();
        ShellAction action = new ShellAction("echo done", Duration.ofSeconds(1));
        DeclarativePostToolHook hook = new DeclarativePostToolHook("my-skill", NameOnlyPredicate.ANY, action, exec);

        HookResult result = hook.execute(contextFor("Read", ToolUseResult.success("call-1", "ok")));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(exec.calls).hasSize(1);
        Map<String, String> env = exec.calls.get(0).env;
        assertThat(env).containsEntry(SkillHookEnv.AIMON_HOOK_EVENT, "postTool")
                .containsEntry(SkillHookEnv.AIMON_SKILL_NAME, "my-skill")
                .containsEntry(SkillHookEnv.AIMON_TOOL_NAME, "Read")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_NAME, "default-agent")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_TYPE, InvokerType.MAIN_AGENT.name())
                .containsEntry(SkillHookEnv.AIMON_ITERATION, "5")
                .containsEntry(SkillHookEnv.AIMON_TOOL_RESULT_STATUS, "success");
    }

    @Test
    void execute_matching_propagatesErrorStatusOnError() {
        RecordingExecutor exec = new RecordingExecutor();
        DeclarativePostToolHook hook = new DeclarativePostToolHook("my-skill", NameOnlyPredicate.ANY,
                new ShellAction("noop", Duration.ofSeconds(1)), exec);

        hook.execute(contextFor("Read", ToolUseResult.error("call-1", "boom")));

        assertThat(exec.calls).hasSize(1);
        assertThat(exec.calls.get(0).env).containsEntry(SkillHookEnv.AIMON_TOOL_RESULT_STATUS, "error");
    }

    @Test
    void execute_nonMatchingTool_doesNotInvokeExecutor() {
        RecordingExecutor exec = new RecordingExecutor();
        DeclarativePostToolHook hook = new DeclarativePostToolHook("my-skill", NameOnlyPredicate.of("Bash"),
                new ShellAction("echo", Duration.ofSeconds(1)), exec);

        HookResult result = hook.execute(contextFor("Read", ToolUseResult.success("c", "ok")));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(exec.calls).isEmpty();
    }

    @Test
    void execute_nullContext_throws() {
        DeclarativePostToolHook hook = new DeclarativePostToolHook("s", NameOnlyPredicate.ANY,
                new ShellAction("x", Duration.ofSeconds(1)), NoOpShellActionExecutor.INSTANCE);

        assertThatThrownBy(() -> hook.execute(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullArgs_throw() {
        ShellAction action = new ShellAction("x", Duration.ofSeconds(1));

        assertThatThrownBy(() -> new DeclarativePostToolHook(null, NameOnlyPredicate.ANY, action,
                NoOpShellActionExecutor.INSTANCE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeclarativePostToolHook("s", null, action, NoOpShellActionExecutor.INSTANCE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> new DeclarativePostToolHook("s", NameOnlyPredicate.ANY, null, NoOpShellActionExecutor.INSTANCE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeclarativePostToolHook("s", NameOnlyPredicate.ANY, action, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static PostToolContext contextFor(String toolName, ToolUseResult result) {
        return PostToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).toolUse(ToolUse.of("call-1", toolName, Map.of()))
                .toolUseResult(result).iterationCount(5).build();
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
}
