package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.declarative.HostShellActionExecutor;
import at.aimon.core.skill.hook.declarative.HttpActionExecutor;
import at.aimon.core.skill.hook.declarative.NoOpShellActionExecutor;
import at.aimon.core.skill.hook.declarative.ShellActionExecutor;

/**
 * A {@code hooks.json} entry that parses but cannot be applied (EE-72).
 *
 * <p>
 * Under a guard event ({@code preTool}, {@code onStart}, {@code preCompact}, {@code permissionRequest}) such an entry
 * used to be skipped with a WARN, so the host started with that guard missing. It now stops startup the way a file
 * that does not parse does (EE-71), and a reload that hits one keeps the previous config. Entries under the other
 * events are still skipped with a WARN, and so is an event name AIMON does not know — unless the name is a near-miss
 * of a guard event, which is a typo rather than a future event.
 */
@DisplayName("hooks.json entries that cannot be applied (EE-72)")
class HookConfigGuardEntryStrictnessTest {

    private static final ShellActionExecutor HOST_SHELL = new HostShellActionExecutor(mock(VirtualShell.class));
    private static final ReloadInvoker INVOKER = new ReloadInvoker(InvokerType.MAIN_AGENT, "main");

    @TempDir
    Path userDir;
    @TempDir
    Path projectDir;

    private HookConfigLoader loader;
    private HookRegistry registry;
    private HookExecutionManager manager;

    @BeforeEach
    void setUp() {
        loader = new HookConfigLoader(new JacksonHookConfigParser(), userDir, projectDir);
        registry = new DefaultHookRegistry();
        manager = mock(HookExecutionManager.class);
        when(manager.executeOnConfigReload(any())).thenReturn(List.of());
    }

    // --- (1) an inapplicable entry under a guard event stops startup -------------------------------------------

    static Stream<Arguments> inapplicableGuardEntries() {
        return Stream.of(Arguments.of("a command handler with no command", """
                {"hooks":{"preTool":[{"hooks":[{"type":"command"}]}]}}""", "preTool", "requires non-blank 'command'"),
                Arguments.of("a blank command", """
                        {"hooks":{"onStart":[{"hooks":[{"type":"command","command":"  "}]}]}}""", "onStart",
                        "requires non-blank 'command'"),
                Arguments.of("deny outside preTool", """
                        {"hooks":{"onStart":[{"hooks":[{"type":"deny","reason":"no"}]}]}}""", "onStart",
                        "type=deny is only valid on preTool"),
                Arguments.of("deny without a reason", """
                        {"hooks":{"preTool":[{"hooks":[{"type":"deny"}]}]}}""", "preTool",
                        "requires non-blank 'reason'"),
                Arguments.of("a URL that is not a URI", """
                        {"hooks":{"preTool":[{"hooks":[{"type":"http","url":"http://exa mple.test/x"}]}]}}""",
                        "preTool", "invalid 'url'"),
                Arguments.of("an mcp handler with no tool", """
                        {"hooks":{"preTool":[{"hooks":[{"type":"mcp","server":"policy"}]}]}}""", "preTool",
                        "requires non-blank 'tool'"),
                Arguments.of("an http handler on a shell-only guard event", """
                        {"hooks":{"permissionRequest":[{"hooks":[{"type":"http","url":"https://example.test/h"}]}]}}""",
                        "permissionRequest", "only 'command'"),
                Arguments.of("an entry with no handlers", """
                        {"hooks":{"preCompact":[{"hooks":[]}]}}""", "preCompact", "no handlers"),
                Arguments.of("a matcher that does not parse", """
                        {"hooks":{"preTool":[{"matcher":"Bash(rm *","hooks":[{"type":"deny","reason":"no"}]}]}}""",
                        "preTool", "matcher"),
                Arguments.of("an http handler with no executor wired", """
                        {"hooks":{"preTool":[{"hooks":[{"type":"http","url":"https://example.test/h"}]}]}}""",
                        "preTool", "no HttpActionExecutor is wired"),
                Arguments.of("an mcp handler with no executor wired", """
                        {"hooks":{"preTool":[{"hooks":[{"type":"mcp","server":"policy","tool":"evaluate"}]}]}}""",
                        "preTool", "no McpActionExecutor is wired"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inapplicableGuardEntries")
    void bootstrap_inapplicableEntryUnderAGuardEvent_stopsStartup(String what, String json, String event, String reason)
            throws Exception {
        // A valid guard in another layer: it must not be what the host starts with either.
        Files.writeString(userDir.resolve("hooks.json"), """
                {"hooks":{"preTool":[{"matcher":"Bash","hooks":[{"type":"deny","reason":"user layer"}]}]}}""");
        writeProjectHooks(json);
        final HookRegistryReloader reloader = reloader(HOST_SHELL);

        assertThatThrownBy(reloader::bootstrap).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("hooks config " + projectHooksFile().toAbsolutePath())
                .hasMessageContaining("(PROJECT layer) is invalid").hasMessageContaining(event + " entry #0")
                .hasMessageContaining(reason);
        assertThat(registry.isEmpty()).isTrue();
        assertThat(reloader.getManagedHookCount()).isZero();
        verify(manager, never()).executeOnConfigReload(any());
    }

    @Test
    void bootstrap_messageNamesTheEntryAndHandlerPosition() throws Exception {
        writeProjectHooks("""
                {"hooks":{"PreToolUse":[
                  {"matcher":"Bash","hooks":[{"type":"deny","reason":"fine"}]},
                  {"matcher":"Edit","hooks":[{"type":"deny","reason":"fine"},{"type":"command"}]}
                ]}}""");

        assertThatThrownBy(reloader(HOST_SHELL)::bootstrap).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("preTool entry #1, handler #1");
    }

    @Test
    void bootstrap_commandGuardThatWouldBeDroppedBecauseShellIsUnsupported_stopsStartup() throws Exception {
        writeProjectHooks("""
                {"hooks":{"onStart":[{"hooks":[{"type":"command","command":"gate.sh"}]}]}}""");

        assertThatThrownBy(reloader(NoOpShellActionExecutor.INSTANCE)::bootstrap)
                .isInstanceOf(HookConfigParseException.class).hasMessageContaining("onStart entry #0, handler #0")
                .hasMessageContaining("does not support shell actions")
                // The command line may carry a secret; the message names the position, not the command.
                .hasMessageNotContaining("gate.sh");
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void bootstrap_failOpenHandlerThatCannotRunHere_isSkippedNotFatal() throws Exception {
        // failOpen says "this handler is not a guard": dropping it leaves no guard off.
        writeProjectHooks("""
                {"hooks":{"preTool":[{"hooks":[
                  {"type":"command","command":"audit.sh","failOpen":true},
                  {"type":"deny","reason":"no"}
                ]}]}}""");

        assertThatCode(reloader(NoOpShellActionExecutor.INSTANCE)::bootstrap).doesNotThrowAnyException();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
    }

    // --- (2) entries under non-guard events keep WARN-and-skip -------------------------------------------------

    @Test
    void bootstrap_inapplicableEntriesUnderNonGuardEvents_areSkippedAsBefore() throws Exception {
        writeProjectHooks("""
                {"hooks":{
                  "postTool":[{"hooks":[{"type":"command"},{"type":"deny","reason":"no"},
                                        {"type":"http","url":"http://exa mple.test/x"}]},
                              {"hooks":[]},
                              {"matcher":"Bash(rm *","hooks":[{"type":"command","command":"audit.sh"}]}],
                  "onStop":[{"hooks":[{"type":"http","url":"https://example.test/h"},
                                      {"type":"command","command":"bye.sh"}]}],
                  "preTool":[{"matcher":"Bash","hooks":[{"type":"deny","reason":"guard"}]}]
                }}""");

        assertThatCode(reloader(HOST_SHELL)::bootstrap).doesNotThrowAnyException();

        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
        assertThat(registry.getHooks(HookEventType.ON_STOP)).hasSize(1);
        // Only the last postTool entry survives, with its matcher fallen back to name-only.
        assertThat(registry.getHooks(HookEventType.POST_TOOL)).hasSize(1);
    }

    @Test
    void bootstrap_commandOnANonGuardEvent_isStillSkippedWhenShellIsUnsupported() throws Exception {
        writeProjectHooks("""
                {"hooks":{"postTool":[{"hooks":[{"type":"command","command":"audit.sh"}]}]}}""");

        assertThatCode(reloader(NoOpShellActionExecutor.INSTANCE)::bootstrap).doesNotThrowAnyException();
        assertThat(registry.isEmpty()).isTrue();
    }

    // --- (2b) a matcher that parses as a tool name no tool can have (EE-85) ---------------------------------------

    @ParameterizedTest(name = "preTool matcher \"{0}\"")
    @ValueSource(strings = {"^Edit$", "tool=Bash", "Bash & input.command~^npm", "Bash Edit", "Bash,Edit", "mcp__.*",
            "Read|^Edit$"})
    void bootstrap_preToolMatcherNoToolCanMatch_stopsStartup(String matcher) throws Exception {
        // Each of these used to register a deny guard that never fired: the text was taken whole as a tool name.
        writeProjectHooks(denyOn("preTool", matcher));
        final HookRegistryReloader reloader = reloader(HOST_SHELL);

        assertThatThrownBy(reloader::bootstrap).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("hooks config " + projectHooksFile().toAbsolutePath())
                .hasMessageContaining("(PROJECT layer) is invalid").hasMessageContaining("preTool entry #0")
                .hasMessageContaining("the matcher could not be parsed").hasMessageContaining("can match no");
        assertThat(registry.isEmpty()).isTrue();
        verify(manager, never()).executeOnConfigReload(any());
    }

    @ParameterizedTest(name = "preTool matcher \"{0}\"")
    @ValueSource(strings = {"Bash", "*", "Edit|Write", "mcp__*", "mcp__github__*", "*Search", "schedule_task",
            "deriver.*", "Bash(git push*--force*)", "Bash(FOO=1 make *)", "Bash(npm run deploy:*)", "Edit(**/*.env)"})
    void bootstrap_preToolMatcherThatCanMatch_isRegistered(String matcher) throws Exception {
        writeProjectHooks(denyOn("preTool", matcher));

        assertThatCode(reloader(HOST_SHELL)::bootstrap).doesNotThrowAnyException();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
    }

    @Test
    void bootstrap_sameMatcherOnPostTool_isAWarnAndTheRestApplies() throws Exception {
        // postTool cannot block: its unparseable matcher keeps the name-only fallback and the WARN it always had.
        writeProjectHooks("""
                {"hooks":{
                  "postTool":[{"matcher":"^Edit$","hooks":[{"type":"command","command":"audit.sh"}]}],
                  "preTool":[{"matcher":"Bash","hooks":[{"type":"deny","reason":"guard"}]}]
                }}""");

        assertThatCode(reloader(HOST_SHELL)::bootstrap).doesNotThrowAnyException();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
        assertThat(registry.getHooks(HookEventType.POST_TOOL)).hasSize(1);
    }

    @Test
    void reload_preToolMatcherNoToolCanMatch_keepsThePreviousConfig() throws Exception {
        writeProjectHooks(denyOn("preTool", "Bash"));
        final HookRegistryReloader reloader = reloader(HOST_SHELL);
        assertThat(reloader.bootstrap()).isTrue();
        final List<PreToolHook> before = List.copyOf(registry.getHooks(HookEventType.PRE_TOOL));

        // An edit that would have swapped a live guard for one that never fires.
        writeProjectHooks(denyOn("preTool", "Bash & input.command~^rm"));

        assertThat(reloader.reload(2L, projectHooksFile())).isFalse();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEqualTo(before);
    }

    private static String denyOn(String event, String matcher) {
        return "{\"hooks\":{\"" + event + "\":[{\"matcher\":\"" + matcher
                + "\",\"hooks\":[{\"type\":\"deny\",\"reason\":\"no\"}]}]}}";
    }

    // --- (3) unknown event names -------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is a near-miss of {1}")
    @CsvSource({"preTol, preTool", "PreToolUs, PreToolUse", "pretoool, preTool", "onStar, onStart",
            "preCompat, preCompact", "PermisionRequest, permissionRequest", "permissionRequests, permissionRequest",
            "onstrat, onStart"})
    void bootstrap_eventNameThatIsANearMissOfAGuardEvent_stopsStartup(String typo, String meant) throws Exception {
        writeProjectHooks("{\"hooks\":{\"" + typo + "\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"g.sh\"}]}]}}");

        assertThatThrownBy(reloader(HOST_SHELL)::bootstrap).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("hooks config " + projectHooksFile().toAbsolutePath())
                .hasMessageContaining("(PROJECT layer) is invalid").hasMessageContaining("unknown event '" + typo + "'")
                .hasMessageContaining("did you mean '" + meant + "'");
        assertThat(registry.isEmpty()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // near-misses of events that cannot block: a WARN with the suggestion, and the rest of the file applies
            "postTol", "PostToolUs", "onStp", "SubagentStopp", "sessionstrat",
            // nothing AIMON knows is close: an event from a newer config, or another tool's
            "TeammateIdle", "PostToolUseFailure", "ConfigChange", "GibberishEvent",
            // deliberately accepted so Claude Code configs import
            "Notification", "UserPromptSubmit", "stop_hook_active"})
    void bootstrap_otherUnknownEventNames_areSkippedAndTheRestApplies(String name) throws Exception {
        writeProjectHooks("{\"hooks\":{\"" + name + "\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"x.sh\"}]}],"
                + "\"preTool\":[{\"hooks\":[{\"type\":\"deny\",\"reason\":\"guard\"}]}]}}");

        assertThatCode(reloader(HOST_SHELL)::bootstrap).doesNotThrowAnyException();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
    }

    @Test
    void nearestKnownEvent_isSuggestedOnlyWithinASmallEditDistance() {
        assertThat(HookEventName.nearest("preTol")).hasValueSatisfying(near -> {
            assertThat(near.getName()).isEqualTo("preTool");
            assertThat(near.getDistance()).isEqualTo(1);
            assertThat(near.isGuard()).isTrue();
        });
        assertThat(HookEventName.nearest("postTol")).hasValueSatisfying(near -> {
            assertThat(near.getName()).isEqualTo("postTool");
            assertThat(near.isGuard()).isFalse();
        });
        // Spelled as the config spelled its neighbours: a Claude Code name is answered with a Claude Code name.
        assertThat(HookEventName.nearest("PreToolUs")).map(HookEventName.Nearest::getName).contains("PreToolUse");
        assertThat(HookEventName.nearest("TeammateIdle")).isEmpty();
        assertThat(HookEventName.nearest("x")).isEmpty();
    }

    // --- hot reload mirrors a broken file: the previous config stays --------------------------------------------

    @Test
    void reload_inapplicableGuardEntry_keepsThePreviousConfigAndReportsTheFile() throws Exception {
        writeProjectHooks("""
                {"hooks":{"preTool":[{"matcher":"Bash","hooks":[{"type":"deny","reason":"first"}]}]}}""");
        final HookRegistryReloader reloader = reloader(HOST_SHELL);
        assertThat(reloader.bootstrap()).isTrue();
        final List<PreToolHook> before = List.copyOf(registry.getHooks(HookEventType.PRE_TOOL));

        writeProjectHooks("""
                {"hooks":{"preTool":[{"matcher":"Bash","hooks":[{"type":"command"}]}]}}""");

        assertThat(reloader.reload(2L, projectHooksFile())).isFalse();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEqualTo(before);
        final ArgumentCaptor<OnConfigReloadContext> captor = ArgumentCaptor.forClass(OnConfigReloadContext.class);
        verify(manager, times(1)).executeOnConfigReload(captor.capture());
        assertThat(captor.getValue().isSuccessful()).isFalse();
        assertThat(captor.getValue().getFailureReason()).startsWith("load/merge failed").contains(
                projectHooksFile().toAbsolutePath().toString(), "(PROJECT layer) is invalid",
                "preTool entry #0, handler #0");
    }

    @Test
    void reload_guardEventTypo_keepsThePreviousConfig() throws Exception {
        writeProjectHooks("""
                {"hooks":{"preTool":[{"matcher":"Bash","hooks":[{"type":"deny","reason":"first"}]}]}}""");
        final HookRegistryReloader reloader = reloader(HOST_SHELL);
        assertThat(reloader.bootstrap()).isTrue();
        final List<PreToolHook> before = List.copyOf(registry.getHooks(HookEventType.PRE_TOOL));

        // The operator renames the key by accident: without the check the reload "succeeds" with the guard gone.
        writeProjectHooks("""
                {"hooks":{"preTol":[{"matcher":"Bash","hooks":[{"type":"deny","reason":"first"}]}]}}""");

        assertThat(reloader.reload(2L, projectHooksFile())).isFalse();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEqualTo(before);
    }

    // --- an http handler is applicable once its executor is wired ----------------------------------------------

    @Test
    void bootstrap_httpGuardWithItsExecutorWired_isRegistered() throws Exception {
        writeProjectHooks("""
                {"hooks":{"preTool":[{"hooks":[{"type":"http","url":"https://example.test/h"}]}]}}""");
        final HookRegistryReloader reloader = new HookRegistryReloader(loader, new HookConfigMerger(),
                new HookRegistryApplier(HOST_SHELL, HttpActionExecutor.createDefault(), null, Map.of()), registry,
                manager, INVOKER);

        assertThat(reloader.bootstrap()).isTrue();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private HookRegistryReloader reloader(ShellActionExecutor shellExecutor) {
        return new HookRegistryReloader(loader, new HookConfigMerger(),
                new HookRegistryApplier(shellExecutor, null, null, Map.of()), registry, manager, INVOKER);
    }

    private Path projectHooksFile() {
        return projectDir.resolve("hooks.json");
    }

    private void writeProjectHooks(String json) throws Exception {
        Files.writeString(projectHooksFile(), json);
    }
}
