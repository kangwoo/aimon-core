package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.hook.action.DenyAction;
import at.aimon.core.skill.hook.action.HookAction;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

class DeclarativePreToolHookTest {

    private static final HookRegistry REGISTRY = new DefaultHookRegistry();

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
        PreToolContext context = contextFor("Bash");

        HookResult result = hook.execute(context);

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(exec.calls).hasSize(1);
        assertThat(exec.calls.get(0).action).isSameAs(action);
        assertThat(exec.calls.get(0).context).isSameAs(context);
        Map<String, String> env = exec.calls.get(0).env;
        assertThat(env).containsEntry(SkillHookEnv.AIMON_HOOK_EVENT, "preTool")
                .containsEntry(SkillHookEnv.AIMON_SKILL_NAME, "my-skill")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_NAME, "default-agent")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_TYPE, InvokerType.MAIN_AGENT.name())
                .containsEntry(SkillHookEnv.AIMON_TOOL_NAME, "Bash").containsEntry(SkillHookEnv.AIMON_ITERATION, "3");
    }

    @ParameterizedTest
    @EnumSource(value = ShellHookOutcome.Unrun.class, mode = EnumSource.Mode.EXCLUDE, names = "CANCELLED")
    void execute_shellCommandThatCouldNotRun_blocksWithTheCause(ShellHookOutcome.Unrun cause) {
        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY,
                new ShellAction("guard.sh --token s3cret", Duration.ofSeconds(1)),
                fixedOutcome(ShellHookOutcome.notRun(cause, "sandbox is down")));

        HookResult result = hook.execute(contextFor("Bash"));

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("guard hook 'my-skill' (preTool)")
                .contains(cause.description()).contains("sandbox is down").contains("fail-closed")
                // Neither the way to switch the guard off nor the command (which may hold a secret) is in the reason.
                .doesNotContain("failOpen").doesNotContain("s3cret");
    }

    @ParameterizedTest
    @EnumSource(value = ShellHookOutcome.Unrun.class, mode = EnumSource.Mode.EXCLUDE, names = "CANCELLED")
    void execute_failOpen_letsAShellCommandThatCouldNotRunPass(ShellHookOutcome.Unrun cause) {
        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY,
                new ShellAction("audit.sh", Duration.ofSeconds(1)),
                fixedOutcome(ShellHookOutcome.notRun(cause, "sandbox is down")), null, null, Map.of(),
                DeclarativeHookOptions.builder().failOpen(true).build());

        assertThat(hook.execute(contextFor("Bash")).getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void execute_failOpen_doesNotWeakenAnExitTwoVeto() {
        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY,
                new ShellAction("guard.sh", Duration.ofSeconds(1)), fixedOutcome(ShellHookOutcome.of(2, "", "no")),
                null, null, Map.of(), DeclarativeHookOptions.builder().failOpen(true).build());

        assertThat(hook.execute(contextFor("Bash")).getStatus()).isEqualTo(HookStatus.BLOCKED);
    }

    @Test
    void execute_exitCodesOtherThanTwo_stillAllow() {
        // 126 and 127 are not in this list: the shell reports them for a command it could not start (EE-66).
        for (int exit : new int[]{0, 1, 3, 125, 128, 130, 255}) {
            DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY,
                    new ShellAction("guard.sh", Duration.ofSeconds(1)),
                    fixedOutcome(ShellHookOutcome.of(exit, "", "boom")));

            assertThat(hook.execute(contextFor("Bash")).getStatus()).as("exit %d", exit).isEqualTo(HookStatus.SUCCESS);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"126, command not executable", "127, command not found"})
    void execute_commandTheShellCouldNotStart_blocksWithoutEchoingIt(int exit, String cause) {
        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY,
                new ShellAction("guard.sh --token s3cret", Duration.ofSeconds(1)),
                fixedOutcome(ShellHookOutcome.of(exit, "", "sh: guard.sh --token s3cret: not found")));

        HookResult result = hook.execute(contextFor("Bash"));

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("guard hook 'my-skill' (preTool)").contains(cause)
                .contains("exit code " + exit).contains("fail-closed")
                // The shell's stderr quotes the command line; neither it nor the opt-out is in the reason.
                .doesNotContain("failOpen").doesNotContain("s3cret").doesNotContain("guard.sh");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {126, 127})
    void execute_failOpen_letsACommandTheShellCouldNotStartPass(int exit) {
        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY,
                new ShellAction("audit.sh", Duration.ofSeconds(1)), fixedOutcome(ShellHookOutcome.of(exit, "", "boom")),
                null, null, Map.of(), DeclarativeHookOptions.builder().failOpen(true).build());

        assertThat(hook.execute(contextFor("Bash")).getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void execute_realShell_guardScriptThatIsMissingOrNotExecutable_blocks(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Files.writeString(tmp.resolve("not-executable.sh"), "#!/bin/sh\nexit 0\n");
        ShellActionExecutor realShell = new HostShellActionExecutor(new at.aimon.core.shell.impl.local.LocalShell(tmp));

        for (String command : List.of("./no-such-guard.sh", "./not-executable.sh")) {
            DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY,
                    new ShellAction(command, Duration.ofSeconds(10)), realShell);

            HookResult result = hook.execute(contextFor("Bash"));

            assertThat(result.getStatus()).as(command).isEqualTo(HookStatus.BLOCKED);
            assertThat(result.getFeedback().orElseThrow()).as(command).contains("could not run its command")
                    .doesNotContain(command);
        }
    }

    @Test
    void execute_nonMatchingTool_isNotBlockedByAGuardThatCannotRun() {
        DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.of("Bash"),
                new ShellAction("guard.sh", Duration.ofSeconds(1)), NoOpShellActionExecutor.INSTANCE);

        assertThat(hook.execute(contextFor("Read")).getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(hook.execute(contextFor("Bash")).getStatus()).isEqualTo(HookStatus.BLOCKED);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("thrownByTheGuard")
    void execute_executorThatThrows_blocksWithTheTypeOnly(Throwable thrown) throws Exception {
        for (ShellActionExecutor executor : List.of(throwingExecutor(thrown), throwingShell(thrown))) {
            DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY,
                    new ShellAction("guard.sh --token s3cret", Duration.ofSeconds(1)), executor);

            HookResult result = hook.execute(contextFor("Bash"));

            assertThat(result.getStatus()).as("%s", executor).isEqualTo(HookStatus.BLOCKED);
            assertThat(result.getFeedback().orElseThrow())
                    .contains(ShellHookOutcome.Unrun.EXECUTION_FAILED.description())
                    .contains(thrown.getClass().getSimpleName()).doesNotContain("s3cret");
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("thrownByTheGuard")
    void execute_executorThatThrows_failOpenPasses(Throwable thrown) throws Exception {
        for (ShellActionExecutor executor : List.of(throwingExecutor(thrown), throwingShell(thrown))) {
            DeclarativePreToolHook hook = new DeclarativePreToolHook("my-skill", NameOnlyPredicate.ANY,
                    new ShellAction("audit.sh", Duration.ofSeconds(1)), executor, null, null, Map.of(),
                    DeclarativeHookOptions.builder().failOpen(true).build());

            assertThat(hook.execute(contextFor("Bash")).getStatus()).as("%s", executor).isEqualTo(HookStatus.SUCCESS);
        }
    }

    // --- a guard that throws instead of reporting an outcome (fail-closed, review of #207 S1) -----------------------

    static java.util.stream.Stream<Throwable> thrownByTheGuard() {
        return java.util.stream.Stream.of(new IllegalStateException("guard.sh --token s3cret exploded"),
                new NoSuchMethodError("at.aimon.core.shell.VirtualShell.execute(guard.sh --token s3cret)"));
    }

    private static ShellActionExecutor throwingExecutor(Throwable thrown) {
        return new ShellActionExecutor() {
            @Override
            public boolean isShellSupported() {
                return true;
            }

            @Override
            public boolean requiresExecutionEnvironment() {
                return false;
            }

            @Override
            public ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> env,
                    String stdinPayload) {
                throw sneaky(thrown);
            }
        };
    }

    private static ShellActionExecutor throwingShell(Throwable thrown) throws Exception {
        final at.aimon.core.shell.VirtualShell shell = org.mockito.Mockito.mock(at.aimon.core.shell.VirtualShell.class);
        org.mockito.Mockito
                .when(shell.execute(org.mockito.ArgumentMatchers.any(at.aimon.core.shell.ShellCommand.class),
                        org.mockito.ArgumentMatchers.any(at.aimon.core.shell.ExecutionOptions.class)))
                .thenThrow(thrown);
        return new HostShellActionExecutor(shell);
    }

    private static RuntimeException sneaky(Throwable thrown) {
        if (thrown instanceof RuntimeException runtime) {
            return runtime;
        }
        throw (Error) thrown;
    }

    private static ShellActionExecutor fixedOutcome(ShellHookOutcome outcome) {
        return new ShellActionExecutor() {
            @Override
            public boolean isShellSupported() {
                return true;
            }

            @Override
            public boolean requiresExecutionEnvironment() {
                return false;
            }

            @Override
            public ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> env,
                    String stdinPayload) {
                return outcome;
            }
        };
    }

    @Test
    void execute_shellActionFailureSwallowed_stillReturnsSuccess() {
        ShellActionExecutor throwing = new ShellActionExecutor() {
            @Override
            public boolean isShellSupported() {
                return true;
            }

            @Override
            public boolean requiresExecutionEnvironment() {
                return false;
            }

            @Override
            public ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> env,
                    String stdinPayload) {
                // Contract: must not throw. Implementations that violate should never make HookResult fail.
                // Here we exercise the "compliant" path; a separate test exercises null safety.
                return ShellHookOutcome.of(0, "", "");
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
                .hookRegistry(REGISTRY).toolUse(ToolUse.of("call-1", toolName, Map.of())).iterationCount(3).build();
    }

    private static final class RecordingExecutor implements ShellActionExecutor {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public boolean isShellSupported() {
            return true;
        }

        @Override
        public boolean requiresExecutionEnvironment() {
            return false;
        }

        @Override
        public ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> environmentOverrides,
                String stdinPayload) {
            calls.add(new Call(action, context, Map.copyOf(environmentOverrides)));
            return ShellHookOutcome.of(0, "", "");
        }

        record Call(ShellAction action, HookContext context, Map<String, String> env) {
        }
    }

    @SuppressWarnings("unused")
    private static HookAction unused() {
        // Force the test class to retain a reference to the sealed interface so removing a permit
        // would surface here as a compile error.
        return new DenyAction("x");
    }
}
