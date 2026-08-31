package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.HookExecutionPolicy;
import at.aimon.core.hook.execution.HookExecutor;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.RewakeEnvelope;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.llm.ToolUse;

/**
 * Phase 4A WI-4A.6 — verifies that {@link DefaultHookExecutionManager} hands every {@link RewakeSpec} returned by a
 * hook to the wired {@link RewakeService}, materialising envelopes with the correct event type / hook id / tool
 * context.
 */
class DefaultHookExecutionManagerRewakeTest {

    private static final RewakeSpec DELAY_SPEC = RewakeSpec.builder()
            .trigger(new RewakeTriggerDelay(Duration.ofMinutes(5))).reason("await-quota").payload("k", "v").build();

    @Test
    void preToolHookRewakeIsScheduledWithToolMetadata() {
        final RewakeService service = mock(RewakeService.class);
        final PreToolHook hook = stubPreToolHook("custom-hook-id");
        final HookExecutor executor = executorReturning(List.of(HookResult.builder().rewakeSpec(DELAY_SPEC).build()));

        final HookExecutionManager manager = newManager(executor, service);
        final PreToolContext ctx = stubPreToolContext(List.of(hook), "agent-x");

        final List<HookResult> results = manager.executePreTool(ctx);

        assertThat(results).hasSize(1);
        final org.mockito.ArgumentCaptor<RewakeEnvelope> captor = org.mockito.ArgumentCaptor
                .forClass(RewakeEnvelope.class);
        verify(service).schedule(captor.capture());
        final RewakeEnvelope env = captor.getValue();

        assertThat(env.getOriginatingHookId()).isEqualTo("custom-hook-id");
        assertThat(env.getOriginalEventType()).isEqualTo(HookEventType.PRE_TOOL);
        assertThat(env.getAgentRuntimeId().agentName()).isEqualTo("agent-x");
        assertThat(env.getOriginalToolName()).contains("Bash");
        assertThat(env.getOriginalToolInput()).isPresent();
        assertThat(env.getReason()).isEqualTo("await-quota");
        assertThat(env.getPayload()).containsEntry("k", "v");
        assertThat(env.getAttemptNumber()).isEqualTo(1);
    }

    @Test
    void multipleRewakeSpecsScheduledIndividually() {
        final RewakeService service = mock(RewakeService.class);
        final RewakeSpec second = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(10)))
                .reason("backup").build();
        final HookResult result = HookResult.builder().rewakeSpec(DELAY_SPEC).rewakeSpec(second).build();

        final HookExecutionManager manager = newManager(executorReturning(List.of(result)), service);
        manager.executePreTool(stubPreToolContext(List.of(stubPreToolHook("h")), "agent-x"));

        verify(service, org.mockito.Mockito.times(2)).schedule(any());
    }

    @Test
    void resultsWithoutRewakeSpecsDoNotInvokeService() {
        final RewakeService service = mock(RewakeService.class);
        final HookExecutionManager manager = newManager(executorReturning(List.of(HookResult.allow())), service);

        manager.executePreTool(stubPreToolContext(List.of(stubPreToolHook("h")), "agent-x"));

        verifyNoInteractions(service);
    }

    @Test
    void noopServiceLogsButDoesNotThrow() {
        // NOOP is the documented bootstrap default; it must accept envelopes silently (it logs a WARN internally).
        final HookResult result = HookResult.builder().rewakeSpec(DELAY_SPEC).build();
        final HookExecutionManager manager = newManager(executorReturning(List.of(result)), RewakeService.NOOP);

        // Must complete without throwing — the manager short-circuits before constructing envelopes.
        manager.executePreTool(stubPreToolContext(List.of(stubPreToolHook("h")), "agent-x"));
    }

    @Test
    void schedulingExceptionDoesNotPropagate() {
        final RewakeService service = mock(RewakeService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("scheduler-down")).when(service).schedule(any());
        final HookResult result = HookResult.builder().rewakeSpec(DELAY_SPEC).build();
        final HookExecutionManager manager = newManager(executorReturning(List.of(result)), service);

        // Must complete normally even though the service throws.
        final List<HookResult> results = manager
                .executePreTool(stubPreToolContext(List.of(stubPreToolHook("h")), "agent-x"));
        assertThat(results).hasSize(1);
    }

    @Test
    void lifecycleHookRewakeHasNoToolMetadata() {
        final RewakeService service = mock(RewakeService.class);
        final OnStartHook hook = stubOnStartHook("startup-hook");
        final HookResult result = HookResult.builder().rewakeSpec(DELAY_SPEC).build();
        final HookExecutor executor = executorReturning(List.of(result));
        final HookExecutionManager manager = newManager(executor, service);

        final OnStartContext ctx = mock(OnStartContext.class);
        final HookRegistry registry = mock(HookRegistry.class);
        when(ctx.getHookRegistry()).thenReturn(registry);
        when(ctx.getInvokerName()).thenReturn("agent-x");
        when(registry.getHooks(HookEventType.ON_START)).thenReturn(List.of(hook));

        manager.executeOnStart(ctx);

        final org.mockito.ArgumentCaptor<RewakeEnvelope> captor = org.mockito.ArgumentCaptor
                .forClass(RewakeEnvelope.class);
        verify(service).schedule(captor.capture());
        final RewakeEnvelope env = captor.getValue();
        assertThat(env.getOriginalEventType()).isEqualTo(HookEventType.ON_START);
        assertThat(env.getOriginatingHookId()).isEqualTo("startup-hook");
        assertThat(env.getOriginalToolName()).isEmpty();
        assertThat(env.getOriginalToolInput()).isEmpty();
    }

    @Test
    void rewakeSpecsArePreservedThroughAskPromotion() {
        final RewakeService service = mock(RewakeService.class);
        final HookResult ask = HookResult.builder().decision(at.aimon.core.hook.execution.Decision.ASK).feedback("ok?")
                .rewakeSpec(DELAY_SPEC).build();
        final HookExecutionManager manager = DefaultHookExecutionManager.builder()
                .executor(executorReturning(List.of(ask))).askPromptHandler(AskPromptHandler.allowAll()).build()
                .withRewakeService(service);

        final List<HookResult> results = manager
                .executePreTool(stubPreToolContext(List.of(stubPreToolHook("h")), "agent-x"));

        // The promoted result must keep its rewakeSpec so downstream consumers (audit, merge) still see it.
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRewakeSpecs()).containsExactly(DELAY_SPEC);
        // And the spec was scheduled exactly once at dispatch time, not duplicated by promotion.
        verify(service, org.mockito.Mockito.times(1)).schedule(any());
    }

    /**
     * Regression guard: dedup can drop a hook from the <i>middle</i> of the registry list, so the executor's result
     * list is parallel-by-index with the post-dedup list only. Pairing results against the registry list stamped the
     * envelope with the wrong {@code originatingHookId}, and {@code DefaultRewakeFireListener} then re-dispatched a
     * different hook (or dropped the fire entirely).
     */
    @Test
    void rewakeEnvelopeIsAttributedToTheHookThatEmittedItWhenDedupDropsAMiddleHook() {
        final RewakeService service = mock(RewakeService.class);
        // "first" and "second" share a dedup key, so "second" — the middle element — is dropped by the executor.
        final HookExecutionPolicy dedupPolicy = HookExecutionPolicy.continueOnExceptionButStopOnBlocked()
                .withDedupKeyExtractor(hook -> "second".equals(hook.getHookId()) ? "dup" : hook.getHookId());
        final PreToolHook first = stubPreToolHook("dup", HookResult.allow());
        final PreToolHook second = stubPreToolHook("second", HookResult.allow());
        final PreToolHook last = stubPreToolHook("last", HookResult.builder().rewakeSpec(DELAY_SPEC).build());

        final HookExecutionManager manager = DefaultHookExecutionManager.builder().preToolPolicy(dedupPolicy)
                .askPromptHandler(AskPromptHandler.allowAll()).build().withRewakeService(service);

        manager.executePreTool(stubPreToolContext(List.of(first, second, last), "agent-x"));

        final org.mockito.ArgumentCaptor<RewakeEnvelope> captor = org.mockito.ArgumentCaptor
                .forClass(RewakeEnvelope.class);
        verify(service).schedule(captor.capture());
        // Before the fix this was "second" — the hook sitting at the same index in the *registry* list.
        assertThat(captor.getValue().getOriginatingHookId()).isEqualTo("last");
    }

    private static HookExecutionManager newManager(HookExecutor executor, RewakeService service) {
        return DefaultHookExecutionManager.builder().executor(executor).askPromptHandler(AskPromptHandler.allowAll())
                .build().withRewakeService(service);
    }

    private static HookExecutor executorReturning(List<HookResult> results) {
        final HookExecutor executor = mock(HookExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(results);
        return executor;
    }

    private static PreToolHook stubPreToolHook(String hookId) {
        return stubPreToolHook(hookId, HookResult.allow());
    }

    private static PreToolHook stubPreToolHook(String hookId, HookResult result) {
        return new PreToolHook() {
            @Override
            public HookResult execute(PreToolContext context) {
                return result;
            }

            @Override
            public String getHookId() {
                return hookId;
            }
        };
    }

    private static OnStartHook stubOnStartHook(String hookId) {
        return new OnStartHook() {
            @Override
            public HookResult execute(OnStartContext context) {
                return HookResult.allow();
            }

            @Override
            public String getHookId() {
                return hookId;
            }
        };
    }

    private static PreToolContext stubPreToolContext(List<PreToolHook> hooks, String invokerName) {
        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_TOOL)).thenReturn(hooks);
        final PreToolContext context = mock(PreToolContext.class);
        when(context.getHookRegistry()).thenReturn(registry);
        when(context.getInvokerName()).thenReturn(invokerName);
        final ToolUse use = ToolUse.of("use-1", "Bash", Map.of("command", "ls"));
        when(context.getCurrentToolUse()).thenReturn(use);
        // currentInput() is not used by the rewake path but kept consistent for safety.
        when(context.currentInput()).thenReturn(ToolInput.of(use.getInput()));
        return context;
    }
}
