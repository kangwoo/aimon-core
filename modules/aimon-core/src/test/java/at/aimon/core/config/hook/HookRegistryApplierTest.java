package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.base.UserLocale;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;
import at.aimon.core.skill.hook.declarative.HostShellActionExecutor;
import at.aimon.core.skill.hook.declarative.HttpActionExecutor;
import at.aimon.core.skill.hook.declarative.McpActionExecutor;
import at.aimon.core.skill.hook.declarative.NoOpShellActionExecutor;

@DisplayName("HookRegistryApplier")
class HookRegistryApplierTest {

    private final JacksonHookConfigParser parser = new JacksonHookConfigParser();
    private final HookConfigMerger merger = new HookConfigMerger();

    private HookRegistryApplier bootstrap() {
        return new HookRegistryApplier(new HostShellActionExecutor(mock(VirtualShell.class)), null, null, Map.of());
    }

    // --- failOpen and executors without shell support (EE-51) -------------------------------------------------------

    @Test
    @DisplayName("an executor without shell support registers no command handler that is not a guard")
    void shellUnsupportedExecutorSkipsCommandHandlers() {
        final DefaultHookRegistry registry = new DefaultHookRegistry();

        // The handlers that may be skipped: those on an event that cannot block, and those that declared failOpen.
        // A command *guard* in the same position stops the load instead (EE-72, HookConfigGuardEntryStrictnessTest).
        new HookRegistryApplier(NoOpShellActionExecutor.INSTANCE, null, null, Map.of()).apply(merged("""
                {"hooks":{
                  "preTool":[{"hooks":[{"type":"command","command":"audit.sh","failOpen":true},
                                       {"type":"deny","reason":"no"}]}],
                  "onStart":[{"hooks":[{"type":"command","command":"note.sh","failOpen":true}]}],
                  "postTool":[{"hooks":[{"type":"command","command":"audit.sh"}]}]
                }}"""), registry);

        // Only the deny handler survives: it needs no shell.
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
        assertThat(registry.getHooks(HookEventType.ON_START)).isEmpty();
        assertThat(registry.getHooks(HookEventType.POST_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("a command handler blocks when its command cannot run, unless it declares failOpen")
    void failOpenIsPassedToTheHook() throws Exception {
        final VirtualShell hostShell = mock(VirtualShell.class);
        when(hostShell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new ShellTimeoutException("timeout", Duration.ofSeconds(1), "", ""));
        final DefaultHookRegistry registry = new DefaultHookRegistry();

        new HookRegistryApplier(new HostShellActionExecutor(hostShell), null, null, Map.of()).apply(merged("""
                {"hooks":{"onStart":[{"hooks":[
                  {"type":"command","command":"gate.sh"},
                  {"type":"command","command":"audit.sh","failOpen":true}
                ]}]}}"""), registry);

        final OnStartContext context = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT)
                .invokerName("agent").hookRegistry(registry).userLocale(UserLocale.createDefault()).userMessage("hi")
                .build();
        final HookResult guard = registry.getHooks(HookEventType.ON_START).get(0).execute(context);
        final HookResult audit = registry.getHooks(HookEventType.ON_START).get(1).execute(context);

        assertThat(guard.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(guard.getFeedback().orElseThrow()).contains("timed out").doesNotContain("failOpen");
        assertThat(audit.getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    @DisplayName("an http or mcp handler blocks when it gets no verdict, unless it declares failOpen (EE-65)")
    void failOpenIsHonouredForHttpAndMcpHandlers() {
        final McpClientManager noServers = mock(McpClientManager.class);
        when(noServers.getClient("policy")).thenReturn(Optional.empty());
        final DefaultHookRegistry registry = new DefaultHookRegistry();

        // Nothing listens on port 1 and the MCP server is not registered: neither call can produce a verdict.
        new HookRegistryApplier(new HostShellActionExecutor(mock(VirtualShell.class)),
                HttpActionExecutor.createDefault(), new McpActionExecutor(noServers, new ObjectMapper()), Map.of())
                .apply(merged("""
                        {"hooks":{"preTool":[{"hooks":[
                          {"type":"http","url":"http://127.0.0.1:1/policy"},
                          {"type":"http","url":"http://127.0.0.1:1/audit","failOpen":true},
                          {"type":"mcp","server":"policy","tool":"evaluate"},
                          {"type":"mcp","server":"policy","tool":"audit","failOpen":true}
                        ]}]}}"""), registry);

        final List<PreToolHook> hooks = registry.getHooks(HookEventType.PRE_TOOL);
        final PreToolContext context = preToolContext(registry);
        assertThat(hooks).hasSize(4);
        assertThat(hooks.get(0).execute(context).getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(hooks.get(0).execute(context).getFeedback().orElseThrow())
                .contains("could not get a verdict from its http call").doesNotContain("failOpen")
                .doesNotContain("127.0.0.1");
        assertThat(hooks.get(1).execute(context).getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(hooks.get(2).execute(context).getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(hooks.get(3).execute(context).getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    @DisplayName("preTool command entries are registered as pre-tool hooks")
    void preToolCommandRegistered() {
        final HookConfigDocument doc = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"x\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
        assertThat(registry.getHooks(HookEventType.POST_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("postTool entries are registered as post-tool hooks")
    void postToolCommandRegistered() {
        final HookConfigDocument doc = parser.parse(
                "{\"hooks\":{\"PostToolUse\":[{\"matcher\":\"*\",\"hooks\":[{\"type\":\"command\",\"command\":\"x\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.POST_TOOL)).hasSize(1);
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("type=deny on postTool is skipped with no registration")
    void denyOnPostToolSkipped() {
        final HookConfigDocument doc = parser.parse(
                "{\"hooks\":{\"PostToolUse\":[{\"matcher\":\"*\",\"hooks\":[{\"type\":\"deny\",\"reason\":\"nope\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.POST_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("non-shell action on onStart/onStop is skipped")
    void nonShellOnLifecycleSkipped() {
        final HookConfigDocument doc = parser.parse(
                "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"http\"," + "\"url\":\"https://example.test/h\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.ON_STOP)).isEmpty();
    }

    @Test
    @DisplayName("onStart shell entries are registered as on-start hooks")
    void onStartShellRegistered() {
        final HookConfigDocument doc = parser
                .parse("{\"hooks\":{\"onStart\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"echo hi\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.ON_START)).hasSize(1);
    }

    // --- which shell a hooks.json command runs in (EE-12) -----------------------------------------------------------

    private static final String SESSION_AND_CONFIG_COMMANDS = """
            {"hooks":{
              "onSessionStart":[{"hooks":[{"type":"command","command":"echo start"}]}],
              "onSessionEnd":[{"hooks":[{"type":"command","command":"echo end"}]}],
              "onConfigReload":[{"hooks":[{"type":"command","command":"echo reload"}]}],
              "onStart":[{"hooks":[{"type":"command","command":"echo hi"}]}]
            }}""";

    private MergedHookConfig merged(String json) {
        return merger.merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, parser.parse(json)).build());
    }

    @Test
    @DisplayName("a host shell executor registers commands on events that fire outside any execution")
    void hostExecutorRegistersOutOfExecutionCommands() throws Exception {
        final VirtualShell hostShell = mock(VirtualShell.class);
        when(hostShell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(0, "", "", Duration.ofMillis(1)));
        final DefaultHookRegistry registry = new DefaultHookRegistry();

        new HookRegistryApplier(new HostShellActionExecutor(hostShell), null, null, Map.of())
                .apply(merged(SESSION_AND_CONFIG_COMMANDS), registry);

        assertThat(registry.getHooks(HookEventType.ON_SESSION_START)).hasSize(1);
        assertThat(registry.getHooks(HookEventType.ON_SESSION_END)).hasSize(1);
        assertThat(registry.getHooks(HookEventType.ON_CONFIG_RELOAD)).hasSize(1);
        assertThat(registry.getHooks(HookEventType.ON_START)).hasSize(1);

        // And it actually runs there: the session-start context has no execution environment, yet the command fires.
        registry.getHooks(HookEventType.ON_SESSION_START).get(0)
                .execute(OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                        .hookRegistry(registry).userLocale(UserLocale.createDefault()).build());
        verify(hostShell).execute(any(ShellCommand.class), any(ExecutionOptions.class));
    }

    @Test
    @DisplayName("an environment-bound executor skips commands on events that have no execution environment")
    void environmentBoundExecutorSkipsOutOfExecutionCommands() {
        // Not the CLI's wiring — an embedder's. Registering these would yield hooks that are skipped on every firing
        // with nothing but a WARN each time; refusing at apply time says so once.
        final DefaultHookRegistry registry = new DefaultHookRegistry();

        new HookRegistryApplier(new DefaultShellActionExecutor(), null, null, Map.of())
                .apply(merged(SESSION_AND_CONFIG_COMMANDS), registry);

        assertThat(registry.getHooks(HookEventType.ON_SESSION_START)).isEmpty();
        assertThat(registry.getHooks(HookEventType.ON_SESSION_END)).isEmpty();
        assertThat(registry.getHooks(HookEventType.ON_CONFIG_RELOAD)).isEmpty();
        // An in-execution event in the same document is unaffected.
        assertThat(registry.getHooks(HookEventType.ON_START)).hasSize(1);
    }

    private static final String PRE_TOOL_COMMAND_WITH_REWAKE = """
            {"hooks":{"preTool":[{"hooks":[{"type":"command","command":"x",
              "asyncRewake":{"trigger":{"delay":"5m"},"maxAttempts":2,"reason":"retry"}}]}]}}""";

    private static PreToolContext preToolContext(DefaultHookRegistry registry) {
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .userLocale(UserLocale.createDefault()).toolUse(ToolUse.of("call-1", "Bash", Map.of()))
                .iterationCount(1).build();
    }

    @Test
    @DisplayName("a host shell executor keeps a command's asyncRewake")
    void hostExecutorKeepsCommandRewake() {
        final DefaultHookRegistry registry = new DefaultHookRegistry();
        new HookRegistryApplier(new HostShellActionExecutor(mock(VirtualShell.class)), null, null, Map.of())
                .apply(merged(PRE_TOOL_COMMAND_WITH_REWAKE), registry);

        final HookResult result = registry.getHooks(HookEventType.PRE_TOOL).get(0).execute(preToolContext(registry));

        assertThat(result.getRewakeSpecs()).hasSize(1);
    }

    @Test
    @DisplayName("an environment-bound executor drops a command's asyncRewake: the replay has no environment")
    void environmentBoundExecutorDropsCommandRewake() {
        final DefaultHookRegistry registry = new DefaultHookRegistry();
        new HookRegistryApplier(new DefaultShellActionExecutor(), null, null, Map.of())
                .apply(merged(PRE_TOOL_COMMAND_WITH_REWAKE), registry);

        // The hook itself still registers — preTool fires inside an execution — but it schedules no replay.
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1);
        final HookResult result = registry.getHooks(HookEventType.PRE_TOOL).get(0).execute(preToolContext(registry));
        assertThat(result.getRewakeSpecs()).isEmpty();
    }

    @Test
    @DisplayName("empty handler list on an event that cannot block is skipped with WARN (no hooks registered)")
    void emptyHandlersSkipped() {
        final HookConfigDocument doc = parser
                .parse("{\"hooks\":{\"PostToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.POST_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("invalid handler (command without 'command' field) on postTool is skipped, others survive")
    void invalidHandlerSkipped() {
        final HookConfigDocument doc = parser.parse("{\"hooks\":{\"PostToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\"}," + "{\"type\":\"command\",\"command\":\"ok\"}" + "]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.POST_TOOL)).hasSize(1);
    }

    @Test
    @DisplayName("SKILL entries are skipped (handled by SkillHookActivator)")
    void skillEntriesSkipped() {
        final HookConfigDocument doc = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"x\"}]}]}}");
        final MergedHookConfig merged = merger.merge(LayeredHookConfig.builder().putSkill("my-skill", doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("USER -> PROJECT -> LOCAL precedence is preserved as registration order")
    void layeredOrderPreserved() {
        final HookConfigDocument user = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"u\"}]}]}}");
        final HookConfigDocument project = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"p\"}]}]}}");
        final HookConfigDocument local = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"l\"}]}]}}");
        final MergedHookConfig merged = merger.merge(LayeredHookConfig.builder().put(HookConfigSource.USER, user)
                .put(HookConfigSource.PROJECT, project).put(HookConfigSource.LOCAL, local).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(3);
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).extracting(h -> h.getClass().getSimpleName())
                .containsOnly("DeclarativePreToolHook");
    }

    @Test
    @DisplayName("invalid matcher on postTool falls back to name-only without throwing")
    void invalidMatcherFallback() {
        final HookConfigDocument doc = parser.parse("{\"hooks\":{\"PostToolUse\":[{\"matcher\":\"\\u0000(\","
                + "\"hooks\":[{\"type\":\"command\",\"command\":\"x\"}]}]}}");
        final MergedHookConfig merged = merger
                .merge(LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build());

        final DefaultHookRegistry registry = new DefaultHookRegistry();
        bootstrap().apply(merged, registry);

        assertThat(registry.getHooks(HookEventType.POST_TOOL)).hasSize(1);
    }

    /**
     * The discriminator fed to {@code DeclarativeHookId} is the contract that async-rewake routing and hot-reload
     * cancellation depend on: a hook's id must be unique among its siblings and must not move when an unrelated
     * document changes.
     */
    @Nested
    @DisplayName("hook id stability")
    class HookIdStability {

        private static final String USER_DOC = "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\","
                + "\"hooks\":[{\"type\":\"command\",\"command\":\"user-a\"}]}]}}";
        private static final String USER_DOC_TWO_HANDLERS = "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\","
                + "\"hooks\":[{\"type\":\"command\",\"command\":\"a\"},"
                + "{\"type\":\"command\",\"command\":\"b\"}]}]}}";
        private static final String PROJECT_DOC = "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Read\","
                + "\"hooks\":[{\"type\":\"command\",\"command\":\"project-a\"}]}]}}";

        private List<String> preToolHookIds(LayeredHookConfig layered) {
            final DefaultHookRegistry registry = new DefaultHookRegistry();
            bootstrap().apply(merger.merge(layered), registry);
            return registry.getHooks(HookEventType.PRE_TOOL).stream().map(h -> h.getHookId()).toList();
        }

        @Test
        @DisplayName("two hooks declared in the same document get different ids")
        void siblingsInOneDocumentGetDistinctIds() {
            final List<String> ids = preToolHookIds(LayeredHookConfig.builder()
                    .put(HookConfigSource.USER, parser.parse(USER_DOC_TWO_HANDLERS)).build());

            assertThat(ids).hasSize(2).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("entries and handlers across one document are all distinct")
        void everyRegisteredHookHasAUniqueId() {
            final String doc = "{\"hooks\":{\"PreToolUse\":["
                    + "{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"a\"},"
                    + "{\"type\":\"command\",\"command\":\"b\"}]},"
                    + "{\"matcher\":\"Read\",\"hooks\":[{\"type\":\"command\",\"command\":\"c\"}]}]}}";

            final List<String> ids = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.PROJECT, parser.parse(doc)).build());

            assertThat(ids).hasSize(3).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a hook keeps the same id across a reload of identical config")
        void idsAreStableAcrossReloadOfIdenticalConfig() {
            final List<String> first = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.USER, parser.parse(USER_DOC))
                            .put(HookConfigSource.PROJECT, parser.parse(PROJECT_DOC)).build());
            final List<String> second = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.USER, parser.parse(USER_DOC))
                            .put(HookConfigSource.PROJECT, parser.parse(PROJECT_DOC)).build());

            assertThat(second).isEqualTo(first);
        }

        /**
         * Regression guard: the entry index used to be counted across the merged dispatch stream, so adding a PROJECT
         * entry shifted every USER hook's id and orphaned its pending rewake envelopes.
         */
        @Test
        @DisplayName("a hook's id is unchanged when an unrelated hook is added to a different layer")
        void idIsUnaffectedByEditsInAnotherLayer() {
            final List<String> userOnly = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.USER, parser.parse(USER_DOC)).build());

            final List<String> withProject = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.USER, parser.parse(USER_DOC))
                            .put(HookConfigSource.PROJECT, parser.parse(PROJECT_DOC)).build());

            assertThat(userOnly).hasSize(1);
            assertThat(withProject).hasSize(2).containsAll(userOnly).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("removing a lower-precedence layer does not re-id the surviving layer's hooks")
        void idIsUnaffectedByRemovalOfAnotherLayer() {
            final List<String> both = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.USER, parser.parse(USER_DOC))
                            .put(HookConfigSource.PROJECT, parser.parse(PROJECT_DOC)).build());

            final List<String> projectOnly = preToolHookIds(
                    LayeredHookConfig.builder().put(HookConfigSource.PROJECT, parser.parse(PROJECT_DOC)).build());

            assertThat(projectOnly).hasSize(1);
            assertThat(both).containsAll(projectOnly);
        }
    }
}
