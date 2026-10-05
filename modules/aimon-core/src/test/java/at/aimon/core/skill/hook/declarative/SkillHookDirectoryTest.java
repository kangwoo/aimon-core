package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.environment.exception.StagingException;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.skill.hook.SkillHookSet;
import at.aimon.core.skill.hook.SkillScopedHookRegistry;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

/**
 * When a shell hook is given {@code AIMON_SKILL_DIR}, what the value is, and what happens when there is none to give
 * (EE-50).
 *
 * <p>
 * The hooks are fired the way dispatch fires them: looked up in the registry the context carries, which is a
 * {@link SkillScopedHookRegistry} for a skill's fork and a plain registry otherwise.
 */
@DisplayName("AIMON_SKILL_DIR for declarative shell hooks")
class SkillHookDirectoryTest {

    private static final ShellAction ACTION = new ShellAction("bash \"$AIMON_SKILL_DIR/scripts/guard.sh\"",
            Duration.ofSeconds(1));
    private static final String STAGED = "/workspace/.aimon-staged/review/0123456789abcdef";

    private final RecordingExecutor executor = new RecordingExecutor(true);
    private final ExecutionEnvironment environment = mock(ExecutionEnvironment.class);

    private static StagedResource resource(String name) {
        return StagedResource.builder().sourceFileSystem(mock(VirtualFileSystem.class))
                .sourceDir(".aimon/skills/" + name).contentKey("0123456789abcdef").name(name).totalBytes(1)
                .files(List.of("SKILL.md")).build();
    }

    private DeclarativePreToolHook preToolHook(String skill, DeclarativeHookOptions options) {
        return new DeclarativePreToolHook(skill, NameOnlyPredicate.ANY, ACTION, executor, null, null, Map.of(),
                options);
    }

    private static HookRegistry layer(HookRegistry base, String skill, DeclarativePreToolHook hook,
            StagedResource resource) {
        return new SkillScopedHookRegistry(base, skill, SkillHookSet.builder().addPreTool(hook).build(), resource);
    }

    private PreToolContext preTool(HookRegistry registry, ExecutionEnvironment env) {
        return PreToolContext.builder().executorType(InvokerType.SUBAGENT).invokerName("worker").hookRegistry(registry)
                .executionEnvironment(env).toolUse(ToolUse.of("call-1", "TodoWrite", Map.of())).iterationCount(1)
                .build();
    }

    // --- when the variable is exported -----------------------------------------------------------------------

    @Test
    @DisplayName("a skill's hook gets the path the firing execution's environment staged, on both channels")
    void skillHookGetsTheStagedPath() {
        final StagedResource review = resource("review");
        final DeclarativePreToolHook hook = preToolHook("review", DeclarativeHookOptions.none());
        when(environment.stage(review)).thenReturn(STAGED);

        hook.execute(preTool(layer(new DefaultHookRegistry(), "review", hook, review), environment));

        assertThat(executor.calls).hasSize(1);
        assertThat(executor.calls.get(0).env()).containsEntry(SkillHookEnv.AIMON_SKILL_DIR, STAGED);
        // The stdin document is rendered from the same map, so a script reading JSON finds it as skill_dir.
        assertThat(executor.calls.get(0).stdinPayload()).contains("\"skill_dir\":\"" + STAGED + "\"");
    }

    @Test
    @DisplayName("a long path is exported whole — the 2000-character cap for model text would turn it into another path")
    void longPathIsNotTruncated() {
        final StagedResource review = resource("review");
        final DeclarativePreToolHook hook = preToolHook("review", DeclarativeHookOptions.none());
        final String longPath = "/" + "d".repeat(SkillHookEnv.MAX_ENV_VALUE_LENGTH + 500) + "/review";
        when(environment.stage(review)).thenReturn(longPath);

        hook.execute(preTool(layer(new DefaultHookRegistry(), "review", hook, review), environment));

        assertThat(executor.calls.get(0).env().get(SkillHookEnv.AIMON_SKILL_DIR)).isEqualTo(longPath)
                .doesNotContain("truncated");
    }

    @Test
    @DisplayName("under a nested skill's view, each hook gets its own skill's directory")
    void nestedViewsAnswerPerSkill() {
        final StagedResource outerResource = resource("outer");
        final StagedResource innerResource = resource("inner");
        final DeclarativePreToolHook outerHook = preToolHook("outer", DeclarativeHookOptions.ofDiscriminator("o"));
        final DeclarativePreToolHook innerHook = preToolHook("inner", DeclarativeHookOptions.ofDiscriminator("i"));
        final HookRegistry view = layer(layer(new DefaultHookRegistry(), "outer", outerHook, outerResource), "inner",
                innerHook, innerResource);
        when(environment.stage(outerResource)).thenReturn("/staged/outer");
        when(environment.stage(innerResource)).thenReturn("/staged/inner");

        outerHook.execute(preTool(view, environment));
        innerHook.execute(preTool(view, environment));

        assertThat(executor.calls.get(0).env()).containsEntry(SkillHookEnv.AIMON_SKILL_DIR, "/staged/outer");
        assertThat(executor.calls.get(1).env()).containsEntry(SkillHookEnv.AIMON_SKILL_DIR, "/staged/inner");
    }

    // --- when it is left out: absent, never empty ------------------------------------------------------------

    @Test
    @DisplayName("a hooks.json hook has no skill directory: the variable is absent even inside a skill's fork")
    void operatorHookGetsNoVariable() {
        // Registered with the runtime's registry under a pseudo-skill name that happens to equal the active skill's:
        // the lookup is by hook identity, so the name cannot lend it the skill's directory.
        final StagedResource review = resource("review");
        final DeclarativePreToolHook skillHook = preToolHook("review", DeclarativeHookOptions.ofDiscriminator("s"));
        final DeclarativePreToolHook operatorHook = preToolHook("review", DeclarativeHookOptions.ofDiscriminator("o"));
        final HookRegistry view = layer(new DefaultHookRegistry(), "review", skillHook, review);

        final HookResult result = operatorHook.execute(preTool(view, environment));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(executor.calls).hasSize(1);
        assertThat(executor.calls.get(0).env()).doesNotContainKey(SkillHookEnv.AIMON_SKILL_DIR);
        assertThat(executor.calls.get(0).stdinPayload()).doesNotContain("skill_dir");
        verify(environment, never()).stage(any());
    }

    @Test
    @DisplayName("a skill assembled by hand, with nothing to stage, runs its hook without the variable")
    void skillWithoutResourceGetsNoVariable() {
        final DeclarativePreToolHook hook = preToolHook("handmade", DeclarativeHookOptions.none());

        hook.execute(preTool(layer(new DefaultHookRegistry(), "handmade", hook, null), environment));

        assertThat(executor.calls.get(0).env()).doesNotContainKey(SkillHookEnv.AIMON_SKILL_DIR);
        verify(environment, never()).stage(any());
    }

    @Test
    @DisplayName("an executor that runs commands outside the context's environment is not handed a path staged into it")
    void hostExecutorGetsNoVariable() {
        final RecordingExecutor host = new RecordingExecutor(false);
        final StagedResource review = resource("review");
        final DeclarativePreToolHook hook = new DeclarativePreToolHook("review", NameOnlyPredicate.ANY, ACTION, host);

        hook.execute(preTool(layer(new DefaultHookRegistry(), "review", hook, review), environment));

        assertThat(host.calls.get(0).env()).doesNotContainKey(SkillHookEnv.AIMON_SKILL_DIR);
        verify(environment, never()).stage(any());
    }

    @Test
    @DisplayName("without an environment in the context nothing is staged, and the executor decides what that means")
    void noEnvironmentLeavesTheDecisionToTheExecutor() {
        final StagedResource review = resource("review");
        final DeclarativePreToolHook hook = preToolHook("review", DeclarativeHookOptions.none());
        final Map<String, String> env = new LinkedHashMap<>();

        final Optional<ShellHookOutcome> refused = SkillHookDirectory.export(env,
                preTool(layer(new DefaultHookRegistry(), "review", hook, review), null), hook, executor);

        assertThat(refused).isEmpty();
        assertThat(env).doesNotContainKey(SkillHookEnv.AIMON_SKILL_DIR);
    }

    // --- when staging fails: the command is not run ----------------------------------------------------------

    @Test
    @DisplayName("preTool: a skill that cannot be staged blocks the tool, with the staging layer's reason")
    void guardBlocksWhenStagingFails() {
        final StagedResource review = resource("review");
        final DeclarativePreToolHook hook = preToolHook("review", DeclarativeHookOptions.none());
        when(environment.stage(review))
                .thenThrow(new StagingException("Cannot stage 'review': 9 bytes exceed the limit of 1"));

        final HookResult result = hook
                .execute(preTool(layer(new DefaultHookRegistry(), "review", hook, review), environment));

        assertThat(executor.calls).as("the command must not run with the variable unset").isEmpty();
        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback()).hasValueSatisfying(reason -> assertThat(reason)
                .contains("guard hook 'review' (preTool)").contains("skill directory could not be staged")
                .contains("exceed the limit of 1").contains("fail-closed").doesNotContain("guard.sh"));
    }

    @Test
    @DisplayName("preTool with failOpen: the tool proceeds, and the command still does not run")
    void failOpenGuardProceedsWithoutRunning() {
        final StagedResource review = resource("review");
        final DeclarativePreToolHook hook = preToolHook("review",
                DeclarativeHookOptions.builder().failOpen(true).build());
        when(environment.stage(review)).thenThrow(new StagingException("over the limit"));

        final HookResult result = hook
                .execute(preTool(layer(new DefaultHookRegistry(), "review", hook, review), environment));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(executor.calls).isEmpty();
    }

    @Test
    @DisplayName("onStart: a skill that cannot be staged blocks the execution")
    void onStartGuardBlocksWhenStagingFails() {
        final StagedResource review = resource("review");
        final DeclarativeOnStartHook hook = new DeclarativeOnStartHook("review", ACTION, executor);
        final HookRegistry view = new SkillScopedHookRegistry(new DefaultHookRegistry(), "review",
                SkillHookSet.builder().addOnStart(hook).build(), review);
        when(environment.stage(review)).thenThrow(new StagingException("over the limit"));

        final HookResult result = hook.execute(OnStartContext.builder().executorType(InvokerType.SUBAGENT)
                .invokerName("worker").hookRegistry(view).executionEnvironment(environment).userMessage("go").build());

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback()).hasValueSatisfying(
                reason -> assertThat(reason).contains("(onStart)").contains("skill directory could not be staged"));
        assertThat(executor.calls).isEmpty();
    }

    @Test
    @DisplayName("onStop (advisory): the command is not run and the event proceeds")
    void advisoryLifecycleHookIsSkippedWhenStagingFails() {
        final StagedResource review = resource("review");
        final DeclarativeOnStopHook hook = new DeclarativeOnStopHook("review", ACTION, executor);
        final HookRegistry view = new SkillScopedHookRegistry(new DefaultHookRegistry(), "review",
                SkillHookSet.builder().addOnStop(hook).build(), review);
        when(environment.stage(review)).thenThrow(new StagingException("over the limit"));
        final ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(1).duration(Duration.ZERO)
                .startTime(java.time.Instant.EPOCH).endTime(java.time.Instant.EPOCH).build();

        final HookResult result = hook.execute(OnStopContext.builder().executorType(InvokerType.SUBAGENT)
                .invokerName("worker").hookRegistry(view).executionEnvironment(environment).success(true)
                .finalAnswer("done").metadata(metadata).build());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(executor.calls).isEmpty();
    }

    @Test
    @DisplayName("postTool (advisory): the command is not run and the tool result stands")
    void postToolHookIsSkippedWhenStagingFails() {
        final StagedResource review = resource("review");
        final DeclarativePostToolHook hook = new DeclarativePostToolHook("review", NameOnlyPredicate.ANY, ACTION,
                executor);
        final HookRegistry view = new SkillScopedHookRegistry(new DefaultHookRegistry(), "review",
                SkillHookSet.builder().addPostTool(hook).build(), review);
        when(environment.stage(review)).thenThrow(new StagingException("over the limit"));

        final HookResult result = hook.execute(
                PostToolContext.builder().executorType(InvokerType.SUBAGENT).invokerName("worker").hookRegistry(view)
                        .executionEnvironment(environment).toolUse(ToolUse.of("call-1", "Read", Map.of()))
                        .toolUseResult(ToolUseResult.success("call-1", "ok")).iterationCount(1).build());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(executor.calls).isEmpty();
    }

    @Test
    @DisplayName("an unavailable environment is reported as that, not as a staging failure")
    void unavailableEnvironmentKeepsItsCause() {
        final StagedResource review = resource("review");
        final DeclarativePreToolHook hook = preToolHook("review", DeclarativeHookOptions.none());
        when(environment.stage(review))
                .thenThrow(new ExecutionEnvironmentUnavailableException("sandbox is down", null));

        final Optional<ShellHookOutcome> refused = SkillHookDirectory.export(new LinkedHashMap<>(),
                preTool(layer(new DefaultHookRegistry(), "review", hook, review), environment), hook, executor);

        assertThat(refused).hasValueSatisfying(outcome -> {
            assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE);
            assertThat(outcome.unrunReason()).contains("sandbox is down");
        });
    }

    @Test
    @DisplayName("an unexpected failure is reported by type only — its message may carry the provider's internals")
    void unexpectedFailureHidesItsMessage() {
        final StagedResource review = resource("review");
        final DeclarativePreToolHook hook = preToolHook("review", DeclarativeHookOptions.none());
        when(environment.stage(review)).thenThrow(new IllegalStateException("token=s3cret at /host/internal"));

        final Optional<ShellHookOutcome> refused = SkillHookDirectory.export(new LinkedHashMap<>(),
                preTool(layer(new DefaultHookRegistry(), "review", hook, review), environment), hook, executor);

        assertThat(refused).hasValueSatisfying(outcome -> {
            assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.STAGING_FAILED);
            assertThat(outcome.unrunReason()).contains("IllegalStateException").doesNotContain("s3cret");
        });
    }

    @Test
    @DisplayName("an environment that answers with no path is a staging failure, not an empty variable")
    void blankPathIsRefused() {
        final StagedResource review = resource("review");
        final DeclarativePreToolHook hook = preToolHook("review", DeclarativeHookOptions.none());
        when(environment.stage(review)).thenReturn("  ");
        final Map<String, String> env = new LinkedHashMap<>();

        final Optional<ShellHookOutcome> refused = SkillHookDirectory.export(env,
                preTool(layer(new DefaultHookRegistry(), "review", hook, review), environment), hook, executor);

        assertThat(refused).hasValueSatisfying(
                outcome -> assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.STAGING_FAILED));
        assertThat(env).doesNotContainKey(SkillHookEnv.AIMON_SKILL_DIR);
    }

    /** Records what it was asked to run and reports exit 0. */
    private static final class RecordingExecutor implements ShellActionExecutor {

        private final List<Call> calls = new ArrayList<>();
        private final boolean runsInTheContextEnvironment;

        private RecordingExecutor(boolean runsInTheContextEnvironment) {
            this.runsInTheContextEnvironment = runsInTheContextEnvironment;
        }

        @Override
        public boolean isShellSupported() {
            return true;
        }

        @Override
        public boolean requiresExecutionEnvironment() {
            return runsInTheContextEnvironment;
        }

        @Override
        public ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> environmentOverrides,
                String stdinPayload) {
            calls.add(new Call(Map.copyOf(environmentOverrides), stdinPayload));
            return ShellHookOutcome.of(0, "", "");
        }

        record Call(Map<String, String> env, String stdinPayload) {
        }
    }
}
