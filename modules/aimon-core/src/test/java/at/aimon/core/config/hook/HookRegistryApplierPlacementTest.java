package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.ShellActionExecutor;
import at.aimon.core.skill.hook.declarative.ShellHookOutcome;

/**
 * Where {@link HookRegistryApplier} places a {@code hooks.json} command (aimon-core #273): the placement table, the
 * {@code asyncRewake} exception, {@code AIMON_HOOK_DIR} and the host-path hint.
 */
@DisplayName("HookRegistryApplier: where a command runs")
class HookRegistryApplierPlacementTest {

    private final JacksonHookConfigParser parser = new JacksonHookConfigParser();
    private final HookConfigMerger merger = new HookConfigMerger();
    private final ShellActionExecutor host = executor(false);
    private final ShellActionExecutor environment = executor(true);
    private final DefaultHookRegistry registry = new DefaultHookRegistry();

    private static ShellActionExecutor executor(boolean environmentBound) {
        final ShellActionExecutor executor = mock(ShellActionExecutor.class);
        when(executor.isShellSupported()).thenReturn(true);
        when(executor.requiresExecutionEnvironment()).thenReturn(environmentBound);
        when(executor.canRunOn(any())).thenAnswer(invocation -> !environmentBound
                || ((HookEventType<?>) invocation.getArgument(0)).firesInsideExecution());
        when(executor.run(any(), any(), anyMap(), any())).thenReturn(ShellHookOutcome.of(0, "", ""));
        return executor;
    }

    private void apply(String json) {
        new HookRegistryApplier(host, environment, null, null, Map.of()).apply(
                merger.merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, parser.parse(json)).build()),
                registry);
    }

    private PreToolContext preTool() {
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .executionEnvironment(TestExecutionEnvironments.ofShell(mock(VirtualShell.class)))
                .toolUse(ToolUse.of("call-1", "Bash", Map.of())).iterationCount(1).build();
    }

    private OnStartContext onStart() {
        return OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .executionEnvironment(TestExecutionEnvironments.ofShell(mock(VirtualShell.class))).userMessage("hi")
                .build();
    }

    @Test
    @DisplayName("no \"shell\" on an in-execution event: the execution's environment")
    void defaultInExecution_runsInTheEnvironment() {
        apply("""
                {"hooks":{"preTool":[{"hooks":[{"type":"command","command":"guard"}]}],
                          "onStart":[{"hooks":[{"type":"command","command":"gate"}]}]}}""");

        registry.getHooks(HookEventType.PRE_TOOL).get(0).execute(preTool());
        registry.getHooks(HookEventType.ON_START).get(0).execute(onStart());

        verify(environment, org.mockito.Mockito.times(2)).run(any(), any(), anyMap(), any());
        verify(host, never()).run(any(), any(), anyMap(), any());
    }

    @Test
    @DisplayName("\"shell\": \"host\" keeps an in-execution handler on the host")
    void explicitHost_runsOnTheHost() {
        apply("""
                {"hooks":{"preTool":[{"hooks":[{"type":"command","command":"guard","shell":"host"}]}]}}""");

        registry.getHooks(HookEventType.PRE_TOOL).get(0).execute(preTool());

        verify(host).run(any(), any(), anyMap(), any());
        verify(environment, never()).run(any(), any(), anyMap(), any());
    }

    @Test
    @DisplayName("no \"shell\" on an out-of-execution event: the host")
    void defaultOutOfExecution_runsOnTheHost() {
        apply("""
                {"hooks":{"onSessionStart":[{"hooks":[{"type":"command","command":"hello"}]}]}}""");

        registry.getHooks(HookEventType.ON_SESSION_START).get(0).execute(OnSessionStartContext.builder()
                .invokerType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry).build());

        verify(host).run(any(), any(), anyMap(), any());
        verify(environment, never()).run(any(), any(), anyMap(), any());
    }

    @Test
    @DisplayName("\"shell\": \"environment\" on an out-of-execution event is skipped")
    void explicitEnvironmentOutOfExecution_isSkipped() {
        apply("""
                {"hooks":{"onSessionEnd":[{"hooks":[{"type":"command","command":"bye","shell":"environment"}]}],
                          "onConfigReload":[{"hooks":[{"type":"command","command":"r","shell":"environment"}]}]}}""");

        assertThat(registry.getHooks(HookEventType.ON_SESSION_END)).isEmpty();
        assertThat(registry.getHooks(HookEventType.ON_CONFIG_RELOAD)).isEmpty();
    }

    @Test
    @DisplayName("an asyncRewake command with no \"shell\" stays on the host, and keeps its rewake")
    void asyncRewakeWithoutShell_staysOnTheHost() {
        apply("""
                {"hooks":{"preTool":[{"hooks":[{"type":"command","command":"x",
                  "asyncRewake":{"trigger":{"delay":"5m"},"maxAttempts":2,"reason":"retry"}}]}]}}""");

        final HookResult result = registry.getHooks(HookEventType.PRE_TOOL).get(0).execute(preTool());

        verify(host).run(any(), any(), anyMap(), any());
        verify(environment, never()).run(any(), any(), anyMap(), any());
        assertThat(result.getRewakeSpecs()).hasSize(1);
    }

    @Test
    @DisplayName("an asyncRewake command placed in the environment explicitly loses its rewake")
    void asyncRewakeInTheEnvironment_dropsTheRewake() {
        apply("""
                {"hooks":{"preTool":[{"hooks":[{"type":"command","command":"x","shell":"environment",
                  "asyncRewake":{"trigger":{"delay":"5m"},"maxAttempts":2,"reason":"retry"}}]}]}}""");

        final HookResult result = registry.getHooks(HookEventType.PRE_TOOL).get(0).execute(preTool());

        verify(environment).run(any(), any(), anyMap(), any());
        assertThat(result.getRewakeSpecs()).isEmpty();
    }

    @Test
    @DisplayName("a command that names AIMON_HOOK_DIR carries its layer's hooks/ directory")
    void hookDirReference_carriesTheLayersDirectory(@TempDir Path tmp) throws Exception {
        final Path layer = Files.createDirectories(tmp.resolve(".aimon/hooks")).getParent();
        final Path file = Files.writeString(layer.resolve("hooks.json"), "{}");
        new HookRegistryApplier(host, environment, null, null, Map.of())
                .apply(merger.merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, parser.parse("""
                        {"hooks":{"preTool":[{"hooks":[{"type":"command",
                          "command":"bash \\"$AIMON_HOOK_DIR/guard.sh\\""}]}]}}"""), file).build()), registry);

        registry.getHooks(HookEventType.PRE_TOOL).get(0).execute(preTool());

        final ArgumentCaptor<ShellAction> action = ArgumentCaptor.forClass(ShellAction.class);
        verify(environment).run(action.capture(), any(), anyMap(), any());
        assertThat(action.getValue().getScriptDirectory()).hasValueSatisfying(directory -> {
            assertThat(directory.getHostDirectory()).isEqualTo(layer.toAbsolutePath().resolve("hooks"));
            assertThat(directory.getResourceName()).isEqualTo("hooks-project");
        });
    }

    @Test
    @DisplayName("a command that does not name AIMON_HOOK_DIR carries no directory")
    void noHookDirReference_carriesNoDirectory() {
        apply("""
                {"hooks":{"preTool":[{"hooks":[{"type":"command","command":"guard"}]}]}}""");

        registry.getHooks(HookEventType.PRE_TOOL).get(0).execute(preTool());

        final ArgumentCaptor<ShellAction> action = ArgumentCaptor.forClass(ShellAction.class);
        verify(environment).run(action.capture(), any(), anyMap(), any());
        assertThat(action.getValue().getScriptDirectory()).isEmpty();
    }

    @Test
    @DisplayName("AIMON_HOOK_DIR with no hooks/ directory stops a guard's load and skips an observer")
    void hookDirReferenceWithoutTheDirectory_isInapplicable(@TempDir Path tmp) throws Exception {
        final Path file = Files.writeString(Files.createDirectories(tmp.resolve(".aimon")).resolve("hooks.json"), "{}");
        final String guard = """
                {"hooks":{"preTool":[{"hooks":[{"type":"command","command":"bash $AIMON_HOOK_DIR/g.sh"}]}]}}""";
        final String observer = """
                {"hooks":{"postTool":[{"hooks":[{"type":"command","command":"bash $AIMON_HOOK_DIR/g.sh"}]}]}}""";
        final HookRegistryApplier applier = new HookRegistryApplier(host, environment, null, null, Map.of());

        assertThatThrownBy(() -> applier.apply(
                merger.merge(
                        LayeredHookConfig.builder().put(HookConfigSource.PROJECT, parser.parse(guard), file).build()),
                new DefaultHookRegistry())).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("AIMON_HOOK_DIR").hasMessageContaining("missing or not a directory");

        applier.apply(merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, parser.parse(observer), file).build()),
                registry);
        assertThat(registry.getHooks(HookEventType.POST_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("a command naming a host .aimon path that the environment could not start says so in its reason")
    void hostPathHint_isAddedToACouldNotStartReason() {
        when(environment.run(any(), any(), anyMap(), any())).thenReturn(ShellHookOutcome.of(127, "", "not found"));
        apply("""
                {"hooks":{"preTool":[{"hooks":[{"type":"command","command":"bash ~/.aimon/hooks/guard.sh"}]}]}}""");

        final HookResult result = registry.getHooks(HookEventType.PRE_TOOL).get(0).execute(preTool());

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("command not found")
                .contains("names host path ~/.aimon/").contains("\"shell\": \"host\"");
    }

    @Test
    @DisplayName("the same command on the host gets no hint: the path is the host's own")
    void hostPathHint_notOnTheHost() {
        when(host.run(any(), any(), anyMap(), any())).thenReturn(ShellHookOutcome.of(127, "", "not found"));
        apply("""
                {"hooks":{"preTool":[{"hooks":[{"type":"command","command":"bash ~/.aimon/hooks/guard.sh",
                  "shell":"host"}]}]}}""");

        final HookResult result = registry.getHooks(HookEventType.PRE_TOOL).get(0).execute(preTool());

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).doesNotContain("names host path");
    }
}
