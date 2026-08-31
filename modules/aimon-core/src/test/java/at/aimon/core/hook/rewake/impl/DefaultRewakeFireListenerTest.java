package at.aimon.core.hook.rewake.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.event.OnConfigReloadHook;
import at.aimon.core.hook.event.OnSessionEndContext;
import at.aimon.core.hook.event.OnSessionEndHook;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnSessionStartHook;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreCompactHook;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.RewakeCapableRuntime;
import at.aimon.core.hook.rewake.RewakeEnvelope;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.hook.rewake.RewakeTriggerCron;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;

/**
 * Phase 4A WI-4A.7 — verifies that {@link DefaultRewakeFireListener} re-dispatches the originating hook on a
 * scheduled fire and silently drops fires whose context / hook has gone away.
 */
class DefaultRewakeFireListenerTest {

    private static final AgentRuntimeId CTX_ID = AgentRuntimeId.fromName("agent-x");

    @Test
    void preToolFireDispatchesToOriginatingHook() {
        final AtomicReference<PreToolContext> seen = new AtomicReference<>();
        final PreToolHook hook = stubPreToolHook("approval-hook", ctx -> {
            seen.set(ctx);
            return HookResult.allow();
        });

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_TOOL)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        listener.onFire(envelope("approval-hook", "Bash", ToolInput.of("command", "ls -la")));

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getCurrentToolUse().getName()).isEqualTo("Bash");
        assertThat(seen.get().getCurrentToolUse().getInput()).containsEntry("command", "ls -la");
        assertThat(seen.get().getInvokerName()).isEqualTo("agent-x");
        assertThat(seen.get().getHookRegistry()).isSameAs(registry);
    }

    @Test
    void missingContextIsDropped() {
        final PreToolHook hook = mock(PreToolHook.class);
        final AgentRuntimeRegistry agentRuntimeRegistry = mock(AgentRuntimeRegistry.class);
        when(agentRuntimeRegistry.get(any())).thenReturn(Optional.empty());

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        listener.onFire(envelope("h", "Bash", ToolInput.of("command", "ls")));

        verify(hook, never()).execute(any());
    }

    @Test
    void contextWithoutRewakeCapableSupportIsDropped() {
        // Plain AgentRuntime — no RewakeCapableRuntime implementation. Listener must not throw.
        final AgentRuntime plain = mock(AgentRuntime.class);
        final AgentRuntimeRegistry agentRuntimeRegistry = mock(AgentRuntimeRegistry.class);
        when(agentRuntimeRegistry.get(CTX_ID)).thenReturn(Optional.of(plain));

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        listener.onFire(envelope("h", "Bash", ToolInput.of("command", "ls")));
        // No exception means success — the drop path is structurally safe.
    }

    @Test
    void unsupportedLifecycleEventIsDroppedWithoutLookup() {
        final AgentRuntimeRegistry agentRuntimeRegistry = mock(AgentRuntimeRegistry.class);
        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);

        final RewakeEnvelope env = RewakeEnvelope.builder().envelopeId("env-1").agentRuntimeId(CTX_ID)
                .trigger(new RewakeTriggerDelay(Duration.ofMinutes(5))).originalEventType(HookEventType.ON_START)
                .originatingHookId("startup").firstScheduledAt(Instant.now()).reason("noop").build();

        listener.onFire(env);

        verify(agentRuntimeRegistry, never()).get(any());
    }

    @Test
    void postToolEventIsDroppedWithoutLookup() {
        final AgentRuntimeRegistry agentRuntimeRegistry = mock(AgentRuntimeRegistry.class);
        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);

        final RewakeEnvelope env = RewakeEnvelope.builder().envelopeId("env-1").agentRuntimeId(CTX_ID)
                .trigger(new RewakeTriggerDelay(Duration.ofMinutes(5))).originalEventType(HookEventType.POST_TOOL)
                .originatingHookId("audit").firstScheduledAt(Instant.now()).reason("retry-audit").build();

        listener.onFire(env);

        verify(agentRuntimeRegistry, never()).get(any());
    }

    @Test
    void postCompactEventIsDroppedWithoutLookup() {
        // POST_COMPACT requires heavyweight runtime state (TranscriptBuffer, CompactionMetadata) that the
        // envelope does not capture, so the listener drops it before any registry lookup.
        final AgentRuntimeRegistry agentRuntimeRegistry = mock(AgentRuntimeRegistry.class);
        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);

        final RewakeEnvelope env = RewakeEnvelope.builder().envelopeId("env-1").agentRuntimeId(CTX_ID)
                .trigger(new RewakeTriggerDelay(Duration.ofMinutes(5))).originalEventType(HookEventType.POST_COMPACT)
                .originatingHookId("post-compact-cleanup").firstScheduledAt(Instant.now()).reason("retry").build();

        listener.onFire(env);

        verify(agentRuntimeRegistry, never()).get(any());
    }

    @Test
    void onConfigReloadFireDispatchesToOriginatingHook() {
        final AtomicReference<OnConfigReloadContext> seen = new AtomicReference<>();
        final OnConfigReloadHook hook = new OnConfigReloadHook() {
            @Override
            public HookResult execute(OnConfigReloadContext context) {
                seen.set(context);
                return HookResult.success();
            }

            @Override
            public String getHookId() {
                return "reload-hook";
            }
        };

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.ON_CONFIG_RELOAD)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        final RewakeEnvelope env = RewakeEnvelope.builder().envelopeId("env-1").agentRuntimeId(CTX_ID)
                .trigger(new RewakeTriggerDelay(Duration.ofMinutes(5)))
                .originalEventType(HookEventType.ON_CONFIG_RELOAD).originatingHookId("reload-hook")
                .firstScheduledAt(Instant.now()).reason("retry-reload").build();

        listener.onFire(env);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getInvokerName()).isEqualTo("agent-x");
        assertThat(seen.get().getHookRegistry()).isSameAs(registry);
        assertThat(seen.get().getConfigSource()).isEqualTo("rewake");
    }

    @Test
    void preCompactFireDispatchesToOriginatingHook() {
        final AtomicReference<PreCompactContext> seen = new AtomicReference<>();
        final PreCompactHook hook = new PreCompactHook() {
            @Override
            public HookResult execute(PreCompactContext context) {
                seen.set(context);
                return HookResult.success();
            }

            @Override
            public String getHookId() {
                return "pre-compact-hook";
            }
        };

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_COMPACT)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        final RewakeEnvelope env = RewakeEnvelope.builder().envelopeId("env-1").agentRuntimeId(CTX_ID)
                .trigger(new RewakeTriggerDelay(Duration.ofMinutes(5))).originalEventType(HookEventType.PRE_COMPACT)
                .originatingHookId("pre-compact-hook").firstScheduledAt(Instant.now()).reason("retry-compact").build();

        listener.onFire(env);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getInvokerName()).isEqualTo("agent-x");
        assertThat(seen.get().getSessionIdValue()).isEmpty();
        assertThat(seen.get().getExecutionId()).contains(ExecutionId.of("rewake:env-1:1"));
    }

    @Test
    void onSessionStartFireDispatchesToOriginatingHook() {
        final AtomicReference<OnSessionStartContext> seen = new AtomicReference<>();
        final OnSessionStartHook hook = new OnSessionStartHook() {
            @Override
            public HookResult execute(OnSessionStartContext context) {
                seen.set(context);
                return HookResult.success();
            }

            @Override
            public String getHookId() {
                return "session-start-hook";
            }
        };

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.ON_SESSION_START)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        final RewakeEnvelope env = RewakeEnvelope.builder().envelopeId("env-1").agentRuntimeId(CTX_ID)
                .trigger(new RewakeTriggerDelay(Duration.ofMinutes(5)))
                .originalEventType(HookEventType.ON_SESSION_START).originatingHookId("session-start-hook")
                .firstScheduledAt(Instant.now()).reason("retry-session").build();

        listener.onFire(env);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getSessionId()).isEmpty();
        assertThat(seen.get().getExecutionId()).contains(ExecutionId.of("rewake:env-1:1"));
        assertThat(seen.get().getAgentRuntimeId()).isEqualTo(CTX_ID.value());
    }

    @Test
    void onSessionEndFireDispatchesToOriginatingHook() {
        final AtomicReference<OnSessionEndContext> seen = new AtomicReference<>();
        final OnSessionEndHook hook = new OnSessionEndHook() {
            @Override
            public HookResult execute(OnSessionEndContext context) {
                seen.set(context);
                return HookResult.success();
            }

            @Override
            public String getHookId() {
                return "session-end-hook";
            }
        };

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.ON_SESSION_END)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        final RewakeEnvelope env = RewakeEnvelope.builder().envelopeId("env-1").agentRuntimeId(CTX_ID)
                .trigger(new RewakeTriggerDelay(Duration.ofMinutes(5))).originalEventType(HookEventType.ON_SESSION_END)
                .originatingHookId("session-end-hook").firstScheduledAt(Instant.now()).reason("retry-session-end")
                .build();

        listener.onFire(env);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().isClean()).isTrue();
        assertThat(seen.get().getSessionId()).isEmpty();
        assertThat(seen.get().getExecutionId()).contains(ExecutionId.of("rewake:env-1:1"));
    }

    /**
     * Two fires of the <em>same</em> cron envelope must not share a correlation id — the envelope id is stable across
     * every tick, so only the attempt number tells the fires apart.
     */
    @Test
    void repeatedCronFiresOfOneEnvelopeGetDistinctExecutionIds() {
        final List<ExecutionId> seen = new ArrayList<>();
        final OnSessionStartHook hook = new OnSessionStartHook() {
            @Override
            public HookResult execute(OnSessionStartContext context) {
                context.getExecutionId().ifPresent(seen::add);
                return HookResult.success();
            }

            @Override
            public String getHookId() {
                return "session-start-hook";
            }
        };

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.ON_SESSION_START)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        final RewakeEnvelope firstFire = RewakeEnvelope.builder().envelopeId("cron-env").agentRuntimeId(CTX_ID)
                .trigger(new RewakeTriggerCron("*/5 * * * *", ZoneId.of("UTC")))
                .originalEventType(HookEventType.ON_SESSION_START).originatingHookId("session-start-hook")
                .firstScheduledAt(Instant.now()).reason("cron-session").build();

        listener.onFire(firstFire);
        // The service advances the attempt counter after each cron fire; the envelope id stays the same.
        listener.onFire(firstFire.withIncrementedAttempt());

        assertThat(seen).containsExactly(ExecutionId.of("rewake:cron-env:1"), ExecutionId.of("rewake:cron-env:2"));
    }

    @Test
    void hookRemovedFromRegistryIsDropped() {
        final HookRegistry registry = mock(HookRegistry.class);
        // Different hook id — the originating one was hot-removed between scheduling and fire.
        when(registry.getHooks(HookEventType.PRE_TOOL))
                .thenReturn(List.of(stubPreToolHook("other-hook", ctx -> HookResult.allow())));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        listener.onFire(envelope("approval-hook", "Bash", ToolInput.of("command", "ls")));

        // No execution happened against the live hook either — listener silently dropped.
        verify(registry, times(1)).getHooks(HookEventType.PRE_TOOL);
    }

    @Test
    void hookExceptionDoesNotPropagate() {
        final PreToolHook hook = stubPreToolHook("approval-hook", ctx -> {
            throw new RuntimeException("boom");
        });
        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_TOOL)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        // Must not throw — listener catches the hook-thrown RuntimeException.
        listener.onFire(envelope("approval-hook", "Bash", ToolInput.of("command", "ls")));
    }

    @Test
    void followUpRewakeWithoutBoundServiceIsDropped() {
        // No bindRewakeService → the listener cannot chain. Hook still re-dispatched, follow-up dropped at INFO.
        final RewakeSpec follow = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(15)))
                .reason("still-waiting").build();
        final PreToolHook hook = stubPreToolHook("approval-hook", ctx -> HookResult.asyncRewake(follow));

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_TOOL)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        listener.onFire(envelope("approval-hook", "Bash", ToolInput.of("command", "ls")));
        // Completes without throwing — without a bound service, chaining is impossible.
    }

    @Test
    void chainedRewakeSchedulesUntilMaxAttempts() {
        // Initial envelope sits at attempt=1; follow-up has maxAttempts=3 → chained should land at attempt=2.
        final RewakeSpec follow = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(15)))
                .reason("still-waiting").maxAttempts(3).build();
        final PreToolHook hook = stubPreToolHook("approval-hook", ctx -> HookResult.asyncRewake(follow));

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_TOOL)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final RewakeService service = mock(RewakeService.class);
        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        listener.bindRewakeService(service);

        listener.onFire(envelope("approval-hook", "Bash", ToolInput.of("command", "ls")));

        final ArgumentCaptor<RewakeEnvelope> captor = ArgumentCaptor.forClass(RewakeEnvelope.class);
        verify(service).schedule(captor.capture());
        final RewakeEnvelope chained = captor.getValue();
        assertThat(chained.getAttemptNumber()).isEqualTo(2);
        assertThat(chained.getOriginatingHookId()).isEqualTo("approval-hook");
        assertThat(chained.getOriginalEventType()).isEqualTo(HookEventType.PRE_TOOL);
        assertThat(chained.getOriginalToolName()).contains("Bash");
        assertThat(chained.getReason()).isEqualTo("still-waiting");
        assertThat(chained.getEnvelopeId()).isNotEqualTo("env-1");
    }

    /**
     * Regression guard: a cron envelope stays registered with the scheduler and fires again on its own, so chaining a
     * follow-up off each fire forks the series rather than extending it — every fire would schedule another natively
     * repeating envelope, doubling the live count per fire (~2^(maxAttempts-1)). Declarative hooks re-emit their
     * configured spec on every fire, so this is the ordinary path for a cron {@code asyncRewake}, not an edge case.
     */
    @Test
    void cronFollowUpIsNotChainedBecauseTheTriggerRepeatsNatively() {
        final RewakeSpec follow = RewakeSpec.builder().trigger(new RewakeTriggerCron("*/5 * * * *", ZoneId.of("UTC")))
                .timeout(Duration.ofHours(2)).reason("poll-every-five-minutes").maxAttempts(10).build();
        final PreToolHook hook = stubPreToolHook("approval-hook", ctx -> HookResult.asyncRewake(follow));

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_TOOL)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final RewakeService service = mock(RewakeService.class);
        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        listener.bindRewakeService(service);

        listener.onFire(envelope("approval-hook", "Bash", ToolInput.of("command", "ls")));

        verify(service, never()).schedule(any());
    }

    @Test
    void nonCronFollowUpsAreStillChainedWhenTheBatchAlsoCarriesACronSpec() {
        // The cron spec is skipped, but it must not suppress its neighbours in the same result.
        final RewakeSpec cron = RewakeSpec.builder().trigger(new RewakeTriggerCron("*/5 * * * *", ZoneId.of("UTC")))
                .timeout(Duration.ofHours(2)).reason("poll").maxAttempts(10).build();
        final RewakeSpec delay = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(15)))
                .reason("still-waiting").maxAttempts(3).build();
        final PreToolHook hook = stubPreToolHook("approval-hook",
                ctx -> HookResult.builder().rewakeSpec(cron).rewakeSpec(delay).build());

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_TOOL)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final RewakeService service = mock(RewakeService.class);
        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        listener.bindRewakeService(service);

        listener.onFire(envelope("approval-hook", "Bash", ToolInput.of("command", "ls")));

        final ArgumentCaptor<RewakeEnvelope> captor = ArgumentCaptor.forClass(RewakeEnvelope.class);
        verify(service).schedule(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("still-waiting");
    }

    @Test
    void chainedRewakeDroppedWhenAttemptExceedsMaxAttempts() {
        // maxAttempts=1 → previous attempt=1 means next would be 2, exceeding the cap → drop.
        final RewakeSpec follow = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(15)))
                .reason("loop-guard").maxAttempts(1).build();
        final PreToolHook hook = stubPreToolHook("approval-hook", ctx -> HookResult.asyncRewake(follow));

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_TOOL)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final RewakeService service = mock(RewakeService.class);
        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        listener.bindRewakeService(service);

        listener.onFire(envelope("approval-hook", "Bash", ToolInput.of("command", "ls")));

        verify(service, never()).schedule(any());
    }

    @Test
    void chainedRewakeContinuesEvenWhenServiceScheduleThrows() {
        // The follow-up scheduler may reject (e.g., quota); the listener swallows and moves on.
        final RewakeSpec follow = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(15)))
                .reason("still-waiting").maxAttempts(5).build();
        final PreToolHook hook = stubPreToolHook("approval-hook", ctx -> HookResult.asyncRewake(follow));

        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_TOOL)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final RewakeService service = mock(RewakeService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("scheduler down")).when(service).schedule(any());
        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        listener.bindRewakeService(service);

        // Must not propagate the schedule failure.
        listener.onFire(envelope("approval-hook", "Bash", ToolInput.of("command", "ls")));
        verify(service, times(1)).schedule(any());
    }

    @Test
    void bindingDifferentServiceTwiceThrows() {
        final AgentRuntimeRegistry registry = mock(AgentRuntimeRegistry.class);
        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(registry);
        final RewakeService first = mock(RewakeService.class);
        listener.bindRewakeService(first);
        // Same instance is allowed (idempotent).
        listener.bindRewakeService(first);
        // A different instance is rejected.
        final RewakeService second = mock(RewakeService.class);
        assertThatIllegalStateException().isThrownBy(() -> listener.bindRewakeService(second));
    }

    @Test
    void missingToolMetadataIsDropped() {
        final PreToolHook hook = mock(PreToolHook.class);
        final HookRegistry registry = mock(HookRegistry.class);
        when(registry.getHooks(HookEventType.PRE_TOOL)).thenReturn(List.of(hook));
        final AgentRuntimeRegistry agentRuntimeRegistry = registryWith(stubCapableContext(registry));

        final DefaultRewakeFireListener listener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        // Envelope with no tool name / input — pathological PRE_TOOL envelope.
        final RewakeEnvelope env = RewakeEnvelope.builder().envelopeId("env-1").agentRuntimeId(CTX_ID)
                .trigger(new RewakeTriggerDelay(Duration.ofMinutes(5))).originalEventType(HookEventType.PRE_TOOL)
                .originatingHookId("h").firstScheduledAt(Instant.now()).reason("noop").build();

        listener.onFire(env);
        verify(hook, never()).execute(any());
    }

    private static RewakeEnvelope envelope(String hookId, String toolName, ToolInput input) {
        return RewakeEnvelope.builder().envelopeId("env-1").agentRuntimeId(CTX_ID)
                .trigger(new RewakeTriggerDelay(Duration.ofMinutes(5))).originalEventType(HookEventType.PRE_TOOL)
                .originatingHookId(hookId).originalToolName(toolName).originalToolInput(input)
                .firstScheduledAt(Instant.now()).reason("await-quota").payload(Map.of("k", "v")).build();
    }

    private static AgentRuntimeRegistry registryWith(AgentRuntime ctx) {
        final AgentRuntimeRegistry registry = mock(AgentRuntimeRegistry.class);
        when(registry.get(CTX_ID)).thenReturn(Optional.of(ctx));
        return registry;
    }

    /** Stub context implementing both {@link AgentRuntime} and {@link RewakeCapableRuntime}. */
    private static AgentRuntime stubCapableContext(HookRegistry hookRegistry) {
        final Environment env = Environment.createDefault();
        return new CapableStub(hookRegistry, env);
    }

    @FunctionalInterface
    private interface PreToolHookFn {
        HookResult run(PreToolContext ctx);
    }

    private static PreToolHook stubPreToolHook(String hookId, PreToolHookFn body) {
        return new PreToolHook() {
            @Override
            public HookResult execute(PreToolContext context) {
                return body.run(context);
            }

            @Override
            public String getHookId() {
                return hookId;
            }
        };
    }

    private static final class CapableStub implements AgentRuntime, RewakeCapableRuntime {
        private final HookRegistry hookRegistry;
        private final Environment environment;

        CapableStub(HookRegistry hookRegistry, Environment environment) {
            this.hookRegistry = hookRegistry;
            this.environment = environment;
        }

        @Override
        public AgentRuntimeId getId() {
            return CTX_ID;
        }

        @Override
        public at.aimon.core.agent.Agent getAgent() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<at.aimon.core.agent.tool.Tool> getAvailableTools() {
            return java.util.List.of();
        }

        @Override
        public HookRegistry getHookRegistry() {
            return hookRegistry;
        }

        @Override
        public Environment getEnvironment() {
            return environment;
        }
    }
}
