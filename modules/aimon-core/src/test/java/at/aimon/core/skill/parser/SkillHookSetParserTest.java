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

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
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
    private final SkillHookSetParser shellParser = new SkillHookSetParser(
            new DefaultShellActionExecutor(new StubShell()));

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
                .hasMessageContaining(eventName).hasMessageContaining("hooks.json");
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
                .hookRegistry(REGISTRY).environment(ENVIRONMENT).userMessage("go").build();
    }

    private static OnStopContext onStopContext() {
        final Instant now = Instant.now();
        final ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(1).duration(Duration.ofMillis(5))
                .startTime(now.minusMillis(5)).endTime(now).build();
        return OnStopContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENVIRONMENT).success(true).finalAnswer("done").metadata(metadata)
                .build();
    }

    private static final HookRegistry REGISTRY = new DefaultHookRegistry();
    private static final Environment ENVIRONMENT = Environment.createDefault();

    /** Shell executor stub that reports a clean exit so parsed hooks can actually be fired. */
    private static final class RecordingShellExecutor implements ShellActionExecutor {

        @Override
        public boolean isShellSupported() {
            return true;
        }

        @Override
        public void run(ShellAction action, Map<String, String> environmentOverrides) {
            run(action, environmentOverrides, null);
        }

        @Override
        public ShellHookOutcome run(ShellAction action, Map<String, String> environmentOverrides, String stdinPayload) {
            return ShellHookOutcome.of(0, "", "");
        }
    }

    /** Test stub — never actually executes. Only used so DefaultShellActionExecutor reports isShellSupported=true. */
    private static final class StubShell implements VirtualShell {
        @Override
        public ShellCommandResult execute(ShellCommand command) {
            return null;
        }

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options) {
            return null;
        }

        @Override
        public String getWorkingDirectory() {
            return null;
        }

        @Override
        public boolean supports(ShellFeature feature) {
            return false;
        }

        @Override
        public void close() {
        }
    }
}
