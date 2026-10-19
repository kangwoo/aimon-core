package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.RunDirectoryIntegrityException;
import at.aimon.core.skill.hook.action.HookScriptDirectory;
import at.aimon.core.skill.hook.action.ShellAction;

class IsolatedShellActionExecutorTest {

    private final IsolatedShellActionExecutor executor = new IsolatedShellActionExecutor();

    @Test
    void needsAnExecutionEnvironment() {
        assertThat(executor.isShellSupported()).isTrue();
        assertThat(executor.requiresExecutionEnvironment()).isTrue();
        for (HookEventType<?> type : HookEventType.values()) {
            assertThat(executor.canRunOn(type)).as(type.name()).isEqualTo(type.firesInsideExecution());
        }
    }

    @Test
    void run_marksTheCommandIsolatedAndAHooks() throws Exception {
        final VirtualShell shell = isolatingShell();

        final ShellHookOutcome outcome = executor.run(new ShellAction("guard", Duration.ofSeconds(3)),
                contextIn(TestExecutionEnvironments.ofShell(shell)), Map.of("AIMON_HOOK_EVENT", "onStart"), "{}");

        final ArgumentCaptor<ExecutionOptions> options = ArgumentCaptor.forClass(ExecutionOptions.class);
        verify(shell).execute(any(ShellCommand.class), options.capture());
        // Isolated: no cd or export the agent left behind may steer it; a hook: no session lock, no state saved.
        assertThat(options.getValue().isIsolated()).isTrue();
        assertThat(options.getValue().isHook()).isTrue();
        assertThat(options.getValue().getEnvironment()).containsEntry("AIMON_HOOK_EVENT", "onStart")
                .doesNotContainKey("PATH");
        assertThat(outcome.getExitCode()).isZero();
    }

    @Test
    void run_aShellWithoutIsolation_isNotRun() throws Exception {
        final VirtualShell shell = mock(VirtualShell.class);
        when(shell.supports(ShellFeature.ISOLATED_EXECUTION)).thenReturn(false);

        final ShellHookOutcome outcome = executor.run(new ShellAction("guard", Duration.ofSeconds(1)),
                contextIn(TestExecutionEnvironments.ofShell(shell)), Map.of(), null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.ISOLATION_UNSUPPORTED);
        verify(shell, never()).execute(any(ShellCommand.class), any(ExecutionOptions.class));
    }

    @Test
    void run_aShellCompiledAgainstTheOldEnum_isNotRun() throws Exception {
        // An exhaustive switch over ShellFeature with no default, compiled before the constant existed.
        final VirtualShell shell = mock(VirtualShell.class);
        when(shell.supports(ShellFeature.ISOLATED_EXECUTION)).thenThrow(new IncompatibleClassChangeError("enum"));

        final ShellHookOutcome outcome = executor.run(new ShellAction("guard", Duration.ofSeconds(1)),
                contextIn(TestExecutionEnvironments.ofShell(shell)), Map.of(), null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.ISOLATION_UNSUPPORTED);
        verify(shell, never()).execute(any(ShellCommand.class), any(ExecutionOptions.class));
    }

    @Test
    void run_noEnvironment_isNotRunAndNotSentToTheHost() {
        final HookContext context = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).userMessage("hi").build();

        final ShellHookOutcome outcome = executor.run(new ShellAction("guard", Duration.ofSeconds(1)), context,
                Map.of(), null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.NO_ENVIRONMENT);
    }

    @Test
    void run_aBranch_runsOnTheBaseEnvironmentsShellWithTheHookFilesAndStagesNothing(@TempDir Path tmp)
            throws Exception {
        final Path layer = Files.createDirectories(tmp.resolve(".aimon"));
        Files.createDirectories(layer.resolve("hooks/lib"));
        Files.writeString(layer.resolve("hooks/guard.sh"), "exit 0\n");
        Files.writeString(layer.resolve("hooks/lib/common.sh"), "true\n");
        final VirtualShell baseShell = isolatingShell();
        final VirtualShell branchShell = isolatingShell();
        final ExecutionEnvironment base = spy(TestExecutionEnvironments.ofShell(baseShell));
        final ExecutionEnvironment branch = branchOf(base, branchShell);

        executor.run(scripted(layer), contextIn(branch), Map.of(), null);

        final ArgumentCaptor<ExecutionOptions> options = ArgumentCaptor.forClass(ExecutionOptions.class);
        verify(baseShell).execute(any(ShellCommand.class), options.capture());
        verify(branchShell, never()).execute(any(ShellCommand.class), any(ExecutionOptions.class));
        // EE-109: the bytes travel with the command; nothing is staged into an agent-writable workspace.
        verify(base, never()).stage(any(StagedResource.class));
        final RunDirectory run = options.getValue().getRunDirectory().orElseThrow();
        assertThat(run.getVariable()).isEqualTo(SkillHookEnv.AIMON_HOOK_DIR);
        assertThat(run.getPaths()).containsExactly("guard.sh", "lib/common.sh");
        assertThat(new String(run.getContent("guard.sh"), StandardCharsets.UTF_8)).isEqualTo("exit 0\n");
        assertThat(options.getValue().getEnvironment()).doesNotContainKey(SkillHookEnv.AIMON_HOOK_DIR);
    }

    @Test
    void run_aShellWithoutRunDirectory_isNotRun(@TempDir Path tmp) throws Exception {
        final Path layer = Files.createDirectories(tmp.resolve(".aimon"));
        Files.createDirectories(layer.resolve("hooks"));
        final VirtualShell shell = isolatingShell();
        when(shell.supports(ShellFeature.RUN_DIRECTORY)).thenReturn(false);
        final ExecutionEnvironment environment = spy(TestExecutionEnvironments.ofShell(shell));

        final ShellHookOutcome outcome = executor.run(scripted(layer), contextIn(environment), Map.of(), null);

        // Fail closed (F1): neither a staged copy the agent can rewrite nor an unset variable.
        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.RUN_DIRECTORY_UNSUPPORTED);
        verify(shell, never()).execute(any(ShellCommand.class), any(ExecutionOptions.class));
        verify(environment, never()).stage(any(StagedResource.class));
    }

    @Test
    void run_aShellThatCannotAnswerForRunDirectory_isNotRun(@TempDir Path tmp) throws Exception {
        final Path layer = Files.createDirectories(tmp.resolve(".aimon"));
        Files.createDirectories(layer.resolve("hooks"));
        final VirtualShell shell = isolatingShell();
        when(shell.supports(ShellFeature.RUN_DIRECTORY)).thenThrow(new IncompatibleClassChangeError("enum"));

        final ShellHookOutcome outcome = executor.run(scripted(layer),
                contextIn(TestExecutionEnvironments.ofShell(shell)), Map.of(), null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.RUN_DIRECTORY_UNSUPPORTED);
        verify(shell, never()).execute(any(ShellCommand.class), any(ExecutionOptions.class));
    }

    @Test
    void run_aShellWithoutRunDirectory_stillRunsAHandlerThatDoesNotNameTheVariable() throws Exception {
        final VirtualShell shell = isolatingShell();
        when(shell.supports(ShellFeature.RUN_DIRECTORY)).thenReturn(false);

        final ShellHookOutcome outcome = executor.run(new ShellAction("guard", Duration.ofSeconds(1)),
                contextIn(TestExecutionEnvironments.ofShell(shell)), Map.of(), null);

        assertThat(outcome.getExitCode()).isZero();
    }

    @Test
    void run_aDirectoryOverTheLimit_isNotRun(@TempDir Path tmp) throws Exception {
        final Path layer = Files.createDirectories(tmp.resolve(".aimon"));
        Files.createDirectories(layer.resolve("hooks"));
        Files.write(layer.resolve("hooks/big.bin"), new byte[(int) RunDirectory.MAX_TOTAL_BYTES + 1]);
        final VirtualShell shell = isolatingShell();

        final ShellHookOutcome outcome = executor.run(scripted(layer),
                contextIn(TestExecutionEnvironments.ofShell(shell)), Map.of(), null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.HOOK_DIR_STAGING_FAILED);
        assertThat(outcome.unrunReason()).contains(String.valueOf(RunDirectory.MAX_TOTAL_BYTES + 1))
                .contains(".stageignore").doesNotContain(tmp.toString());
        verify(shell, never()).execute(any(ShellCommand.class), any(ExecutionOptions.class));
    }

    @Test
    void run_aFileNameARunDirectoryCannotPlace_isNotRun(@TempDir Path tmp) throws Exception {
        // #283 F1: DEL passes the local filesystem's own path check and reaches the run-directory builder.
        final Path layer = Files.createDirectories(tmp.resolve(".aimon"));
        Files.createDirectories(layer.resolve("hooks"));
        Files.writeString(layer.resolve("hooks/guard.sh"), "exit 0\n");
        assumeTrue(createdFile(layer.resolve("hooks/bad\u007fname.sh")), "the filesystem refuses the name");
        final VirtualShell shell = isolatingShell();

        final ShellHookOutcome outcome = executor.run(scripted(layer),
                contextIn(TestExecutionEnvironments.ofShell(shell)), Map.of(), null);

        // A defect in the hook's own files, not tampering: the "could not run" class, which failOpen may open.
        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.HOOK_DIR_STAGING_FAILED);
        assertThat(outcome.unrunReason()).contains("'bad\\u007fname.sh' contains a control character")
                .contains(".stageignore").doesNotContain("\u007f").doesNotContain(tmp.toString());
        verify(shell, never()).execute(any(ShellCommand.class), any(ExecutionOptions.class));
    }

    @Test
    void run_aFileNameWithANewline_isRefusedByTheScan(@TempDir Path tmp) throws Exception {
        // The local filesystem refuses a C0 control in a path before the builder sees it; still not run, not tampering.
        final Path layer = Files.createDirectories(tmp.resolve(".aimon"));
        Files.createDirectories(layer.resolve("hooks"));
        assumeTrue(createdFile(layer.resolve("hooks/bad\nname.sh")), "the filesystem refuses the name");
        final VirtualShell shell = isolatingShell();

        final ShellHookOutcome outcome = executor.run(scripted(layer),
                contextIn(TestExecutionEnvironments.ofShell(shell)), Map.of(), null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.HOOK_DIR_STAGING_FAILED);
        assertThat(outcome.unrunReason()).doesNotContain(tmp.toString());
        verify(shell, never()).execute(any(ShellCommand.class), any(ExecutionOptions.class));
    }

    @Test
    void run_aShellThatFindsTheFilesChanged_reportsTampering(@TempDir Path tmp) throws Exception {
        final Path layer = Files.createDirectories(tmp.resolve(".aimon"));
        Files.createDirectories(layer.resolve("hooks"));
        final VirtualShell shell = isolatingShell();
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new RunDirectoryIntegrityException("guard.sh does not match"));

        final ShellHookOutcome outcome = executor.run(scripted(layer),
                contextIn(TestExecutionEnvironments.ofShell(shell)), Map.of(), null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.HOOK_FILES_TAMPERED);
    }

    @Test
    void run_withoutAScriptDirectory_stagesNothing() throws Exception {
        final VirtualShell shell = isolatingShell();
        final ExecutionEnvironment environment = spy(TestExecutionEnvironments.ofShell(shell));

        executor.run(new ShellAction("echo AIMON_HOOK_DIR", Duration.ofSeconds(1)), contextIn(environment), Map.of(),
                null);

        verify(environment, never()).stage(any(StagedResource.class));
        final ArgumentCaptor<ExecutionOptions> options = ArgumentCaptor.forClass(ExecutionOptions.class);
        verify(shell).execute(any(ShellCommand.class), options.capture());
        assertThat(options.getValue().getEnvironment()).doesNotContainKey(SkillHookEnv.AIMON_HOOK_DIR);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void run_aScriptEditedBetweenFirings_runsTheNewContent(@TempDir Path tmp) throws Exception {
        // F14: the directory is scanned again at every firing, so an edit to guard.sh alone (hooks.json untouched)
        // is what the next firing runs.
        final Path layer = Files.createDirectories(tmp.resolve("project/.aimon"));
        Files.createDirectories(layer.resolve("hooks"));
        Files.writeString(layer.resolve("hooks/say.txt"), "first");
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(Files.createDirectories(tmp.resolve("workspace"))).contentSearch(false).build()) {
            final ExecutionEnvironment environment = provider
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("hooks")).build());
            final ShellAction action = new ShellAction("cat \"$AIMON_HOOK_DIR/say.txt\"", Duration.ofSeconds(10),
                    HookScriptDirectory.of(layer, "hooks-project"));

            final ShellHookOutcome first = executor.run(action, contextIn(environment), Map.of(), null);
            Files.writeString(layer.resolve("hooks/say.txt"), "second");
            final ShellHookOutcome second = executor.run(action, contextIn(environment), Map.of(), null);

            assertThat(first.getStdout()).isEqualTo("first");
            assertThat(second.getStdout()).isEqualTo("second");
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void run_withTheLocalProvider_theHookDirIsAPrivateRunDirectoryAndTamperingTheWorkspaceChangesNothing(
            @TempDir Path tmp) throws Exception {
        // The EE-109 scenario: the agent's shell rewrites a staged copy of guard.sh. There is none any more, so the
        // only file the next firing reads is the operator's own.
        final Path layer = Files.createDirectories(tmp.resolve("project/.aimon"));
        Files.createDirectories(layer.resolve("hooks"));
        Files.writeString(layer.resolve("hooks/guard.sh"), "echo operator\n");
        final Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace).contentSearch(false).build()) {
            final ExecutionEnvironment environment = provider
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("hooks")).build());
            final ShellAction action = new ShellAction(
                    "printf '%s|' \"$AIMON_HOOK_DIR\"; stat -c %a \"$AIMON_HOOK_DIR\" 2>/dev/null"
                            + " || stat -f %Lp \"$AIMON_HOOK_DIR\"; bash \"$AIMON_HOOK_DIR/guard.sh\"",
                    Duration.ofSeconds(10), HookScriptDirectory.of(layer, "hooks-project"));

            final ShellHookOutcome first = executor.run(action, contextIn(environment), Map.of(), null);
            try (var staged = Files.walk(workspace)) {
                assertThat(staged.map(path -> workspace.relativize(path).toString()))
                        .noneMatch(path -> path.contains("hooks-project"));
            }
            final String[] parts = first.getStdout().split("\\|", 2);
            final Path hookDir = Path.of(parts[0]);
            assertThat(hookDir).doesNotExist();
            assertThat(hookDir.toAbsolutePath().startsWith(workspace.toAbsolutePath())).isFalse();
            assertThat(parts[1]).startsWith("700").contains("operator");

            Files.writeString(layer.resolve("hooks/guard.sh"), "echo edited\n");
            final ShellHookOutcome second = executor.run(action, contextIn(environment), Map.of(), null);
            assertThat(second.getStdout()).contains("edited").doesNotContain(parts[0]);
        }
    }

    private static boolean createdFile(Path path) {
        try {
            Files.writeString(path, "true\n");
            return true;
        } catch (IOException | InvalidPathException e) {
            return false;
        }
    }

    private static ShellAction scripted(Path layer) {
        return new ShellAction("bash \"$AIMON_HOOK_DIR/guard.sh\"", Duration.ofSeconds(1),
                HookScriptDirectory.of(layer, "hooks-project"));
    }

    private static VirtualShell isolatingShell() throws Exception {
        final VirtualShell shell = mock(VirtualShell.class);
        when(shell.supports(ShellFeature.ISOLATED_EXECUTION)).thenReturn(true);
        when(shell.supports(ShellFeature.RUN_DIRECTORY)).thenReturn(true);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(0, "", "", Duration.ofMillis(1)));
        return shell;
    }

    /** A workflow branch that declares its lineage: a view of {@code base} with a shell of its own. */
    private static ExecutionEnvironment branchOf(ExecutionEnvironment base, VirtualShell branchShell) {
        return new ExecutionEnvironment() {
            @Override
            public VirtualFileSystem fileSystem() {
                return base.fileSystem();
            }

            @Override
            public VirtualShell shell() {
                return branchShell;
            }

            @Override
            public EnvironmentDescriptor descriptor() {
                return base.descriptor();
            }

            @Override
            public String stage(StagedResource resource) {
                throw new AssertionError("a branch's hooks.json command stages into its base environment");
            }

            @Override
            public Optional<ExecutionEnvironment> isolatedFrom() {
                return Optional.of(base);
            }
        };
    }

    private static HookContext contextIn(ExecutionEnvironment executionEnvironment) {
        return OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).executionEnvironment(executionEnvironment).userMessage("hi")
                .build();
    }
}
