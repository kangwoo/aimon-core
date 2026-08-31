package at.aimon.core.skill.hook.declarative;

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

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.event.OnSessionEndContext;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.PermissionDeniedContext;
import at.aimon.core.hook.event.PermissionRequestContext;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.skill.hook.SkillHookSet;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Contract of the event-name → hook-constructor table shared by {@code hooks.json} and SKILL.md frontmatter.
 *
 * <p>
 * The table is the single place where an event name becomes a live hook, so the things worth pinning are the ones a
 * silent table edit would break: that every entry builds the class the registry expects, that the hook exports
 * <em>its own</em> event name (a copy/paste slip here would make a script see the wrong {@code AIMON_HOOK_EVENT}),
 * that the hook id stays discriminated so same-class siblings do not collide, and that the key set still lines up with
 * both {@link HookEventType#values()} and {@link SkillHookSet#supportedEvents()} — an event name in the table with no
 * matching event type (or vice versa) is dead config nothing would ever fire.
 */
class DeclarativeShellHookBindingTest {

    private static final HookRegistry REGISTRY = new DefaultHookRegistry();
    private static final Environment ENV = Environment.createDefault();
    private static final ShellAction ACTION = new ShellAction("notify.sh", Duration.ofSeconds(1));
    private static final String SKILL = "my-skill";
    private static final String DISCRIMINATOR = "handlers[0][1]";

    /** The two events that carry a matcher plus HTTP / MCP executors, and so are deliberately not in the table. */
    private static final Set<String> TOOL_INPUT_PAIR = Set.of(HookEventType.PRE_TOOL.name(),
            HookEventType.POST_TOOL.name());

    /** One row per table entry: event name, the hook class it must build, its event type and a firing context. */
    static Stream<Arguments> bindings() {
        return Stream.of(
                Arguments.of(DeclarativeOnStartHook.EVENT_NAME, DeclarativeOnStartHook.class, HookEventType.ON_START,
                        onStartContext()),
                Arguments.of(DeclarativeOnStopHook.EVENT_NAME, DeclarativeOnStopHook.class, HookEventType.ON_STOP,
                        onStopContext()),
                Arguments.of(DeclarativeOnSessionStartHook.EVENT_NAME, DeclarativeOnSessionStartHook.class,
                        HookEventType.ON_SESSION_START, onSessionStartContext()),
                Arguments.of(DeclarativeOnSessionEndHook.EVENT_NAME, DeclarativeOnSessionEndHook.class,
                        HookEventType.ON_SESSION_END, onSessionEndContext()),
                Arguments.of(DeclarativeSubagentStartHook.EVENT_NAME, DeclarativeSubagentStartHook.class,
                        HookEventType.SUBAGENT_START, subagentStartContext()),
                Arguments.of(DeclarativeSubagentStopHook.EVENT_NAME, DeclarativeSubagentStopHook.class,
                        HookEventType.SUBAGENT_STOP, subagentStopContext()),
                Arguments.of(DeclarativePreCompactHook.EVENT_NAME, DeclarativePreCompactHook.class,
                        HookEventType.PRE_COMPACT, preCompactContext()),
                Arguments.of(DeclarativePostCompactHook.EVENT_NAME, DeclarativePostCompactHook.class,
                        HookEventType.POST_COMPACT, postCompactContext()),
                Arguments.of(DeclarativePermissionRequestHook.EVENT_NAME, DeclarativePermissionRequestHook.class,
                        HookEventType.PERMISSION_REQUEST, permissionRequestContext()),
                Arguments.of(DeclarativePermissionDeniedHook.EVENT_NAME, DeclarativePermissionDeniedHook.class,
                        HookEventType.PERMISSION_DENIED, permissionDeniedContext()),
                Arguments.of(DeclarativeOnConfigReloadHook.EVENT_NAME, DeclarativeOnConfigReloadHook.class,
                        HookEventType.ON_CONFIG_RELOAD, onConfigReloadContext()));
    }

    // --- table entries -------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("bindings")
    void forEvent_buildsTheDeclaredHookClassUnderTheDeclaredEventType(String eventName, Class<?> hookClass,
            HookEventType<?> eventType, HookContext context) {
        final DeclarativeShellHookBinding<?> binding = DeclarativeShellHookBinding.forEvent(eventName).orElseThrow();

        final ExecutionHook<?> hook = binding.create(SKILL, ACTION, RecordingExecutor.ok(),
                DeclarativeHookOptions.none());

        assertThat(binding.getEventType()).isSameAs(eventType);
        assertThat(hook).isInstanceOf(hookClass);
        // The registry keys on the event type's hook interface, so a row wired to the wrong type would not register.
        assertThat(eventType.hookClass()).isAssignableFrom(hookClass);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bindings")
    void create_exportsItsOwnEventNameAsAimonHookEvent(String eventName, Class<?> hookClass, HookEventType<?> eventType,
            HookContext context) {
        final RecordingExecutor executor = RecordingExecutor.ok();
        final ExecutionHook<?> hook = DeclarativeShellHookBinding.forEvent(eventName).orElseThrow().create(SKILL,
                ACTION, executor, DeclarativeHookOptions.none());

        execute(hook, context);

        assertThat(executor.envs).hasSize(1);
        assertThat(executor.envs.get(0)).containsEntry(SkillHookEnv.AIMON_HOOK_EVENT, eventName)
                .containsEntry(SkillHookEnv.AIMON_SKILL_NAME, SKILL);
        // The table key, the exported variable and the registry key must all be the same string.
        assertThat(eventName).isEqualTo(eventType.name());
    }

    /**
     * The three session-labelled events fire both with and without a session behind them — a rewake replay drives the
     * same chain with nothing but an envelope id. Both env keys are exported either way, because the stdin JSON
     * payload is rendered from this same map and a key that comes and goes would change the document's shape between
     * firings of one event.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sessionLabelledContexts")
    void create_exportsSessionAndExecutionIdAsAnExclusivePair(String eventName, HookContext sessionBacked,
            HookContext sessionless) {
        final RecordingExecutor backedExecutor = RecordingExecutor.ok();
        execute(DeclarativeShellHookBinding.forEvent(eventName).orElseThrow().create(SKILL, ACTION, backedExecutor,
                DeclarativeHookOptions.none()), sessionBacked);

        assertThat(backedExecutor.envs.get(0)).containsEntry(SkillHookEnv.AIMON_SESSION_ID, "sess-1")
                .containsEntry(SkillHookEnv.AIMON_EXECUTION_ID, "");

        final RecordingExecutor sessionlessExecutor = RecordingExecutor.ok();
        execute(DeclarativeShellHookBinding.forEvent(eventName).orElseThrow().create(SKILL, ACTION, sessionlessExecutor,
                DeclarativeHookOptions.none()), sessionless);

        assertThat(sessionlessExecutor.envs.get(0)).containsEntry(SkillHookEnv.AIMON_SESSION_ID, "")
                .containsEntry(SkillHookEnv.AIMON_EXECUTION_ID, "rewake:env-1");
    }

    private static Stream<Arguments> sessionLabelledContexts() {
        final ExecutionId run = ExecutionId.of("rewake:env-1");
        return Stream.of(
                Arguments.of(DeclarativeOnSessionStartHook.EVENT_NAME,
                        OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                                .hookRegistry(REGISTRY).environment(ENV).sessionId(SessionId.of("sess-1")).build(),
                        OnSessionStartContext
                                .builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                                .hookRegistry(REGISTRY).environment(ENV).executionId(run).build()),
                Arguments.of(DeclarativeOnSessionEndHook.EVENT_NAME,
                        OnSessionEndContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                                .hookRegistry(REGISTRY).environment(ENV).sessionId(SessionId.of("sess-1")).build(),
                        OnSessionEndContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                                .hookRegistry(REGISTRY).environment(ENV).executionId(run).build()),
                Arguments.of(DeclarativePreCompactHook.EVENT_NAME,
                        PreCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                                .hookRegistry(REGISTRY).environment(ENV).trigger(CompactionTrigger.AUTO)
                                .sessionIdValue("sess-1").build(),
                        PreCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                                .hookRegistry(REGISTRY).environment(ENV).trigger(CompactionTrigger.AUTO)
                                .executionId(run).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bindings")
    void create_hookIdIsClassScopedAndCarriesTheDiscriminator(String eventName, Class<?> hookClass,
            HookEventType<?> eventType, HookContext context) {
        final DeclarativeShellHookBinding<?> binding = DeclarativeShellHookBinding.forEvent(eventName).orElseThrow();

        final ExecutionHook<?> discriminated = binding.create(SKILL, ACTION, RecordingExecutor.ok(),
                DeclarativeHookOptions.ofDiscriminator(DISCRIMINATOR));
        final ExecutionHook<?> plain = binding.create(SKILL, ACTION, RecordingExecutor.ok(),
                DeclarativeHookOptions.none());

        assertThat(discriminated.getHookId()).isEqualTo(hookClass.getName() + "@" + SKILL + "#" + DISCRIMINATOR);
        assertThat(plain.getHookId()).isEqualTo(hookClass.getName() + "@" + SKILL);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bindings")
    void create_twoSiblingsOfOneEvent_getDistinctHookIds(String eventName, Class<?> hookClass,
            HookEventType<?> eventType, HookContext context) {
        final DeclarativeShellHookBinding<?> binding = DeclarativeShellHookBinding.forEvent(eventName).orElseThrow();

        final ExecutionHook<?> first = binding.create(SKILL, ACTION, RecordingExecutor.ok(),
                DeclarativeHookOptions.ofDiscriminator("handlers[0][0]"));
        final ExecutionHook<?> second = binding.create(SKILL, ACTION, RecordingExecutor.ok(),
                DeclarativeHookOptions.ofDiscriminator("handlers[0][1]"));

        assertThat(first.getHookId()).isNotEqualTo(second.getHookId());
    }

    @Test
    void forEvent_toolInputPairAndUnknownNames_areAbsent() {
        assertThat(DeclarativeShellHookBinding.forEvent(DeclarativePreToolHook.EVENT_NAME)).isEmpty();
        assertThat(DeclarativeShellHookBinding.forEvent(DeclarativePostToolHook.EVENT_NAME)).isEmpty();
        assertThat(DeclarativeShellHookBinding.forEvent("onStarted")).isEmpty();
        assertThat(DeclarativeShellHookBinding.forEvent("ONSTART")).isEmpty();
    }

    @Test
    void forEvent_null_throwsDespiteTheJavadocSayingItMayBeNull() {
        // PINNED, not endorsed: the javadoc documents eventName as "(may be null)", but the backing Map.ofEntries map
        // throws on a null key rather than returning empty. Both sides are defensible — this test just makes sure
        // whichever one is corrected is corrected deliberately.
        assertThatThrownBy(() -> DeclarativeShellHookBinding.forEvent(null)).isInstanceOf(NullPointerException.class);
    }

    // --- key set vs the two front-ends ---------------------------------------------------------------------------

    @Test
    void eventNames_coverEveryEventTypeExceptTheToolInputPair() {
        final Set<String> expected = HookEventType.values().stream().map(HookEventType::name)
                .filter(name -> !TOOL_INPUT_PAIR.contains(name)).collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(DeclarativeShellHookBinding.eventNames()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void eventNames_containEverySkillDeclarableShellOnlyEvent() {
        // SkillHookSetParser derives its accepted-event set from SkillHookSet.supportedEvents() and routes everything
        // outside the tool-input pair through this table, so a skill-declarable event missing here would throw
        // "Unhandled event" at skill-load time.
        final Set<String> shellOnlySkillEvents = skillDeclarableEventNames().stream()
                .filter(name -> !TOOL_INPUT_PAIR.contains(name)).collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(DeclarativeShellHookBinding.eventNames()).containsAll(shellOnlySkillEvents);
    }

    @Test
    void eventNames_beyondTheSkillDeclarableSet_areExactlyTheSessionAndConfigLifecycleEvents() {
        // The two front-ends deliberately do NOT have the same key set: session- and config-lifecycle events fire
        // outside any skill invocation, so they are hooks.json-only. Pinning the difference keeps that gap a decision
        // rather than an accident.
        final Set<String> skillDeclarable = skillDeclarableEventNames();

        final Set<String> hooksJsonOnly = DeclarativeShellHookBinding.eventNames().stream()
                .filter(name -> !skillDeclarable.contains(name)).collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(hooksJsonOnly).containsExactlyInAnyOrder(DeclarativeOnSessionStartHook.EVENT_NAME,
                DeclarativeOnSessionEndHook.EVENT_NAME, DeclarativeOnConfigReloadHook.EVENT_NAME);
    }

    // --- asyncRewake wiring --------------------------------------------------------------------------------------

    @Test
    void create_delayRewakeSpec_isAttachedToTheResultWithTriggerTimeoutAndMaxAttemptsIntact() {
        final RewakeTriggerDelay trigger = new RewakeTriggerDelay(Duration.ofMinutes(5));
        final RewakeSpec spec = RewakeSpec.builder().trigger(trigger).timeout(Duration.ofMinutes(30)).maxAttempts(4)
                .reason("waiting for the deploy to settle").build();
        final ExecutionHook<?> hook = DeclarativeShellHookBinding.forEvent(DeclarativeOnSessionStartHook.EVENT_NAME)
                .orElseThrow().create(SKILL, ACTION, RecordingExecutor.ok(),
                        DeclarativeHookOptions.builder().hookIdDiscriminator(DISCRIMINATOR).rewakeSpec(spec).build());

        final HookResult result = execute(hook, onSessionStartContext());

        assertThat(result.getRewakeSpecs()).hasSize(1);
        final RewakeSpec attached = result.getRewakeSpecs().get(0);
        assertThat(attached.getTrigger()).isEqualTo(trigger);
        assertThat(attached.getTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(attached.getMaxAttempts()).isEqualTo(4);
    }

    @Test
    void create_withoutRewakeSpec_resultCarriesNone() {
        final ExecutionHook<?> hook = DeclarativeShellHookBinding.forEvent(DeclarativeOnSessionStartHook.EVENT_NAME)
                .orElseThrow().create(SKILL, ACTION, RecordingExecutor.ok(), DeclarativeHookOptions.none());

        final HookResult result = execute(hook, onSessionStartContext());

        assertThat(result.getRewakeSpecs()).isEmpty();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private static Set<String> skillDeclarableEventNames() {
        return SkillHookSet.supportedEvents().stream().map(HookEventType::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Fires a hook obtained from the wildcard-typed table.
     *
     * <p>
     * The cast is safe by construction: each row pairs its context factory with the hook class that consumes it, and
     * the parameterized rows keep the two together.
     */
    @SuppressWarnings("unchecked")
    private static HookResult execute(ExecutionHook<?> hook, HookContext context) {
        return ((ExecutionHook<HookContext>) hook).execute(context);
    }

    // --- context fixtures ----------------------------------------------------------------------------------------

    private static OnStartContext onStartContext() {
        return OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).userMessage("deploy please").build();
    }

    private static OnStopContext onStopContext() {
        final Instant now = Instant.now();
        final ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(3).duration(Duration.ofMillis(50))
                .startTime(now.minusMillis(50)).endTime(now).build();
        return OnStopContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).success(true).finalAnswer("done").metadata(metadata).build();
    }

    private static OnSessionStartContext onSessionStartContext() {
        return OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).sessionId(SessionId.generate())
                .agentRuntimeId("agent:default-agent").build();
    }

    private static OnSessionEndContext onSessionEndContext() {
        return OnSessionEndContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).sessionId(SessionId.generate())
                .agentRuntimeId("agent:default-agent").clean(true).build();
    }

    private static SubagentStartContext subagentStartContext() {
        return SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).subagentName("Explore").taskId("t-1")
                .goal("map the module graph").description("read-only exploration").build();
    }

    private static SubagentStopContext subagentStopContext() {
        return SubagentStopContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).subagentName("Explore").taskId("t-1").success(true).build();
    }

    private static PreCompactContext preCompactContext() {
        return PreCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).trigger(CompactionTrigger.AUTO).sessionIdValue("conv-1")
                .messageCount(42).estimatedTokens(120_000).build();
    }

    private static PostCompactContext postCompactContext() {
        final Instant now = Instant.now();
        final CompactionMetadata metadata = CompactionMetadata.builder().trigger(CompactionTrigger.AUTO).startedAt(now)
                .completedAt(now).build();
        return PostCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).trigger(CompactionTrigger.AUTO).compactionMetadata(metadata)
                .compactSummary("summary").transcriptBuffer(new TranscriptBuffer(SessionId.generate())).build();
    }

    private static PermissionRequestContext permissionRequestContext() {
        return PermissionRequestContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).toolName("Bash")
                .toolInput(ToolInput.of(Map.of("command", "ls"))).build();
    }

    private static PermissionDeniedContext permissionDeniedContext() {
        return PermissionDeniedContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).toolName("Bash")
                .toolInput(ToolInput.of(Map.of("command", "rm -rf /"))).denyReason("policy").build();
    }

    private static OnConfigReloadContext onConfigReloadContext() {
        return OnConfigReloadContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("config-watcher")
                .hookRegistry(REGISTRY).environment(ENV).reloadCounter(1L).configSource("/etc/aimon/hooks.json")
                .successful(true).build();
    }

    /**
     * Executor stub that records the environment each hook exports and always reports a clean exit.
     *
     * <p>
     * It overrides the three-argument {@code run} on purpose: the default implementation of that overload returns
     * {@link ShellHookOutcome#notObserved()}, which would make every outcome-sensitive assertion vacuous.
     */
    private static final class RecordingExecutor implements ShellActionExecutor {

        private final List<Map<String, String>> envs = new ArrayList<>();

        static RecordingExecutor ok() {
            return new RecordingExecutor();
        }

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
            envs.add(new LinkedHashMap<>(environmentOverrides));
            return ShellHookOutcome.of(0, "", "");
        }
    }
}
