package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.declarative.HostShellActionExecutor;
import at.aimon.core.skill.hook.declarative.ShellActionExecutor;

/**
 * Smoke tests for {@link HookHotReloadBootstrap}.
 *
 * <p>
 * The full edit → reload → event SLA path is covered by {@link HookConfigHotReloadE2ETest}; these tests focus on the
 * helper's own contract: required-arg validation, bootstrap success/failure surfacing, and idempotent close.
 */
class HookHotReloadBootstrapTest {

    // A shell-capable executor: one without shell support registers no command handler at all (EE-51).
    private static final ShellActionExecutor SHELL_EXECUTOR = new HostShellActionExecutor(
            org.mockito.Mockito.mock(VirtualShell.class));

    private static final ReloadInvoker INVOKER = new ReloadInvoker(InvokerType.MAIN_AGENT, "main");

    @TempDir
    Path userDir;
    @TempDir
    Path projectDir;

    @Test
    void startWithNoConfigFilesSucceedsAndExposesActiveWatcher() throws Exception {
        final DefaultHookRegistry registry = new DefaultHookRegistry();
        final DefaultHookExecutionManager executionManager = new DefaultHookExecutionManager();

        try (HookHotReloadBootstrap.Started started = HookHotReloadBootstrap.builder().userHome(userDir)
                .projectRoot(projectDir).shellExecutor(SHELL_EXECUTOR).processEnv(Map.of()).registry(registry)
                .executionManager(executionManager).invoker(INVOKER).start()) {

            // No layer files present → the initial load is a successful no-op.
            assertThat(started.isWatcherActive()).isTrue();
            assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEmpty();
        }
    }

    @Test
    void startWithProjectConfigRegistersHooksOnBootstrap() throws Exception {
        Files.createDirectories(projectDir.resolve(".aimon"));
        Files.writeString(projectDir.resolve(".aimon/hooks.json"),
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[" //
                        + "{\"type\":\"command\",\"command\":\"echo hi\"}]}]}}");

        final DefaultHookRegistry registry = new DefaultHookRegistry();

        try (HookHotReloadBootstrap.Started started = HookHotReloadBootstrap.builder().userHome(userDir)
                .projectRoot(projectDir).shellExecutor(SHELL_EXECUTOR).processEnv(Map.of()).registry(registry)
                .invoker(INVOKER).start()) {

            assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void theDeprecatedBootstrapFlagIsAlwaysTrue() throws Exception {
        try (HookHotReloadBootstrap.Started started = HookHotReloadBootstrap.builder().userHome(userDir)
                .projectRoot(projectDir).shellExecutor(SHELL_EXECUTOR).processEnv(Map.of())
                .registry(new DefaultHookRegistry()).invoker(INVOKER).start()) {

            assertThat(started.isBootstrapSucceeded()).isTrue();
        }
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {"{not valid json|line: 1",
            "{\"hooks\":{\"PreToolUse\":[{\"hooks\":[{\"type\":\"comand\",\"command\":\"c\"}]}]}}|Unknown hook handler type",
            "{\"hooks\":{\"PreToolUse\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"c\",\"timeout\":-5}]}]}}|must be a positive number"})
    void startWithABrokenConfigThrowsAndLeavesNothingBehind(String broken, String cause) throws Exception {
        // EE-71. The user layer is valid and holds a guard: it must not be registered either, and the host must
        // not come up.
        Files.createDirectories(userDir.resolve(".aimon"));
        Files.writeString(userDir.resolve(".aimon/hooks.json"),
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[" //
                        + "{\"type\":\"command\",\"command\":\"guard\"}]}]}}");
        Files.createDirectories(projectDir.resolve(".aimon"));
        final Path brokenFile = projectDir.resolve(".aimon/hooks.json");
        Files.writeString(brokenFile, broken);
        final DefaultHookRegistry registry = new DefaultHookRegistry();
        final long watchersBefore = watcherThreads();

        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().userHome(userDir).projectRoot(projectDir)
                .shellExecutor(SHELL_EXECUTOR).processEnv(Map.of()).registry(registry).invoker(INVOKER).start())
                .isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining(brokenFile.toAbsolutePath().toString())
                .hasMessageContaining("(PROJECT layer) is invalid").hasMessageContaining(cause);

        assertThat(registry.isEmpty()).isTrue();
        assertThat(watcherThreads()).as("no watcher thread was started").isEqualTo(watchersBefore);
    }

    private static long watcherThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "aimon-hook-config-watcher".equals(thread.getName())).count();
    }

    @Test
    void closeIsIdempotent() throws Exception {
        final DefaultHookRegistry registry = new DefaultHookRegistry();

        final HookHotReloadBootstrap.Started started = HookHotReloadBootstrap.builder().userHome(userDir)
                .projectRoot(projectDir).shellExecutor(SHELL_EXECUTOR).processEnv(Map.of()).registry(registry)
                .invoker(INVOKER).start();

        started.close();
        // Second close must not throw.
        started.close();
    }

    @Test
    void requiredFieldsAreValidated() {
        final DefaultHookRegistry registry = new DefaultHookRegistry();

        // Each required field, omitted, must throw NPE on start().
        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().projectRoot(projectDir).shellExecutor(SHELL_EXECUTOR)
                .processEnv(Map.of()).registry(registry).invoker(INVOKER).start())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("userHome");

        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().userHome(userDir).shellExecutor(SHELL_EXECUTOR)
                .processEnv(Map.of()).registry(registry).invoker(INVOKER).start())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("projectRoot");

        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().userHome(userDir).projectRoot(projectDir)
                .processEnv(Map.of()).registry(registry).invoker(INVOKER).start())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("shellExecutor");

        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().userHome(userDir).projectRoot(projectDir)
                .shellExecutor(SHELL_EXECUTOR).registry(registry).invoker(INVOKER).start())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("processEnv");

        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().userHome(userDir).projectRoot(projectDir)
                .shellExecutor(SHELL_EXECUTOR).processEnv(Map.of()).invoker(INVOKER).start())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("registry");

        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().userHome(userDir).projectRoot(projectDir)
                .shellExecutor(SHELL_EXECUTOR).processEnv(Map.of()).registry(registry).start())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("invoker");
    }
}
