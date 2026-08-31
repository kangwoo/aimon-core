package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.skill.hook.declarative.DeclarativeHookId;
import at.aimon.core.skill.hook.declarative.DeclarativePreToolHook;
import at.aimon.core.skill.hook.declarative.NoOpShellActionExecutor;

/**
 * Phase 3 WI-3.4.b — verifies {@link HookRegistryReloader} bootstrap, swap, and event-firing semantics.
 */
class HookRegistryReloaderTest {

    private static final Environment ENV = Environment.createDefault();
    private static final ReloadInvoker INVOKER = new ReloadInvoker(InvokerType.MAIN_AGENT, "main", ENV);

    @TempDir
    Path userDir;
    @TempDir
    Path projectDir;

    private HookConfigLoader loader;
    private HookConfigMerger merger;
    private HookRegistryApplier bootstrap;

    @BeforeEach
    void setUp() {
        loader = new HookConfigLoader(new JacksonHookConfigParser(), userDir, projectDir);
        merger = new HookConfigMerger();
        bootstrap = new HookRegistryApplier(NoOpShellActionExecutor.INSTANCE, null, null, Map.of());
    }

    @Test
    void rejectsNullArguments() {
        final HookRegistry registry = new DefaultHookRegistry();
        assertThatNullPointerException()
                .isThrownBy(() -> new HookRegistryReloader(null, merger, bootstrap, registry, null, INVOKER));
        assertThatNullPointerException()
                .isThrownBy(() -> new HookRegistryReloader(loader, null, bootstrap, registry, null, INVOKER));
        assertThatNullPointerException()
                .isThrownBy(() -> new HookRegistryReloader(loader, merger, null, registry, null, INVOKER));
        assertThatNullPointerException()
                .isThrownBy(() -> new HookRegistryReloader(loader, merger, bootstrap, null, null, INVOKER));
        assertThatNullPointerException()
                .isThrownBy(() -> new HookRegistryReloader(loader, merger, bootstrap, registry, null, null));
        // 7-arg constructor (Phase 4A WI-4A.9): rewakeService must not be null.
        assertThatNullPointerException()
                .isThrownBy(() -> new HookRegistryReloader(loader, merger, bootstrap, registry, null, INVOKER, null));
    }

    @Test
    void reloadCounterMustBeNonNegative() {
        final HookRegistry registry = new DefaultHookRegistry();
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, null,
                INVOKER);
        assertThatIllegalArgumentException().isThrownBy(() -> reloader.reload(-1L, null));
    }

    @Test
    void bootstrapAppliesInitialConfigAndDoesNotFireOnConfigReload() throws Exception {
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"echo hi\"}]}]}}");

        final HookRegistry registry = new DefaultHookRegistry();
        final HookExecutionManager manager = mock(HookExecutionManager.class);
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, manager,
                INVOKER);

        assertThat(reloader.bootstrap()).isTrue();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
        assertThat(reloader.getManagedHookCount()).isEqualTo(1);
        verify(manager, never()).executeOnConfigReload(any());
    }

    @Test
    void bootstrapWithMissingFilesIsSuccessfulAndNoOp() {
        final HookRegistry registry = new DefaultHookRegistry();
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, null,
                INVOKER);

        assertThat(reloader.bootstrap()).isTrue();
        assertThat(registry.isEmpty()).isTrue();
        assertThat(reloader.getManagedHookCount()).isZero();
    }

    @Test
    void reloadSwapsManagedHooksAndFiresSuccessEvent() throws Exception {
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"first\"}]}]}}");

        final HookRegistry registry = new DefaultHookRegistry();
        final HookExecutionManager manager = mock(HookExecutionManager.class);
        when(manager.executeOnConfigReload(any())).thenReturn(List.of(HookResult.allow()));
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, manager,
                INVOKER);

        assertThat(reloader.bootstrap()).isTrue();
        final List<PreToolHook> firstSnapshot = List.copyOf(registry.getHooks(HookEventType.PRE_TOOL));
        assertThat(firstSnapshot).hasSize(1);

        // Edit the config to two hooks and reload.
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":["
                + "{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"second-a\"}]},"
                + "{\"matcher\":\"Read\",\"hooks\":[{\"type\":\"command\",\"command\":\"second-b\"}]}]}}");

        final Path triggering = projectHooksFile();
        assertThat(reloader.reload(1L, triggering)).isTrue();

        final List<PreToolHook> secondSnapshot = List.copyOf(registry.getHooks(HookEventType.PRE_TOOL));
        assertThat(secondSnapshot).hasSize(2);
        assertThat(secondSnapshot).doesNotContainAnyElementsOf(firstSnapshot);

        final ArgumentCaptor<OnConfigReloadContext> captor = ArgumentCaptor.forClass(OnConfigReloadContext.class);
        verify(manager, times(1)).executeOnConfigReload(captor.capture());
        final OnConfigReloadContext ctx = captor.getValue();
        assertThat(ctx.isSuccessful()).isTrue();
        assertThat(ctx.getReloadCounter()).isEqualTo(1L);
        assertThat(ctx.getConfigSource()).isEqualTo(triggering.toString());
        assertThat(ctx.getFailureReason()).isEmpty();
    }

    @Test
    void reloadDoesNotTouchProgrammaticallyRegisteredHooks() throws Exception {
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"first\"}]}]}}");

        final HookRegistry registry = new DefaultHookRegistry();
        final PreToolHook external = ctx -> HookResult.allow();
        registry.register(HookEventType.PRE_TOOL, external);

        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, null,
                INVOKER);
        assertThat(reloader.bootstrap()).isTrue();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(2).contains(external);

        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Read\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"second\"}]}]}}");
        assertThat(reloader.reload(1L, null)).isTrue();

        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(2).contains(external);
    }

    @Test
    void reloadFailureLeavesRegistryIntactAndFiresFailureEvent() throws Exception {
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"first\"}]}]}}");

        final HookRegistry registry = new DefaultHookRegistry();
        final HookExecutionManager manager = mock(HookExecutionManager.class);
        when(manager.executeOnConfigReload(any())).thenReturn(List.of());
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, manager,
                INVOKER);
        assertThat(reloader.bootstrap()).isTrue();
        final List<PreToolHook> beforeFailure = List.copyOf(registry.getHooks(HookEventType.PRE_TOOL));

        // Replace with malformed JSON to force a parse failure on reload.
        writeProjectHooks("{not valid json");

        assertThat(reloader.reload(2L, projectHooksFile())).isFalse();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEqualTo(beforeFailure);

        final ArgumentCaptor<OnConfigReloadContext> captor = ArgumentCaptor.forClass(OnConfigReloadContext.class);
        verify(manager, times(1)).executeOnConfigReload(captor.capture());
        final OnConfigReloadContext ctx = captor.getValue();
        assertThat(ctx.isSuccessful()).isFalse();
        assertThat(ctx.getReloadCounter()).isEqualTo(2L);
        assertThat(ctx.getFailureReason()).startsWith("load/merge failed");
    }

    @Test
    void reloadWithNullExecutionManagerSucceedsSilently() throws Exception {
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"x\"}]}]}}");

        final HookRegistry registry = new DefaultHookRegistry();
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, null,
                INVOKER);
        assertThat(reloader.bootstrap()).isTrue();

        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Read\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"y\"}]}]}}");
        assertThat(reloader.reload(1L, null)).isTrue();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
    }

    @Test
    void midSwapFailureRollsBackToPreviousRegistryState() throws Exception {
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":["
                + "{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"old-a\"}]},"
                + "{\"matcher\":\"Read\",\"hooks\":[{\"type\":\"command\",\"command\":\"old-b\"}]}]}}");

        // Custom registry: throws on the SECOND registerPreToolHook call (after we have unregistered old hooks
        // and successfully registered the first new hook). All other operations behave normally so the rollback
        // can replay them.
        final FailingRegistry registry = new FailingRegistry(/* failOnRegisterCallNumber */ 2);

        final HookExecutionManager manager = mock(HookExecutionManager.class);
        when(manager.executeOnConfigReload(any())).thenReturn(List.of());
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, manager,
                INVOKER);

        assertThat(reloader.bootstrap()).isTrue();
        final List<PreToolHook> oldHooks = List.copyOf(registry.getHooks(HookEventType.PRE_TOOL));
        assertThat(oldHooks).hasSize(2);

        // New config has 3 pre-tool hooks; second register call (i.e. the 4th register-call overall counting
        // bootstrap's two) will throw. The first 2 register calls happened during bootstrap, so adjust accordingly.
        registry.armFailureCounter();

        writeProjectHooks("{\"hooks\":{\"PreToolUse\":["
                + "{\"matcher\":\"A\",\"hooks\":[{\"type\":\"command\",\"command\":\"new-a\"}]},"
                + "{\"matcher\":\"B\",\"hooks\":[{\"type\":\"command\",\"command\":\"new-b\"}]},"
                + "{\"matcher\":\"C\",\"hooks\":[{\"type\":\"command\",\"command\":\"new-c\"}]}]}}");

        assertThat(reloader.reload(1L, projectHooksFile())).isFalse();

        // Registry rolled back: the original two old hooks are present again, no new hooks remain.
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).containsExactlyElementsOf(oldHooks);

        // Failure event fired.
        final ArgumentCaptor<OnConfigReloadContext> captor = ArgumentCaptor.forClass(OnConfigReloadContext.class);
        verify(manager, times(1)).executeOnConfigReload(captor.capture());
        final OnConfigReloadContext ctx = captor.getValue();
        assertThat(ctx.isSuccessful()).isFalse();
        assertThat(ctx.getFailureReason()).startsWith("swap failed");

        // Subsequent reload (after disarming the trap) should still work — managed state was preserved.
        registry.disarm();
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":["
                + "{\"matcher\":\"X\",\"hooks\":[{\"type\":\"command\",\"command\":\"x\"}]}]}}");
        assertThat(reloader.reload(2L, projectHooksFile())).isTrue();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
    }

    // ==== Phase 4A WI-4A.9 — hot-reload cancels rewake envelopes for removed hooks ====

    @Test
    void bootstrapDoesNotInvokeRewakeCancel() throws Exception {
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"echo hi\"}]}]}}");

        final HookRegistry registry = new DefaultHookRegistry();
        final RewakeService rewakeService = mock(RewakeService.class);
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, null,
                INVOKER, rewakeService);

        assertThat(reloader.bootstrap()).isTrue();

        // Bootstrap has no prior managed hooks, so no ids are "removed" — cancel must not be invoked.
        verifyNoInteractions(rewakeService);
    }

    @Test
    void reloadCancelsRewakesForRemovedHookIds() throws Exception {
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"echo hi\"}]}]}}");

        final HookRegistry registry = new DefaultHookRegistry();
        final RewakeService rewakeService = mock(RewakeService.class);
        when(rewakeService.cancelByOriginatingHookId(anyString())).thenReturn(0);
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, null,
                INVOKER, rewakeService);
        assertThat(reloader.bootstrap()).isTrue();

        // Drop the only hook by replacing the config with an empty hooks block.
        writeProjectHooks("{\"hooks\":{}}");
        assertThat(reloader.reload(1L, projectHooksFile())).isTrue();

        // The position-keyed hookId of the dropped hook is now absent from the next config. The id is discriminated by
        // config source and entry/handler position, so it survives an unrelated edit but disappears with the hook.
        verify(rewakeService).cancelByOriginatingHookId(
                DeclarativeHookId.of(DeclarativePreToolHook.class, "project#0", "preTool[0][0]"));
    }

    @Test
    void reloadDoesNotCancelWhenSameHookIdStillPresent() throws Exception {
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"first\"}]}]}}");

        final HookRegistry registry = new DefaultHookRegistry();
        final RewakeService rewakeService = mock(RewakeService.class);
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, null,
                INVOKER, rewakeService);
        assertThat(reloader.bootstrap()).isTrue();

        // The matcher changed but the hook still sits at the same position → its hookId is unchanged, so the rewakes
        // it owns must survive the reload.
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Read\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"second\"}]}]}}");
        assertThat(reloader.reload(1L, projectHooksFile())).isTrue();

        verify(rewakeService, never()).cancelByOriginatingHookId(anyString());
    }

    @Test
    void midSwapFailureDoesNotCancelRewakes() throws Exception {
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":["
                + "{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"old-a\"}]},"
                + "{\"matcher\":\"Read\",\"hooks\":[{\"type\":\"command\",\"command\":\"old-b\"}]}]}}");

        final FailingRegistry registry = new FailingRegistry(/* failOnRegisterCallNumber */ 2);
        final RewakeService rewakeService = mock(RewakeService.class);
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, null,
                INVOKER, rewakeService);
        assertThat(reloader.bootstrap()).isTrue();

        registry.armFailureCounter();
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":["
                + "{\"matcher\":\"A\",\"hooks\":[{\"type\":\"command\",\"command\":\"new-a\"}]},"
                + "{\"matcher\":\"B\",\"hooks\":[{\"type\":\"command\",\"command\":\"new-b\"}]},"
                + "{\"matcher\":\"C\",\"hooks\":[{\"type\":\"command\",\"command\":\"new-c\"}]}]}}");

        assertThat(reloader.reload(1L, projectHooksFile())).isFalse();

        // Swap aborted before the post-commit cancel step could run.
        verify(rewakeService, never()).cancelByOriginatingHookId(anyString());
    }

    @Test
    void cancelFailureDoesNotAbortReload() throws Exception {
        writeProjectHooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"first\"}]}]}}");

        final HookRegistry registry = new DefaultHookRegistry();
        final HookExecutionManager manager = mock(HookExecutionManager.class);
        when(manager.executeOnConfigReload(any())).thenReturn(List.of(HookResult.allow()));
        final RewakeService rewakeService = mock(RewakeService.class);
        when(rewakeService.cancelByOriginatingHookId(anyString()))
                .thenThrow(new IllegalStateException("simulated cancel failure"));
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, merger, bootstrap, registry, manager,
                INVOKER, rewakeService);
        assertThat(reloader.bootstrap()).isTrue();

        writeProjectHooks("{\"hooks\":{}}");
        // Reload still reports success because the swap committed before the (best-effort) cancel ran.
        assertThat(reloader.reload(1L, projectHooksFile())).isTrue();

        verify(rewakeService, atLeastOnce()).cancelByOriginatingHookId(anyString());

        // Successful OnConfigReload still fired despite cancel throwing.
        final ArgumentCaptor<OnConfigReloadContext> captor = ArgumentCaptor.forClass(OnConfigReloadContext.class);
        verify(manager, times(1)).executeOnConfigReload(captor.capture());
        assertThat(captor.getValue().isSuccessful()).isTrue();
    }

    /**
     * Test-only registry that delegates to {@link DefaultHookRegistry} but optionally throws on the Nth pre-tool
     * register call once {@link #armFailureCounter()} has been invoked.
     */
    private static final class FailingRegistry extends DefaultHookRegistry {
        private final int failAtRegisterCallIndex;
        private boolean armed;
        private int registerCalls;

        FailingRegistry(int failAtRegisterCallIndex) {
            this.failAtRegisterCallIndex = failAtRegisterCallIndex;
        }

        void armFailureCounter() {
            this.armed = true;
            this.registerCalls = 0;
        }

        void disarm() {
            this.armed = false;
        }

        @Override
        public <H extends ExecutionHook<?>> void register(HookEventType<H> type, H hook) {
            if (armed && type == HookEventType.PRE_TOOL) {
                registerCalls++;
                if (registerCalls == failAtRegisterCallIndex) {
                    throw new IllegalStateException("simulated registry failure on call #" + registerCalls);
                }
            }
            super.register(type, hook);
        }
    }

    private Path projectHooksFile() {
        return projectDir.resolve("hooks.json");
    }

    private void writeProjectHooks(String json) throws Exception {
        Files.writeString(projectHooksFile(), json);
    }
}
