package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.skill.hook.declarative.NoOpShellActionExecutor;

/**
 * Smoke tests for {@link HookHotReloadBootstrap}.
 *
 * <p>
 * The full edit → reload → event SLA path is covered by {@link HookConfigHotReloadE2ETest}; these tests focus on the
 * helper's own contract: required-arg validation, bootstrap success/failure surfacing, and idempotent close.
 */
class HookHotReloadBootstrapTest {

    private static final ReloadInvoker INVOKER = new ReloadInvoker(InvokerType.MAIN_AGENT, "main",
            Environment.createDefault());

    @TempDir
    Path userDir;
    @TempDir
    Path projectDir;

    @Test
    void startWithNoConfigFilesSucceedsAndExposesActiveWatcher() throws Exception {
        final DefaultHookRegistry registry = new DefaultHookRegistry();
        final DefaultHookExecutionManager executionManager = new DefaultHookExecutionManager();

        try (HookHotReloadBootstrap.Started started = HookHotReloadBootstrap.builder().userHome(userDir)
                .projectRoot(projectDir).shellExecutor(NoOpShellActionExecutor.INSTANCE).processEnv(Map.of())
                .registry(registry).executionManager(executionManager).invoker(INVOKER).start()) {

            // No layer files present → bootstrap is a successful no-op.
            assertThat(started.isBootstrapSucceeded()).isTrue();
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
                .projectRoot(projectDir).shellExecutor(NoOpShellActionExecutor.INSTANCE).processEnv(Map.of())
                .registry(registry).invoker(INVOKER).start()) {

            assertThat(started.isBootstrapSucceeded()).isTrue();
            assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        final DefaultHookRegistry registry = new DefaultHookRegistry();

        final HookHotReloadBootstrap.Started started = HookHotReloadBootstrap.builder().userHome(userDir)
                .projectRoot(projectDir).shellExecutor(NoOpShellActionExecutor.INSTANCE).processEnv(Map.of())
                .registry(registry).invoker(INVOKER).start();

        started.close();
        // Second close must not throw.
        started.close();
    }

    @Test
    void requiredFieldsAreValidated() {
        final DefaultHookRegistry registry = new DefaultHookRegistry();

        // Each required field, omitted, must throw NPE on start().
        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().projectRoot(projectDir)
                .shellExecutor(NoOpShellActionExecutor.INSTANCE).processEnv(Map.of()).registry(registry)
                .invoker(INVOKER).start()).isInstanceOf(NullPointerException.class).hasMessageContaining("userHome");

        assertThatThrownBy(
                () -> HookHotReloadBootstrap.builder().userHome(userDir).shellExecutor(NoOpShellActionExecutor.INSTANCE)
                        .processEnv(Map.of()).registry(registry).invoker(INVOKER).start())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("projectRoot");

        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().userHome(userDir).projectRoot(projectDir)
                .processEnv(Map.of()).registry(registry).invoker(INVOKER).start())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("shellExecutor");

        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().userHome(userDir).projectRoot(projectDir)
                .shellExecutor(NoOpShellActionExecutor.INSTANCE).registry(registry).invoker(INVOKER).start())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("processEnv");

        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().userHome(userDir).projectRoot(projectDir)
                .shellExecutor(NoOpShellActionExecutor.INSTANCE).processEnv(Map.of()).invoker(INVOKER).start())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("registry");

        assertThatThrownBy(() -> HookHotReloadBootstrap.builder().userHome(userDir).projectRoot(projectDir)
                .shellExecutor(NoOpShellActionExecutor.INSTANCE).processEnv(Map.of()).registry(registry).start())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("invoker");
    }
}
