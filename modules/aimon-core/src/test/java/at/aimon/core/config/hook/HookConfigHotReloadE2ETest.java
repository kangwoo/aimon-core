package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.event.OnConfigReloadHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.skill.hook.declarative.NoOpShellActionExecutor;

/**
 * Phase 3 WI-3.6.x — End-to-end hot reload of {@code hooks.json}.
 *
 * <p>
 * Wires the full reload pipeline: {@link HookConfigWatcher} polls a watched file, debounces bursts of events, and
 * invokes {@link HookRegistryReloader#reload(long, Path)} which materialises the new layered config, swaps it onto a
 * live {@link DefaultHookRegistry}, and fires an {@link OnConfigReloadHook} via {@link DefaultHookExecutionManager}.
 *
 * <p>
 * Confirms the design budget: a hooks.json edit becomes visible (new hooks registered + OnConfigReload event fired)
 * inside the 2s SLA, with no programmatically registered hooks disturbed.
 */
class HookConfigHotReloadE2ETest {

    private static final Environment ENV = Environment.createDefault();
    private static final ReloadInvoker INVOKER = new ReloadInvoker(InvokerType.MAIN_AGENT, "main", ENV);

    @TempDir
    Path userDir;
    @TempDir
    Path projectDir;

    private HookConfigWatcher watcher;
    private DefaultHookExecutionManager executionManager;

    @BeforeEach
    void setUp() {
        executionManager = new DefaultHookExecutionManager();
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.close();
        }
    }

    @Test
    void editingHooksJsonReloadsRegistryAndFiresOnConfigReloadWithinSla() throws Exception {
        // Initial config — one PreTool hook on Bash.
        final Path projectHooks = projectDir.resolve("hooks.json");
        Files.writeString(projectHooks, "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"echo old\"}]}]}}");

        final HookConfigLoader loader = new HookConfigLoader(new JacksonHookConfigParser(), userDir, projectDir);
        final HookConfigMerger merger = new HookConfigMerger();
        final HookRegistryApplier bootstrap = new HookRegistryApplier(NoOpShellActionExecutor.INSTANCE, null, null,
                Map.of());
        final DefaultHookRegistry registry = new DefaultHookRegistry();

        // Capture all OnConfigReload events fired through the live execution manager.
        final ConcurrentLinkedQueue<OnConfigReloadContext> reloadEvents = new ConcurrentLinkedQueue<>();
        final OnConfigReloadHook captureHook = ctx -> {
            reloadEvents.add(ctx);
            return HookResult.allow();
        };
        registry.register(HookEventType.ON_CONFIG_RELOAD, captureHook);

        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry,
                executionManager, INVOKER);
        assertThat(reloader.bootstrap()).isTrue();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
        // Bootstrap does not fire OnConfigReload by contract.
        assertThat(reloadEvents).isEmpty();

        // Tight intervals so the test runs fast (real production defaults are 1s poll / 2s debounce).
        watcher = new HookConfigWatcher(List.of(projectHooks), reloader::reload, Duration.ofMillis(200),
                Duration.ofMillis(100), null);
        watcher.start();

        final long startNanos = System.nanoTime();

        // Edit the file — the watcher should detect the mtime change and fire a debounced reload.
        // Bump mtime explicitly because some filesystems have second-resolution timestamps.
        Files.writeString(projectHooks,
                "{\"hooks\":{\"PreToolUse\":["
                        + "{\"matcher\":\"Read\",\"hooks\":[{\"type\":\"command\",\"command\":\"echo new-a\"}]},"
                        + "{\"matcher\":\"Write\",\"hooks\":[{\"type\":\"command\",\"command\":\"echo new-b\"}]}]}}");
        Files.setLastModifiedTime(projectHooks,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));

        // SLA: reload completes within 2s of the edit.
        await().atMost(Duration.ofSeconds(2)).until(() -> !reloadEvents.isEmpty());
        final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(elapsedMs).as("reload SLA: edit -> OnConfigReload fired").isLessThanOrEqualTo(2_000L);
        assertThat(reloadEvents).hasSize(1);

        final OnConfigReloadContext ctx = reloadEvents.peek();
        assertThat(ctx.isSuccessful()).isTrue();
        assertThat(ctx.getReloadCounter()).isEqualTo(1L);
        assertThat(ctx.getConfigSource()).isEqualTo(projectHooks.toString());
        assertThat(ctx.getFailureReason()).isEmpty();

        // The live registry now reflects the new config: two pre-tool hooks, no leftover hook from the old config.
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(2);
        // The programmatically registered OnConfigReload hook survived the swap.
        assertThat(registry.getHooks(HookEventType.ON_CONFIG_RELOAD)).contains(captureHook);
    }
}
