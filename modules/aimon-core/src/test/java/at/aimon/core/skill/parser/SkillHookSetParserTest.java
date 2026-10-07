package at.aimon.core.skill.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.skill.hook.SkillHookSet;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.DeclarativeOnStartHook;
import at.aimon.core.skill.hook.declarative.DeclarativeOnStopHook;
import at.aimon.core.skill.hook.declarative.DeclarativePermissionDeniedHook;
import at.aimon.core.skill.hook.declarative.DeclarativePermissionRequestHook;
import at.aimon.core.skill.hook.declarative.DeclarativePostCompactHook;
import at.aimon.core.skill.hook.declarative.DeclarativePostToolHook;
import at.aimon.core.skill.hook.declarative.DeclarativePreCompactHook;
import at.aimon.core.skill.hook.declarative.DeclarativePreToolHook;
import at.aimon.core.skill.hook.declarative.DeclarativeSubagentStartHook;
import at.aimon.core.skill.hook.declarative.DeclarativeSubagentStopHook;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;
import at.aimon.core.skill.hook.declarative.NoOpShellActionExecutor;
import at.aimon.core.skill.hook.declarative.ShellActionExecutor;
import at.aimon.core.skill.hook.declarative.ShellHookOutcome;

class SkillHookSetParserTest {

    private final SkillHookSetParser denyOnlyParser = new SkillHookSetParser();
    private final SkillHookSetParser shellParser = new SkillHookSetParser(new DefaultShellActionExecutor());

    @Test
    void parse_nullHooksNode_returnsEmpty() {
        SkillHookSet set = denyOnlyParser.parse("s", null);

        assertThat(set.isEmpty()).isTrue();
    }

    @Test
    void parse_nullSkillName_throws() {
        assertThatThrownBy(() -> denyOnlyParser.parse(null, Map.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parse_nonMappingHooks_throws() {
        assertThatThrownBy(() -> denyOnlyParser.parse("s", "oops")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hooks").hasMessageContaining("mapping");
    }

    @Test
    void parse_unknownEvent_throws() {
        Map<String, Object> hooks = Map.of("preWeird",
                List.of(Map.of("action", Map.of("type", "deny", "reason", "x"))));

        assertThatThrownBy(() -> denyOnlyParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preWeird");
    }

    @Test
    void parse_eventValueNotList_throws() {
        Map<String, Object> hooks = Map.of("preTool", Map.of("action", Map.of("type", "deny", "reason", "x")));

        assertThatThrownBy(() -> denyOnlyParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preTool").hasMessageContaining("list");
    }

    @Test
    void parse_hookEntryNotMapping_throws() {
        Map<String, Object> hooks = Map.of("preTool", List.of("nope"));

        assertThatThrownBy(() -> denyOnlyParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preTool[0]").hasMessageContaining("mapping");
    }

    @Test
    void parse_missingAction_throws() {
        Map<String, Object> hooks = Map.of("preTool", List.of(Map.of("matcher", "Bash")));

        assertThatThrownBy(() -> denyOnlyParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    @Test
    void parse_actionNotMapping_throws() {
        Map<String, Object> hooks = Map.of("preTool", List.of(Map.of("action", "deny")));

        assertThatThrownBy(() -> denyOnlyParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action").hasMessageContaining("mapping");
    }

    @Test
    void parse_unknownActionType_throws() {
        Map<String, Object> hooks = Map.of("preTool", List.of(Map.of("action", Map.of("type", "burn-the-house"))));

        assertThatThrownBy(() -> denyOnlyParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("burn-the-house");
    }

    @Test
    void parse_denyOnPreTool_buildsHook() {
        Map<String, Object> hooks = Map.of("preTool",
                List.of(Map.of("matcher", "Bash", "action", Map.of("type", "deny", "reason", "blocked"))));

        SkillHookSet set = denyOnlyParser.parse("s", hooks);

        assertThat(set.getPreToolHooks()).hasSize(1);
        assertThat(set.getPostToolHooks()).isEmpty();
    }

    @Test
    void parse_denyWithoutReason_throws() {
        Map<String, Object> hooks = Map.of("preTool", List.of(Map.of("action", Map.of("type", "deny"))));

        assertThatThrownBy(() -> denyOnlyParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void parse_denyOnPostTool_throws() {
        Map<String, Object> hooks = Map.of("postTool",
                List.of(Map.of("action", Map.of("type", "deny", "reason", "x"))));

        assertThatThrownBy(() -> denyOnlyParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deny").hasMessageContaining("preTool");
    }

    @Test
    void parse_denyOnOnStart_throws() {
        Map<String, Object> hooks = Map.of("onStart", List.of(Map.of("action", Map.of("type", "deny", "reason", "x"))));

        assertThatThrownBy(() -> denyOnlyParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deny");
    }

    @Test
    void parse_denyOnOnStop_throws() {
        Map<String, Object> hooks = Map.of("onStop", List.of(Map.of("action", Map.of("type", "deny", "reason", "x"))));

        assertThatThrownBy(() -> denyOnlyParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deny");
    }

    @Test
    void parse_shellWithoutSupport_throwsClearMessage() {
        Map<String, Object> hooks = Map.of("preTool",
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo"))));

        assertThatThrownBy(() -> denyOnlyParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shell").hasMessageContaining("not supported");
    }

    @Test
    void parse_shellOnAllFourEvents_buildsExpectedHooks() {
        Map<String, Object> hooks = Map.of("preTool",
                List.of(Map.of("matcher", "Bash", "action", Map.of("type", "shell", "command", "echo a"))), "postTool",
                List.of(Map.of("matcher", "*", "action", Map.of("type", "shell", "command", "echo b"))), "onStart",
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo c"))), "onStop",
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo d"))));

        SkillHookSet set = shellParser.parse("s", hooks);

        assertThat(set.getPreToolHooks()).hasSize(1);
        assertThat(set.getPostToolHooks()).hasSize(1);
        assertThat(set.getOnStartHooks()).hasSize(1);
        assertThat(set.getOnStopHooks()).hasSize(1);
    }

    @Test
    void parse_shellWithoutCommand_throws() {
        Map<String, Object> hooks = Map.of("preTool", List.of(Map.of("action", Map.of("type", "shell"))));

        assertThatThrownBy(() -> shellParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }

    @Test
    void parse_shellTimeoutInteger_accepted() {
        Map<String, Object> hooks = Map.of("preTool",
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo", "timeoutMs", 1500))));

        SkillHookSet set = shellParser.parse("s", hooks);

        assertThat(set.getPreToolHooks()).hasSize(1);
    }

    @Test
    void parse_shellTimeoutLong_accepted() {
        Map<String, Object> hooks = Map.of("preTool",
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo", "timeoutMs", 1500L))));

        SkillHookSet set = shellParser.parse("s", hooks);

        assertThat(set.getPreToolHooks()).hasSize(1);
    }

    @Test
    void parse_shellTimeoutZero_throws() {
        Map<String, Object> hooks = Map.of("preTool",
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo", "timeoutMs", 0))));

        assertThatThrownBy(() -> shellParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs").hasMessageContaining("positive");
    }

    @Test
    void parse_shellTimeoutNegative_throws() {
        Map<String, Object> hooks = Map.of("preTool",
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo", "timeoutMs", -10))));

        assertThatThrownBy(() -> shellParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
    }

    @Test
    void parse_shellTimeoutNonNumber_throws() {
        Map<String, Object> hooks = Map.of("preTool",
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo", "timeoutMs", "5s"))));

        assertThatThrownBy(() -> shellParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
    }

    @Test
    void parse_matcherOnOnStart_throws() {
        Map<String, Object> hooks = Map.of("onStart",
                List.of(Map.of("matcher", "Bash", "action", Map.of("type", "shell", "command", "echo"))));

        assertThatThrownBy(() -> shellParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matcher").hasMessageContaining("onStart");
    }

    @Test
    void parse_matcherOnOnStop_throws() {
        Map<String, Object> hooks = Map.of("onStop",
                List.of(Map.of("matcher", "*", "action", Map.of("type", "shell", "command", "echo"))));

        assertThatThrownBy(() -> shellParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matcher");
    }

    @Test
    void parse_matcherNotString_throws() {
        Map<String, Object> hooks = Map.of("preTool",
                List.of(Map.of("matcher", 42, "action", Map.of("type", "shell", "command", "echo"))));

        assertThatThrownBy(() -> shellParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matcher").hasMessageContaining("string");
    }

    @ParameterizedTest(name = "{0} matcher \"{1}\"")
    @MethodSource("matchersNoToolCanMatch")
    void parse_matcherNoToolCanMatch_throws(String event, String matcher) {
        // EE-85: the text used to be taken whole as a tool name, so the deny guard loaded and never fired. The skill
        // parser throws on a matcher that does not parse under both tool events, as it does for every malformed field.
        Map<String, Object> hooks = Map.of(event,
                List.of(Map.of("matcher", matcher, "action", Map.of("type", "shell", "command", "echo"))));

        assertThatThrownBy(() -> shellParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hooks." + event + "[0].matcher could not be parsed")
                .hasMessageContaining("can match no");
    }

    static Stream<Arguments> matchersNoToolCanMatch() {
        return Stream.of("preTool", "postTool")
                .flatMap(event -> Stream.of("^Edit$", "tool=Bash", "Bash & input.command~^npm", "Bash Edit", "mcp__.*")
                        .map(matcher -> Arguments.of(event, matcher)));
    }

    @Test
    void parse_omittedMatcherOnPreTool_defaultsToAny() {
        Map<String, Object> hooks = Map.of("preTool", List.of(Map.of("action", Map.of("type", "deny", "reason", "x"))));

        SkillHookSet set = denyOnlyParser.parse("s", hooks);

        assertThat(set.getPreToolHooks()).hasSize(1);
    }

    @Test
    void constructor_nullExecutor_throws() {
        assertThatThrownBy(() -> new SkillHookSetParser((ShellActionExecutor) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void parse_noOpExecutor_isExplicitDefault() {
        // Sanity: the no-arg ctor should refuse shell actions just like NoOpShellActionExecutor.INSTANCE.
        Map<String, Object> hooks = Map.of("onStart",
                List.of(Map.of("action", Map.of("type", "shell", "command", "x"))));

        assertThatThrownBy(() -> new SkillHookSetParser(NoOpShellActionExecutor.INSTANCE).parse("s", hooks))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not supported");
    }

    // --- the full declarable surface ------------------------------------------------------------------------------

    /**
     * Every event a skill may declare, mapped to the concrete hook class the parser must build for it.
     *
     * <p>
     * Keyed by event name and cross-checked against {@link SkillHookSet#supportedEvents()} in
     * {@link #declarableEvents_matchSkillHookSetSupportedEvents()}, so widening the supported set without teaching the
     * parser (or this test) about the new event fails rather than silently going untested.
     */
    private static final Map<String, Class<?>> EXPECTED_HOOK_CLASS = expectedHookClasses();

    private static Map<String, Class<?>> expectedHookClasses() {
        final Map<String, Class<?>> byEvent = new LinkedHashMap<>();
        byEvent.put(DeclarativePreToolHook.EVENT_NAME, DeclarativePreToolHook.class);
        byEvent.put(DeclarativePostToolHook.EVENT_NAME, DeclarativePostToolHook.class);
        byEvent.put(DeclarativeOnStartHook.EVENT_NAME, DeclarativeOnStartHook.class);
        byEvent.put(DeclarativeOnStopHook.EVENT_NAME, DeclarativeOnStopHook.class);
        byEvent.put(DeclarativeSubagentStartHook.EVENT_NAME, DeclarativeSubagentStartHook.class);
        byEvent.put(DeclarativeSubagentStopHook.EVENT_NAME, DeclarativeSubagentStopHook.class);
        byEvent.put(DeclarativePermissionRequestHook.EVENT_NAME, DeclarativePermissionRequestHook.class);
        byEvent.put(DeclarativePermissionDeniedHook.EVENT_NAME, DeclarativePermissionDeniedHook.class);
        byEvent.put(DeclarativePreCompactHook.EVENT_NAME, DeclarativePreCompactHook.class);
        byEvent.put(DeclarativePostCompactHook.EVENT_NAME, DeclarativePostCompactHook.class);
        return Map.copyOf(byEvent);
    }

    static Stream<Arguments> declarableEvents() {
        return SkillHookSet.supportedEvents().stream()
                .map(type -> Arguments.of(type.name(), type, EXPECTED_HOOK_CLASS.get(type.name())));
    }

    @Test
    void declarableEvents_matchSkillHookSetSupportedEvents() {
        final Set<String> supported = SkillHookSet.supportedEvents().stream().map(HookEventType::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(EXPECTED_HOOK_CLASS.keySet()).containsExactlyInAnyOrderElementsOf(supported);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declarableEvents")
    void parse_everyDeclarableEvent_buildsItsDeclarativeHookClass(String eventName, HookEventType<?> eventType,
            Class<?> hookClass) {
        final Map<String, Object> hooks = Map.of(eventName,
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo " + eventName))));

        final SkillHookSet set = shellParser.parse("s", hooks);

        assertThat(hooksFor(set, eventType)).singleElement().isInstanceOf(hookClass);
        // Nothing else may be populated: a mis-routed event would otherwise show up as a hook on the wrong chain.
        assertThat(otherEventsOf(set, eventType)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declarableEvents")
    void parse_twoEntriesOfOneEvent_getDistinctHookIds(String eventName, HookEventType<?> eventType,
            Class<?> hookClass) {
        // A-1: without the frontmatter path as a discriminator both entries would share one id, and rewake routing
        // plus reload cancellation would then treat them as the same hook.
        final Map<String, Object> hooks = Map.of(eventName,
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo first")),
                        Map.of("action", Map.of("type", "shell", "command", "echo second"))));

        final SkillHookSet set = shellParser.parse("s", hooks);

        final List<? extends ExecutionHook<?>> hooksOfEvent = hooksFor(set, eventType);
        assertThat(hooksOfEvent).hasSize(2);
        assertThat(hooksOfEvent.get(0).getHookId()).isNotEqualTo(hooksOfEvent.get(1).getHookId());
        assertThat(hooksOfEvent.get(0).getHookId()).isEqualTo(hookClass.getName() + "@s#hooks." + eventName + "[0]");
        assertThat(hooksOfEvent.get(1).getHookId()).isEqualTo(hookClass.getName() + "@s#hooks." + eventName + "[1]");
    }

    /** Every declarable event except {@code preTool} — the only one a static {@code deny} action is valid on. */
    static Stream<Arguments> declarableEventsExceptPreTool() {
        return declarableEvents().filter(args -> !DeclarativePreToolHook.EVENT_NAME.equals(args.get()[0]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declarableEventsExceptPreTool")
    void parse_denyIsRejectedEverywhereExceptPreTool(String eventName, HookEventType<?> eventType, Class<?> hookClass) {
        final Map<String, Object> hooks = Map.of(eventName,
                List.of(Map.of("action", Map.of("type", "deny", "reason", "x"))));

        assertThatThrownBy(() -> shellParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deny").hasMessageContaining(DeclarativePreToolHook.EVENT_NAME);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"onSessionStart", "onSessionEnd", "onConfigReload"})
    void parse_sessionAndConfigLifecycleEvents_areRejectedWithAPointerToHooksJson(String eventName) {
        // These have a DeclarativeShellHookBinding entry, but they fire outside any skill invocation, so a per-skill
        // registration could never fire. The parser must say so rather than accept a hook nothing would ever run.
        final Map<String, Object> hooks = Map.of(eventName,
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo"))));

        assertThatThrownBy(() -> shellParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(eventName).hasMessageContaining("hooks.json")
                .hasMessageContaining("outside any execution").hasMessageContaining("execution environment");
    }

    @Test
    void supportedEvents_allFireInsideAnExecution() {
        // The invariant the shell check below rests on: frontmatter accepts no event without an execution environment.
        assertThat(SkillHookSet.supportedEvents()).allMatch(HookEventType::firesInsideExecution);
    }

    @Test
    void requireRunnableOn_environmentBoundExecutor_rejectsEventsOutsideAnExecution() {
        // Unreachable through parse() today (the unknown-event check fires first); exercised directly so the rule is
        // in place the day SkillHookSet.supportedEvents() grows such an event.
        final DefaultShellActionExecutor environmentBound = new DefaultShellActionExecutor();
        for (HookEventType<?> type : HookEventType.values()) {
            if (type.firesInsideExecution()) {
                SkillHookSetParser.requireRunnableOn(environmentBound, type, "hooks." + type.name() + "[0]");
            } else {
                assertThatThrownBy(() -> SkillHookSetParser.requireRunnableOn(environmentBound, type,
                        "hooks." + type.name() + "[0]")).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("hooks." + type.name() + "[0].action").hasMessageContaining(type.name())
                        .hasMessageContaining("outside any execution").hasMessageContaining("execution environment");
            }
        }
    }

    @Test
    void requireRunnableOn_executorThatNeedsNoEnvironment_acceptsEveryEvent() {
        final RecordingShellExecutor hostLike = new RecordingShellExecutor();
        for (HookEventType<?> type : HookEventType.values()) {
            SkillHookSetParser.requireRunnableOn(hostLike, type, "hooks." + type.name() + "[0]");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"onstart", "OnStart", "preTools", "post_tool", "subagentStopped"})
    void parse_misspelledEventName_isRejectedRatherThanIgnored(String eventName) {
        // Event names are case-sensitive and exact; a typo must fail loudly, because a silently dropped preTool
        // deny-guard would fail OPEN.
        final Map<String, Object> hooks = Map.of(eventName,
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo"))));

        assertThatThrownBy(() -> shellParser.parse("s", hooks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(eventName).hasMessageContaining("unknown event");
    }

    // --- failOpen (EE-51) -----------------------------------------------------------------------------------------

    @Test
    void parse_shellGuard_blocksWhenItCannotRun_andFailOpenLetsItPass() {
        final ShellActionExecutor cannotRun = new RecordingShellExecutor(
                ShellHookOutcome.notRun(ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE, "sandbox is down"));
        final Map<String, Object> hooks = Map.of("onStart",
                List.of(Map.of("action", Map.of("type", "shell", "command", "gate.sh")),
                        Map.of("action", Map.of("type", "shell", "command", "audit.sh"), "failOpen", true),
                        Map.of("action", Map.of("type", "shell", "command", "gate2.sh"), "failOpen", false)));

        final SkillHookSet set = new SkillHookSetParser(cannotRun).parse("deploy", hooks);

        final HookResult guard = set.getOnStartHooks().get(0).execute(onStartContext());
        assertThat(guard.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(guard.getFeedback().orElseThrow()).contains("guard hook 'deploy' (onStart)")
                .contains("execution environment unavailable: sandbox is down");
        assertThat(set.getOnStartHooks().get(1).execute(onStartContext()).getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(set.getOnStartHooks().get(2).execute(onStartContext()).getStatus()).isEqualTo(HookStatus.BLOCKED);
    }

    @ParameterizedTest(name = "failOpen: {0}")
    @MethodSource("nonBooleanFailOpen")
    void parse_failOpenThatIsNotABoolean_throws(Object value) {
        final Map<String, Object> entry = new java.util.HashMap<>();
        entry.put("action", Map.of("type", "shell", "command", "gate.sh"));
        entry.put("failOpen", value);

        assertThatThrownBy(() -> shellParser.parse("s", Map.of("preTool", List.of(entry))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hooks.preTool[0].failOpen")
                .hasMessageContaining("boolean");
    }

    static Stream<Object> nonBooleanFailOpen() {
        // null is an explicit "failOpen:" with no value — still not a boolean.
        return Stream.of("true", "false", 1, 0, null, List.of(true));
    }

    @Test
    void parse_failOpenOnADenyAction_isIgnored() {
        final Map<String, Object> hooks = Map.of("preTool",
                List.of(Map.of("action", Map.of("type", "deny", "reason", "no"), "failOpen", true)));

        final SkillHookSet set = denyOnlyParser.parse("s", hooks);

        assertThat(set.getPreToolHooks()).hasSize(1);
    }

    @Test
    void parse_httpAndMcpGuards_blockWithoutAVerdict_andFailOpenLetsThemPass() {
        // The MCP server is not registered and nothing listens on the HTTP port: neither call can produce a verdict.
        final at.aimon.core.mcp.McpClientManager noServers = org.mockito.Mockito
                .mock(at.aimon.core.mcp.McpClientManager.class);
        org.mockito.Mockito.when(noServers.getClient("policy")).thenReturn(java.util.Optional.empty());
        final SkillHookSetParser parser = new SkillHookSetParser(new RecordingShellExecutor(),
                at.aimon.core.skill.hook.declarative.HttpActionExecutor.createDefault(),
                new at.aimon.core.skill.hook.declarative.McpActionExecutor(noServers,
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                Map.of());
        final Map<String, Object> http = Map.of("type", "http", "url", "http://127.0.0.1:1/policy");
        final Map<String, Object> mcp = Map.of("type", "mcp", "server", "policy", "tool", "evaluate");
        final Map<String, Object> hooks = Map.of("preTool",
                List.of(Map.of("action", http), Map.of("action", http, "failOpen", true), Map.of("action", mcp),
                        Map.of("action", mcp, "failOpen", true)));

        final SkillHookSet set = parser.parse("deploy", hooks);

        final at.aimon.core.hook.event.PreToolContext context = at.aimon.core.hook.event.PreToolContext.builder()
                .executorType(at.aimon.core.agent.InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new at.aimon.core.hook.DefaultHookRegistry())
                .toolUse(at.aimon.core.llm.ToolUse.of("call-1", "Bash", Map.of())).iterationCount(1).build();
        assertThat(set.getPreToolHooks().get(0).execute(context).getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(set.getPreToolHooks().get(1).execute(context).getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(set.getPreToolHooks().get(2).execute(context).getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(set.getPreToolHooks().get(3).execute(context).getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void parse_failOpenOnAnAdvisoryEvent_isAcceptedAndHarmless() {
        final ShellActionExecutor cannotRun = new RecordingShellExecutor(
                ShellHookOutcome.notRun(ShellHookOutcome.Unrun.TIMEOUT, ""));
        final Map<String, Object> hooks = Map.of("onStop",
                List.of(Map.of("action", Map.of("type", "shell", "command", "bye.sh"), "failOpen", true)));

        final SkillHookSet set = new SkillHookSetParser(cannotRun).parse("s", hooks);

        assertThat(set.getOnStopHooks().get(0).execute(onStopContext()).getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    // --- ignoreInterrupt (EE-97) ----------------------------------------------------------------------------------

    @Test
    void parse_ignoreInterruptOnAReportEvent_reachesTheHook() {
        final Map<String, Object> shell = Map.of("type", "shell", "command", "cleanup.sh");
        final Map<String, Object> hooks = Map.of("onStop",
                List.of(Map.of("action", shell, "ignoreInterrupt", true),
                        Map.of("action", shell, "ignoreInterrupt", false), Map.of("action", shell)),
                "postTool", List.of(Map.of("action", shell, "ignoreInterrupt", true)));

        final SkillHookSet set = new SkillHookSetParser(new RecordingShellExecutor()).parse("deploy", hooks);

        assertThat(set.getOnStopHooks()).extracting(ExecutionHook::ignoresInterrupt).containsExactly(true, false,
                false);
        assertThat(set.getPostToolHooks()).extracting(ExecutionHook::ignoresInterrupt).containsExactly(true);
    }

    @ParameterizedTest(name = "ignoreInterrupt: {0}")
    @MethodSource("nonBooleanFailOpen")
    void parse_ignoreInterruptThatIsNotABoolean_throws(Object value) {
        final Map<String, Object> entry = new java.util.HashMap<>();
        entry.put("action", Map.of("type", "shell", "command", "cleanup.sh"));
        entry.put("ignoreInterrupt", value);

        assertThatThrownBy(() -> shellParser.parse("s", Map.of("onStop", List.of(entry))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hooks.onStop[0].ignoreInterrupt")
                .hasMessageContaining("boolean");
    }

    @Test
    void parse_ignoreInterruptOnAGuardEventOrANonShellAction_isDroppedAndTheHookIsKept() {
        final SkillHookSetParser parser = new SkillHookSetParser(new RecordingShellExecutor(),
                at.aimon.core.skill.hook.declarative.HttpActionExecutor.createDefault(), null, Map.of());
        final Map<String, Object> shell = Map.of("type", "shell", "command", "gate.sh");
        final Map<String, Object> hooks = Map.of("onStart", List.of(Map.of("action", shell, "ignoreInterrupt", true)),
                "preTool", List.of(Map.of("action", shell, "ignoreInterrupt", true)), "preCompact",
                List.of(Map.of("action", shell, "ignoreInterrupt", true)), "permissionRequest",
                List.of(Map.of("action", shell, "ignoreInterrupt", true)), "postTool", List.of(Map.of("action",
                        Map.of("type", "http", "url", "http://127.0.0.1:1/audit"), "ignoreInterrupt", true)));

        final ch.qos.logback.classic.Logger parserLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(SkillHookSetParser.class);
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        parserLogger.addAppender(appender);
        final SkillHookSet set;
        try {
            set = parser.parse("deploy", hooks);
        } finally {
            parserLogger.detachAppender(appender);
        }

        for (at.aimon.core.hook.HookEventType<?> event : List.of(at.aimon.core.hook.HookEventType.ON_START,
                at.aimon.core.hook.HookEventType.PRE_TOOL, at.aimon.core.hook.HookEventType.PRE_COMPACT,
                at.aimon.core.hook.HookEventType.PERMISSION_REQUEST, at.aimon.core.hook.HookEventType.POST_TOOL)) {
            assertThat(set.get(event)).as(event.name()).hasSize(1)
                    .allSatisfy(hook -> assertThat(hook.ignoresInterrupt()).isFalse());
        }
        // The hook classes refuse the option on a guard event by themselves, so the assertion above holds with the
        // parser's own check removed. The WARN is what only the parser produces: one per dropped key, naming the
        // entry and saying why.
        final List<String> warnings = appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("'ignoreInterrupt'")).toList();
        assertThat(warnings).hasSize(5);
        for (String guard : List.of("onStart", "preTool", "preCompact", "permissionRequest")) {
            assertThat(warnings).as(guard).filteredOn(message -> message.startsWith("hooks." + guard + "[0]:"))
                    .singleElement().asString().contains("has no effect on " + guard).contains("the event can block");
        }
        assertThat(warnings).filteredOn(message -> message.startsWith("hooks.postTool[0]:")).singleElement().asString()
                .contains("not a shell command").doesNotContain("can block");
    }

    @Test
    void parse_ignoreInterruptOnAReportEventWithAShellAction_isKeptWithoutAWarning() {
        // The other side of the test above: the WARN is tied to the cases the key is dropped in.
        final ch.qos.logback.classic.Logger parserLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(SkillHookSetParser.class);
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        parserLogger.addAppender(appender);
        final SkillHookSet set;
        try {
            set = shellParser.parse("deploy", Map.of("onStop", List
                    .of(Map.of("action", Map.of("type", "shell", "command", "cleanup.sh"), "ignoreInterrupt", true))));
        } finally {
            parserLogger.detachAppender(appender);
        }

        assertThat(set.getOnStopHooks()).singleElement()
                .satisfies(hook -> assertThat(hook.ignoresInterrupt()).isTrue());
        assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("'ignoreInterrupt'"));
    }

    // --- asyncRewake (B-1): declarable in hooks.json only ---------------------------------------------------------

    @Test
    void parse_handlerWithoutAsyncRewake_yieldsAResultWithNoRewakeSpec() {
        final RecordingShellExecutor executor = new RecordingShellExecutor();
        final Map<String, Object> hooks = Map.of("onStart",
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo hi"))));

        final SkillHookSet set = new SkillHookSetParser(executor).parse("s", hooks);
        final HookResult result = set.getOnStartHooks().get(0).execute(onStartContext());

        assertThat(result.getRewakeSpecs()).isEmpty();
    }

    @Test
    void parse_asyncRewakeInFrontmatter_isSilentlyIgnored() {
        // PINNED, not endorsed: 'asyncRewake' is a hooks.json feature — HookRegistryApplier parses it into
        // DeclarativeHookOptions#getRewakeSpec(). SkillHookSetParser reads only 'matcher' and 'action', so the block
        // below neither errors nor takes effect. See the risks note: an unknown key on a hook entry should arguably
        // be rejected the same way an unknown event name is.
        final RecordingShellExecutor executor = new RecordingShellExecutor();
        final Map<String, Object> hooks = Map.of("onStop",
                List.of(Map.of("action", Map.of("type", "shell", "command", "echo bye"), "asyncRewake",
                        Map.of("trigger", Map.of("type", "delay", "delayMs", 60_000), "timeoutMs", 600_000,
                                "maxAttempts", 3, "reason", "poll the deploy"))));

        final SkillHookSet set = new SkillHookSetParser(executor).parse("s", hooks);
        final HookResult result = set.getOnStopHooks().get(0).execute(onStopContext());

        assertThat(set.getOnStopHooks()).hasSize(1);
        assertThat(result.getRewakeSpecs()).isEmpty();
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private static List<? extends ExecutionHook<?>> hooksFor(SkillHookSet set, HookEventType<?> type) {
        return set.get(type);
    }

    private static List<? extends ExecutionHook<?>> otherEventsOf(SkillHookSet set, HookEventType<?> type) {
        final List<ExecutionHook<?>> others = new ArrayList<>();
        for (HookEventType<?> candidate : SkillHookSet.supportedEvents()) {
            if (!candidate.equals(type)) {
                others.addAll(hooksFor(set, candidate));
            }
        }
        return others;
    }

    private static OnStartContext onStartContext() {
        return OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).userMessage("go").build();
    }

    private static OnStopContext onStopContext() {
        final Instant now = Instant.now();
        final ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(1).duration(Duration.ofMillis(5))
                .startTime(now.minusMillis(5)).endTime(now).build();
        return OnStopContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).success(true).finalAnswer("done").metadata(metadata).build();
    }

    private static final HookRegistry REGISTRY = new DefaultHookRegistry();

    /** Shell executor stub that reports a clean exit so parsed hooks can actually be fired. */
    private static final class RecordingShellExecutor implements ShellActionExecutor {

        private final ShellHookOutcome outcome;

        RecordingShellExecutor() {
            this(ShellHookOutcome.of(0, "", ""));
        }

        RecordingShellExecutor(ShellHookOutcome outcome) {
            this.outcome = outcome;
        }

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
            return outcome;
        }
    }
}
