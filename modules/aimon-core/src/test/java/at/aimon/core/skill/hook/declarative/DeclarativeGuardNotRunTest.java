package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.PermissionRequestContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.DefaultHookExecutor;
import at.aimon.core.hook.execution.HookExecutionPolicy;
import at.aimon.core.hook.execution.HookExecutionPolicy.ExecutionMode;
import at.aimon.core.hook.execution.HookExecutionPolicy.TimeoutBehavior;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;
import at.aimon.core.skill.hook.declarative.predicate.PredicateParser;

/**
 * A declarative guard that was never run, or that died outside the part of its body it guards itself, must block.
 *
 * <p>
 * Two roads led past the guard through {@code HookExecutionPolicy#onException}, which every shipped policy answers
 * with success: the pool refusing the task ({@code RejectedExecutionException} — saturated, or shut down), and an
 * exception thrown before the hook reaches the {@code try} around its action (the matcher predicate, for one). These
 * tests run the real manager with the shipped policies.
 */
@DisplayName("declarative guards that could not be run, or threw outside their action")
class DeclarativeGuardNotRunTest {

    private static final ShellAction GUARD = new ShellAction("guard.sh", Duration.ofSeconds(1));

    private final HookRegistry registry = new DefaultHookRegistry();
    private final ExecutorService closedPool = closedPool();
    private final DefaultHookExecutionManager rejecting = DefaultHookExecutionManager.builder()
            .executor(new DefaultHookExecutor(closedPool)).build();
    private final DefaultHookExecutionManager running = DefaultHookExecutionManager.builder().build();
    private final ShellActionExecutor shell = new HostShellActionExecutor(mock(VirtualShell.class));

    private static ExecutorService closedPool() {
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.shutdownNow();
        return pool;
    }

    @AfterEach
    void closeManagers() throws Exception {
        rejecting.close();
        running.close();
    }

    // --- the pool refuses the hook -----------------------------------------------------------------------------

    @Test
    void preTool_guardThePoolRefuses_blocks() {
        registry.register(HookEventType.PRE_TOOL,
                new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, GUARD, shell));

        final HookResult result = HookResult.merge(rejecting.executePreTool(preToolContext("ls")));

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("could not be run");
    }

    @Test
    void onStart_preCompact_permissionRequest_guardsThePoolRefuses_block() {
        registry.register(HookEventType.ON_START, new DeclarativeOnStartHook("ops", GUARD, shell));
        registry.register(HookEventType.PRE_COMPACT, new DeclarativePreCompactHook("ops", GUARD, shell));
        registry.register(HookEventType.PERMISSION_REQUEST, new DeclarativePermissionRequestHook("ops", GUARD, shell));

        assertThat(HookResult.merge(rejecting.executeOnStart(onStartContext())).getStatus())
                .isEqualTo(HookStatus.BLOCKED);
        for (CompactionTrigger trigger : CompactionTrigger.values()) {
            assertThat(HookResult.merge(rejecting.executePreCompact(preCompactContext(trigger))).getStatus())
                    .as(trigger.name()).isEqualTo(HookStatus.BLOCKED);
        }
        assertThat(HookResult.merge(rejecting.executePermissionRequest(permissionRequestContext())).isBlocked())
                .isTrue();
    }

    @Test
    void parallelMode_guardThePoolRefuses_blocks() {
        final DefaultHookExecutionManager parallel = DefaultHookExecutionManager.builder()
                .executor(new DefaultHookExecutor(closedPool)).preToolPolicy(HookExecutionPolicy
                        .continueOnExceptionButStopOnBlocked().withExecutionMode(ExecutionMode.PARALLEL))
                .build();
        registry.register(HookEventType.PRE_TOOL,
                new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, GUARD, shell));

        assertThat(HookResult.merge(parallel.executePreTool(preToolContext("ls"))).getStatus())
                .isEqualTo(HookStatus.BLOCKED);
    }

    @Test
    void failOpenHookThePoolRefuses_followsTheEventPolicy() {
        registry.register(HookEventType.PRE_TOOL, new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, GUARD, shell,
                null, null, Map.of(), DeclarativeHookOptions.builder().failOpen(true).build()));

        assertThat(HookResult.merge(rejecting.executePreTool(preToolContext("ls"))).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void advisoryHookThePoolRefuses_stillProceeds() {
        registry.register(HookEventType.ON_STOP, new DeclarativeOnStopHook("ops", GUARD, shell));

        assertThat(HookResult.merge(rejecting.executeOnStop(onStopContext())).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void programmaticHooksThePoolRefuses_keepTheEventPolicy() {
        final PreToolHook preTool = context -> HookResult.block("never reached");
        final OnStartHook onStart = context -> HookResult.block("never reached");
        registry.register(HookEventType.PRE_TOOL, preTool);
        registry.register(HookEventType.ON_START, onStart);

        // Unchanged: a hook registered in code that declares nothing is read by the event policy, and the shipped
        // policies map a rejection to success.
        assertThat(HookResult.merge(rejecting.executePreTool(preToolContext("ls"))).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
        assertThat(HookResult.merge(rejecting.executeOnStart(onStartContext())).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void programmaticHookThatDeclaresFailClosed_blocksWhenThePoolRefusesIt() {
        registry.register(HookEventType.PRE_TOOL, new PreToolHook() {
            @Override
            public HookResult execute(PreToolContext context) {
                return HookResult.success();
            }

            @Override
            public Optional<TimeoutBehavior> getTimeoutBehavior() {
                return Optional.of(TimeoutBehavior.FAIL_CLOSED);
            }
        });

        assertThat(HookResult.merge(rejecting.executePreTool(preToolContext("ls"))).getStatus())
                .isEqualTo(HookStatus.BLOCKED);
    }

    // --- the hook throws outside the try around its action ------------------------------------------------------

    @Test
    void preTool_guardWhoseMatcherThrows_blocks_andTheReasonCarriesOnlyTheType() {
        final ToolInputPredicate throwing = (toolName, input) -> {
            throw new IllegalStateException("secret-detail /etc/policy.d");
        };
        registry.register(HookEventType.PRE_TOOL, new DeclarativePreToolHook("ops", throwing, GUARD, shell));

        final HookResult result = HookResult.merge(running.executePreTool(preToolContext("ls")));

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("IllegalStateException").doesNotContain("secret-detail")
                .doesNotContain("failOpen");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void preTool_guardWhoseMatcherDiesWithAnError_blocks() {
        // An Error is not an Exception, and it is thrown before the hook reaches its action. The matcher that used to
        // do this on a command the model chose was the Bash sub-command splitter, one stack frame per nested $( ... );
        // it is bounded now (see the next test), so the road is driven with a matcher that throws outright.
        final ToolInputPredicate overflowing = (toolName, input) -> {
            throw new StackOverflowError();
        };
        registry.register(HookEventType.PRE_TOOL, new DeclarativePreToolHook("ops", overflowing, GUARD, shell));

        final HookResult result = HookResult.merge(running.executePreTool(preToolContext("ls")));

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("StackOverflowError");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void preTool_guardAskedAboutACommandNestedTooDeepToSplit_isRunNotKilled() throws Exception {
        // 20,000 nested $( ... ) overflowed the matcher on this stack. The matcher now declines to split it and
        // matches, so the hook gets as far as its action: what blocks here is the guard's own command failing to run
        // (the shell is a mock), which is the answer for a command that is not nested at all.
        final ExecutorService smallStacks = Executors
                .newCachedThreadPool(task -> new Thread(null, task, "hook-small-stack", 256 * 1024));
        final DefaultHookExecutionManager manager = DefaultHookExecutionManager.builder()
                .executor(new DefaultHookExecutor(smallStacks)).build();
        registry.register(HookEventType.PRE_TOOL,
                new DeclarativePreToolHook("ops", PredicateParser.parse("Bash(git push*)"), GUARD, shell));
        final String nested = "$(".repeat(20_000) + "rm -rf /" + ")".repeat(20_000);
        try {
            final HookResult result = HookResult.merge(manager.executePreTool(preToolContext(nested)));

            assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(result.getFeedback().orElseThrow()).contains("could not run its command")
                    .doesNotContain("StackOverflowError");
        } finally {
            smallStacks.shutdownNow();
        }
    }

    @Test
    void failOpenHookWhoseMatcherThrows_followsTheEventPolicy() {
        final ToolInputPredicate throwing = (toolName, input) -> {
            throw new IllegalStateException("boom");
        };
        registry.register(HookEventType.PRE_TOOL, new DeclarativePreToolHook("ops", throwing, GUARD, shell, null, null,
                Map.of(), DeclarativeHookOptions.builder().failOpen(true).build()));

        assertThat(HookResult.merge(running.executePreTool(preToolContext("ls"))).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void programmaticHookThatThrows_keepsTheEventPolicy() {
        final PreToolHook throwing = context -> {
            throw new IllegalStateException("policy service down");
        };
        registry.register(HookEventType.PRE_TOOL, throwing);

        assertThat(HookResult.merge(running.executePreTool(preToolContext("ls"))).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    // --- shutdown ------------------------------------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void afterTheManagerIsClosed_aGuardBlocksAtOnce_andAnAdvisoryHookStillProceeds() throws Exception {
        registry.register(HookEventType.PRE_TOOL,
                new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, GUARD, shell));
        registry.register(HookEventType.ON_STOP, new DeclarativeOnStopHook("ops", GUARD, shell));
        running.close();

        // Teardown closes the pool on purpose, after everything that fires a hook is gone. A tool call that still
        // arrives is answered without waiting — blocked, not run unguarded — and the lifecycle events an orderly
        // shutdown does fire (onStop, onSessionEnd) are advisory and are not turned into blocks.
        assertThat(HookResult.merge(running.executePreTool(preToolContext("ls"))).getStatus())
                .isEqualTo(HookStatus.BLOCKED);
        assertThat(HookResult.merge(running.executeOnStop(onStopContext())).getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private PreToolContext preToolContext(String command) {
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .toolUse(ToolUse.of("call-1", "Bash", Map.of("command", command))).iterationCount(1).build();
    }

    private OnStartContext onStartContext() {
        return OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .userMessage("deploy please").build();
    }

    private OnStopContext onStopContext() {
        final Instant now = Instant.now();
        return OnStopContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .success(true).finalAnswer("done").metadata(ExecutionMetadata.builder().iterationCount(1)
                        .duration(Duration.ofMillis(50)).startTime(now.minusMillis(50)).endTime(now).build())
                .build();
    }

    private PreCompactContext preCompactContext(CompactionTrigger trigger) {
        return PreCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(registry).trigger(trigger).sessionIdValue("conv-1").messageCount(42)
                .estimatedTokens(120_000).build();
    }

    private PermissionRequestContext permissionRequestContext() {
        return PermissionRequestContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(registry).toolName("Bash").toolInput(ToolInput.of(Map.of("command", "ls"))).build();
    }
}
