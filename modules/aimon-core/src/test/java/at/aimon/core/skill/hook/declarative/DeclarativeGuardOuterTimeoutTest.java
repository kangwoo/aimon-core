package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.PermissionRequestContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookExecutionPolicy;
import at.aimon.core.hook.execution.HookExecutionPolicy.TimeoutBehavior;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.mcp.McpClient;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.action.McpToolAction;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

/**
 * A declarative guard whose action does not honour its own timeout must still block when the hook executor's outer
 * net cuts it off (EE-64).
 *
 * <p>
 * The shell here ignores both its {@link ExecutionOptions#getTimeout() timeout} and thread interrupts — a remote
 * shell that does not implement cancellation, a stuck read. The hook's own deadline therefore never reports
 * {@code TIMEOUT}, and what ends the wait is {@code DefaultHookExecutor}'s net. Every policy the manager ships maps
 * that to {@link TimeoutBehavior#FAIL_OPEN}; these tests run the real manager with those policies, only shortened.
 */
@DisplayName("declarative guards and the hook executor's outer timeout (EE-64)")
class DeclarativeGuardOuterTimeoutTest {

    private static final Duration NET = Duration.ofMillis(250);
    // Shorter than the net, so the policy timeout is the net as it is (no +5s grace) and the test stays fast.
    private static final ShellAction GUARD = new ShellAction("guard.sh", Duration.ofMillis(50));

    private final CountDownLatch release = new CountDownLatch(1);
    private final HookRegistry registry = new DefaultHookRegistry();
    private final DefaultHookExecutionManager manager = DefaultHookExecutionManager.builder()
            .onStartPolicy(HookExecutionPolicy.continueOnExceptionAndNeverStop().withTimeout(NET))
            .preToolPolicy(HookExecutionPolicy.continueOnExceptionButStopOnBlocked().withTimeout(NET))
            .preCompactAutoPolicy(HookExecutionPolicy.continueOnExceptionButStopOnBlocked().withTimeout(NET))
            .preCompactManualPolicy(HookExecutionPolicy.continueOnExceptionAndNeverStop().withTimeout(NET)).build();

    @AfterEach
    void releaseHungActions() throws Exception {
        release.countDown();
        manager.close();
    }

    @Test
    void preTool_shellGuardThatOutlivesTheNet_blocks() throws Exception {
        registry.register(HookEventType.PRE_TOOL,
                new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, GUARD, hangingShellExecutor()));

        final HookResult result = HookResult.merge(manager.executePreTool(preToolContext()));

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("timed out");
    }

    @Test
    void preTool_shellGuardThatOutlivesTheNet_failOpen_followsTheEventPolicy() throws Exception {
        registry.register(HookEventType.PRE_TOOL, new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, GUARD,
                hangingShellExecutor(), null, null, Map.of(), DeclarativeHookOptions.builder().failOpen(true).build()));

        assertThat(HookResult.merge(manager.executePreTool(preToolContext())).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void preTool_mcpGuardWhoseCallNeverReturns_blocks() {
        final McpClient client = mock(McpClient.class);
        when(client.isConnected()).thenReturn(true);
        when(client.callTool(any(), any())).thenAnswer(invocation -> {
            awaitReleaseIgnoringInterrupts();
            return null;
        });
        final McpClientManager servers = mock(McpClientManager.class);
        when(servers.getClient("policy")).thenReturn(Optional.of(client));
        // This client ignores the interrupt McpActionExecutor's deadline sends, so the net is what ends the call.
        registry.register(HookEventType.PRE_TOOL,
                new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY,
                        McpToolAction.builder().serverName("policy").toolName("evaluate").timeout(Duration.ofMillis(50))
                                .build(),
                        NoOpShellActionExecutor.INSTANCE, null, new McpActionExecutor(servers, new ObjectMapper()),
                        Map.of(), DeclarativeHookOptions.none()));

        assertThat(HookResult.merge(manager.executePreTool(preToolContext())).getStatus())
                .isEqualTo(HookStatus.BLOCKED);
    }

    @Test
    void onStart_shellGuardThatOutlivesTheNet_blocks() throws Exception {
        registry.register(HookEventType.ON_START, new DeclarativeOnStartHook("ops", GUARD, hangingShellExecutor()));

        assertThat(HookResult.merge(manager.executeOnStart(onStartContext())).getStatus())
                .isEqualTo(HookStatus.BLOCKED);
    }

    @Test
    void preCompact_shellGuardThatOutlivesTheNet_blocks_underBothTriggers() throws Exception {
        registry.register(HookEventType.PRE_COMPACT,
                new DeclarativePreCompactHook("ops", GUARD, hangingShellExecutor()));

        for (CompactionTrigger trigger : CompactionTrigger.values()) {
            assertThat(HookResult.merge(manager.executePreCompact(preCompactContext(trigger))).getStatus())
                    .as(trigger.name()).isEqualTo(HookStatus.BLOCKED);
        }
    }

    @Test
    void permissionRequest_shellGuardThatOutlivesTheNet_denies() throws Exception {
        registry.register(HookEventType.PERMISSION_REQUEST,
                new DeclarativePermissionRequestHook("ops", GUARD, hangingShellExecutor()));

        final HookResult result = HookResult.merge(manager.executePermissionRequest(permissionRequestContext()));

        assertThat(result.isBlocked()).isTrue();
    }

    @Test
    void advisoryEvent_shellHookThatOutlivesTheNet_stillProceeds() throws Exception {
        final DeclarativeOnStopHook hook = new DeclarativeOnStopHook("ops", GUARD, hangingShellExecutor());

        // Nothing to decide on an advisory event, so the hook declares nothing and the policy's FAIL_OPEN stands.
        assertThat(hook.getTimeoutBehavior()).isEmpty();
    }

    @Test
    void programmaticHooks_keepTheEventPolicy() {
        final PreToolHook slowPreTool = context -> {
            awaitReleaseIgnoringInterrupts();
            return HookResult.block("too late");
        };
        final OnStartHook slowOnStart = context -> {
            awaitReleaseIgnoringInterrupts();
            return HookResult.block("too late");
        };
        registry.register(HookEventType.PRE_TOOL, slowPreTool);
        registry.register(HookEventType.ON_START, slowOnStart);

        // Not changed by EE-64: a programmatically registered hook that outlives the net is read by the event
        // policy, and the shipped policies say FAIL_OPEN.
        assertThat(HookResult.merge(manager.executePreTool(preToolContext())).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
        assertThat(HookResult.merge(manager.executeOnStart(onStartContext())).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void programmaticOnStartHookThatThrows_isStillReadAsSuccess() {
        final OnStartHook throwing = context -> {
            throw new IllegalStateException("policy service down");
        };
        registry.register(HookEventType.ON_START, throwing);

        // The other half of the gap the backlog item names, reproduced and deliberately left: the onStart policy
        // maps an exception to success, and a declarative hook cannot reach this path (it catches and reports
        // EXECUTION_FAILED itself).
        assertThat(HookResult.merge(manager.executeOnStart(onStartContext())).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void programmaticHookThatDeclaresFailClosed_blocksUnderTheSamePolicy() {
        registry.register(HookEventType.PRE_TOOL, new PreToolHook() {
            @Override
            public HookResult execute(PreToolContext context) {
                awaitReleaseIgnoringInterrupts();
                return HookResult.success();
            }

            @Override
            public Optional<TimeoutBehavior> getTimeoutBehavior() {
                return Optional.of(TimeoutBehavior.FAIL_CLOSED);
            }
        });

        assertThat(HookResult.merge(manager.executePreTool(preToolContext())).getStatus())
                .isEqualTo(HookStatus.BLOCKED);
    }

    @Test
    void declaredBehaviour_isFailClosedOnGuardEventsAndAbsentOtherwise() throws Exception {
        final ShellActionExecutor shell = hangingShellExecutor();
        final DeclarativeHookOptions failOpen = DeclarativeHookOptions.builder().failOpen(true).build();

        assertThat(new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, GUARD, shell).getTimeoutBehavior())
                .contains(TimeoutBehavior.FAIL_CLOSED);
        assertThat(new DeclarativeOnStartHook("ops", GUARD, shell).getTimeoutBehavior())
                .contains(TimeoutBehavior.FAIL_CLOSED);
        assertThat(new DeclarativePreCompactHook("ops", GUARD, shell).getTimeoutBehavior())
                .contains(TimeoutBehavior.FAIL_CLOSED);
        assertThat(new DeclarativePermissionRequestHook("ops", GUARD, shell).getTimeoutBehavior())
                .contains(TimeoutBehavior.FAIL_CLOSED);
        // failOpen is "this hook is not a guard": it declares nothing, so a host that configured a stricter event
        // policy is not loosened by a config key.
        assertThat(
                new DeclarativePreToolHook("ops", NameOnlyPredicate.ANY, GUARD, shell, null, null, Map.of(), failOpen)
                        .getTimeoutBehavior())
                .isEmpty();
        assertThat(new DeclarativeOnStartHook("ops", GUARD, shell, failOpen).getTimeoutBehavior()).isEmpty();
        assertThat(new DeclarativePostToolHook("ops", NameOnlyPredicate.ANY, GUARD, shell).getTimeoutBehavior())
                .isEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private ShellActionExecutor hangingShellExecutor() throws Exception {
        final VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenAnswer(invocation -> {
            awaitReleaseIgnoringInterrupts();
            return null;
        });
        return new HostShellActionExecutor(shell);
    }

    private void awaitReleaseIgnoringInterrupts() {
        boolean interrupted = false;
        while (true) {
            try {
                if (release.await(30, TimeUnit.SECONDS)) {
                    break;
                }
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private PreToolContext preToolContext() {
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .toolUse(ToolUse.of("call-1", "Bash", Map.of())).iterationCount(1).build();
    }

    private OnStartContext onStartContext() {
        return OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .userMessage("deploy please").build();
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
