package at.aimon.core.hook.rewake.impl;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.event.OnSessionEndContext;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.RewakeCapableRuntime;
import at.aimon.core.hook.rewake.RewakeEnvelope;
import at.aimon.core.hook.rewake.RewakeEnvelopes;
import at.aimon.core.hook.rewake.RewakeFireListener;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.hook.rewake.RewakeTriggerCron;
import at.aimon.core.llm.ToolUse;

/**
 * Default {@link RewakeFireListener} that re-hydrates the originating
 * {@link at.aimon.core.agent.AgentRuntime AgentRuntime} and re-dispatches the originating hook when
 * a scheduled envelope fires.
 *
 * <p>
 * <b>Supported event types.</b> Per design §4 the listener honors:
 * <ul>
 * <li>{@link HookEventType#PRE_TOOL} — replays the tool admission path with the captured tool name / input.
 * <li>{@link HookEventType#ON_CONFIG_RELOAD} — replays the advisory reload notification with a degraded "rewake"
 * stand-in (counter=0, source="rewake", successful=true).
 * <li>{@link HookEventType#PRE_COMPACT} — replays the pre-compact advisory with a synthetic
 * {@link CompactionTrigger#MANUAL} trigger and zeroed counters.
 * <li>{@link HookEventType#ON_SESSION_START} / {@link HookEventType#ON_SESSION_END} — replay the lifecycle event with
 * no session id at all, carrying an {@link ExecutionId} that identifies the fire instead; clean termination is assumed.
 * </ul>
 *
 * <p>
 * <b>Unsupported event types are dropped.</b> {@link HookEventType#PERMISSION_REQUEST} and
 * {@link HookEventType#PERMISSION_DENIED} are rejected upstream at envelope creation time
 * (see {@link RewakeEnvelopes#from(HookEventType, HookContext, ExecutionHook, RewakeSpec)
 * RewakeEnvelopes.from}). {@link HookEventType#POST_TOOL}, {@link HookEventType#POST_COMPACT},
 * {@link HookEventType#ON_START}, {@link HookEventType#ON_STOP}, {@link HookEventType#SUBAGENT_START},
 * {@link HookEventType#SUBAGENT_STOP} reach the listener but are dropped with a WARN — their contexts require
 * heavyweight runtime state (post-tool result, transcript buffer, compaction metadata, …) that the envelope does not
 * capture.
 *
 * <p>
 * <b>Best-effort delivery.</b> The listener silently drops fires when:
 * <ul>
 * <li>the {@link AgentRuntimeRegistry} no longer holds the originating
 * {@link RewakeEnvelope#getAgentRuntimeId() context id} — the agent was removed or the JVM cycled before the
 * envelope persisted;
 * <li>the resolved context does not implement {@link RewakeCapableRuntime} — typical for lightweight test stubs that
 * have no hook registry to dispatch against;
 * <li>the registered originating hook is gone (config hot-reload removed it; the reloader
 * additionally cancels pending envelopes proactively when a hook is removed);
 * <li>the captured tool metadata is incomplete for {@code PRE_TOOL} (envelope lacks {@code originalToolName} /
 * {@code originalToolInput} — should not happen but the listener defends anyway).
 * </ul>
 * Each drop logs a WARN with the envelope id, originating hook id, and reason.
 *
 * <p>
 * <b>Single-hook re-dispatch.</b> Only the originating hook is invoked, not the full event-type fan-out — sibling
 * hooks already saw the original firing and the rewake exists precisely to give the originating hook a fresh decision
 * (or observation, for advisory event types).
 *
 * <p>
 * <b>Rewake-in-rewake (design §5.2).</b> When the re-dispatched hook returns more {@link RewakeSpec spec(s)}, the
 * listener chains them via {@link #bindRewakeService(RewakeService) the bound service}: each follow-up is scheduled
 * with {@code attemptNumber = previous + 1} as long as that count stays within the new spec's
 * {@link RewakeSpec#getMaxAttempts() maxAttempts}. Specs that would exceed the cap are dropped with a WARN. If no
 * service is bound, follow-ups are dropped at INFO so tests using a bare listener still observe the original fire.
 *
 * <p>
 * <b>Exception isolation.</b> Per the {@link RewakeFireListener} contract no exception is allowed to propagate out of
 * {@link #onFire(RewakeEnvelope)}. Any throwable from the hook or the rebuild path is caught and logged at WARN /
 * ERROR with the envelope id; the calling service continues unaffected.
 *
 * <p>
 * <b>Thread-safety.</b> The listener holds only the registry reference; concurrent {@code onFire} calls are safe so
 * long as the underlying hook implementations are.
 */
public final class DefaultRewakeFireListener implements RewakeFireListener {

    private static final Logger log = LoggerFactory.getLogger(DefaultRewakeFireListener.class);

    private final AgentRuntimeRegistry agentRuntimeRegistry;
    private volatile RewakeService rewakeService;

    /**
     * @param agentRuntimeRegistry
     *            registry used to resolve the originating agent context (must not be null)
     */
    public DefaultRewakeFireListener(AgentRuntimeRegistry agentRuntimeRegistry) {
        this.agentRuntimeRegistry = Objects.requireNonNull(agentRuntimeRegistry, "agentRuntimeRegistry cannot be null");
    }

    /**
     * Binds the {@link RewakeService} used for chained scheduling (rewake-in-rewake). Idempotent — calling twice with a
     * different service throws {@link IllegalStateException} so accidental double-wiring is surfaced loudly. Calling
     * with the same instance is a silent no-op.
     *
     * <p>
     * Bootstrap order: construct listener → construct service (passing listener) → call this method on the listener
     * with the freshly built service. The two-step wiring breaks the circular dependency without resorting to
     * suppliers / proxies.
     *
     * @param rewakeService
     *            service used to schedule follow-up envelopes (must not be null)
     * @throws NullPointerException
     *             if {@code rewakeService} is null
     * @throws IllegalStateException
     *             if a different service is already bound
     */
    public void bindRewakeService(RewakeService rewakeService) {
        Objects.requireNonNull(rewakeService, "rewakeService cannot be null");
        final RewakeService current = this.rewakeService;
        if (current != null && current != rewakeService) {
            throw new IllegalStateException(
                    "rewakeService already bound to a different instance; rebinding is not allowed");
        }
        this.rewakeService = rewakeService;
    }

    @Override
    public void onFire(RewakeEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope cannot be null");
        try {
            dispatch(envelope);
        } catch (RuntimeException e) {
            log.error("Unexpected error dispatching rewake fire (envelopeId={}, hookId={}): {}",
                    envelope.getEnvelopeId(), envelope.getOriginatingHookId(), e.getMessage(), e);
        }
    }

    private void dispatch(RewakeEnvelope envelope) {
        final HookEventType<?> eventType = envelope.getOriginalEventType();
        if (!isSupported(eventType)) {
            log.warn(
                    "Rewake fire for event type '{}' is not supported by DefaultRewakeFireListener "
                            + "(envelopeId={}, hookId={}). Dropping — only PRE_TOOL, ON_CONFIG_RELOAD, PRE_COMPACT, "
                            + "ON_SESSION_START, ON_SESSION_END are wired.",
                    eventType.name(), envelope.getEnvelopeId(), envelope.getOriginatingHookId());
            return;
        }

        final Optional<AgentRuntime> resolved = agentRuntimeRegistry.get(envelope.getAgentRuntimeId());
        if (resolved.isEmpty()) {
            log.warn(
                    "Rewake fire dropped — agent runtime not in registry "
                            + "(envelopeId={}, agentRuntimeId={}, hookId={}).",
                    envelope.getEnvelopeId(), envelope.getAgentRuntimeId(), envelope.getOriginatingHookId());
            return;
        }
        final AgentRuntime ctx = resolved.get();
        if (!(ctx instanceof RewakeCapableRuntime capable)) {
            log.warn(
                    "Rewake fire dropped — context type {} does not implement RewakeCapableRuntime "
                            + "(envelopeId={}, hookId={}).",
                    ctx.getClass().getName(), envelope.getEnvelopeId(), envelope.getOriginatingHookId());
            return;
        }

        final HookRegistry registry = capable.getHookRegistry();

        if (eventType == HookEventType.PRE_TOOL) {
            dispatchPreTool(envelope, capable, registry);
        } else if (eventType == HookEventType.ON_CONFIG_RELOAD) {
            runHook(envelope, HookEventType.ON_CONFIG_RELOAD, registry,
                    buildOnConfigReloadContext(envelope, capable, registry));
        } else if (eventType == HookEventType.PRE_COMPACT) {
            runHook(envelope, HookEventType.PRE_COMPACT, registry, buildPreCompactContext(envelope, capable, registry));
        } else if (eventType == HookEventType.ON_SESSION_START) {
            runHook(envelope, HookEventType.ON_SESSION_START, registry,
                    buildOnSessionStartContext(envelope, capable, registry));
        } else if (eventType == HookEventType.ON_SESSION_END) {
            runHook(envelope, HookEventType.ON_SESSION_END, registry,
                    buildOnSessionEndContext(envelope, capable, registry));
        }
    }

    private static boolean isSupported(HookEventType<?> eventType) {
        return eventType == HookEventType.PRE_TOOL || eventType == HookEventType.ON_CONFIG_RELOAD
                || eventType == HookEventType.PRE_COMPACT || eventType == HookEventType.ON_SESSION_START
                || eventType == HookEventType.ON_SESSION_END;
    }

    private void dispatchPreTool(RewakeEnvelope envelope, RewakeCapableRuntime capable, HookRegistry registry) {
        final Optional<String> toolName = envelope.getOriginalToolName();
        final Optional<ToolInput> toolInput = envelope.getOriginalToolInput();
        if (toolName.isEmpty() || toolInput.isEmpty()) {
            log.warn(
                    "Rewake fire dropped — PRE_TOOL envelope is missing tool metadata "
                            + "(envelopeId={}, hookId={}, toolName={}, hasInput={}).",
                    envelope.getEnvelopeId(), envelope.getOriginatingHookId(), toolName.orElse("<absent>"),
                    toolInput.isPresent());
            return;
        }
        final PreToolContext rebuilt = buildPreToolContext(envelope, capable, registry, toolName.get(),
                toolInput.get());
        runHook(envelope, HookEventType.PRE_TOOL, registry, rebuilt);
    }

    private <C extends HookContext, H extends ExecutionHook<C>> void runHook(RewakeEnvelope envelope,
            HookEventType<H> eventType, HookRegistry registry, C rebuiltContext) {
        final Optional<H> match = registry.getHooks(eventType).stream()
                .filter(h -> envelope.getOriginatingHookId().equals(h.getHookId())).findFirst();
        if (match.isEmpty()) {
            log.warn(
                    "Rewake fire dropped — originating hook id '{}' no longer registered for {} "
                            + "(envelopeId={}, agentRuntimeId={}). Possible config hot-reload race.",
                    envelope.getOriginatingHookId(), eventType.name(), envelope.getEnvelopeId(),
                    envelope.getAgentRuntimeId());
            return;
        }
        final H hook = match.get();
        log.info("Rewake fire dispatching to hook (envelopeId={}, hookId={}, eventType={}, attempt={}).",
                envelope.getEnvelopeId(), envelope.getOriginatingHookId(), eventType.name(),
                envelope.getAttemptNumber());
        try {
            final HookResult result = hook.execute(rebuiltContext);
            chainFollowUps(envelope, result.getRewakeSpecs());
        } catch (RuntimeException e) {
            log.warn("Rewake-fired hook threw (envelopeId={}, hookId={}, eventType={}): {}", envelope.getEnvelopeId(),
                    envelope.getOriginatingHookId(), eventType.name(), e.getMessage(), e);
        }
    }

    private void chainFollowUps(RewakeEnvelope previous, List<RewakeSpec> followUps) {
        if (followUps.isEmpty()) {
            return;
        }
        final RewakeService service = this.rewakeService;
        if (service == null) {
            log.info(
                    "Rewake fire produced {} follow-up spec(s) but no RewakeService is bound to the listener "
                            + "(envelopeId={}, hookId={}). Follow-ups dropped — call bindRewakeService at bootstrap.",
                    followUps.size(), previous.getEnvelopeId(), previous.getOriginatingHookId());
            return;
        }
        final int nextAttempt = previous.getAttemptNumber() + 1;
        for (RewakeSpec next : followUps) {
            if (next.getTrigger() instanceof RewakeTriggerCron) {
                // A cron envelope repeats natively — the scheduler keeps the original trigger registered and bounds it
                // with endAt(firstScheduledAt + timeout) plus the per-fire attempt cap. Chaining a second envelope off
                // each fire would not extend the series, it would fork it: every fire schedules another natively
                // repeating series, so the live envelope count doubles per fire (~2^(maxAttempts-1)). Declarative
                // hooks re-emit their configured spec on every fire by design, so this is the normal path, not a
                // pathological one. The initial envelope is unaffected — it is scheduled from the live turn by
                // DefaultHookExecutionManager#scheduleRewakes, which is what makes a cron asyncRewake work at all.
                log.debug(
                        "Rewake follow-up skipped — trigger is cron and repeats natively "
                                + "(envelopeId={}, hookId={}, reason='{}').",
                        previous.getEnvelopeId(), previous.getOriginatingHookId(), next.getReason());
                continue;
            }
            if (nextAttempt > next.getMaxAttempts()) {
                log.warn(
                        "Rewake chain dropped — attempt {} would exceed maxAttempts={} "
                                + "(envelopeId={}, hookId={}, reason='{}').",
                        nextAttempt, next.getMaxAttempts(), previous.getEnvelopeId(), previous.getOriginatingHookId(),
                        next.getReason());
                continue;
            }
            try {
                service.schedule(RewakeEnvelopes.chained(previous, next));
            } catch (RuntimeException e) {
                log.warn("Failed to schedule chained rewake (envelopeId={}, hookId={}, reason='{}'): {}",
                        previous.getEnvelopeId(), previous.getOriginatingHookId(), next.getReason(), e.getMessage(), e);
            }
        }
    }

    private PreToolContext buildPreToolContext(RewakeEnvelope envelope, RewakeCapableRuntime capable,
            HookRegistry registry, String toolName, ToolInput toolInput) {
        final ToolUse tu = ToolUse.of("rewake:" + envelope.getEnvelopeId(), toolName, toolInput.toMap());
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                .invokerName(envelope.getAgentRuntimeId().agentName()).hookRegistry(registry)
                .environment(capable.getEnvironment()).toolUse(tu).iterationCount(0).timestamp(Instant.now()).build();
    }

    private OnConfigReloadContext buildOnConfigReloadContext(RewakeEnvelope envelope, RewakeCapableRuntime capable,
            HookRegistry registry) {
        return OnConfigReloadContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName(envelope.getAgentRuntimeId().agentName()).hookRegistry(registry)
                .environment(capable.getEnvironment()).reloadCounter(0L).configSource("rewake").successful(true)
                .timestamp(Instant.now()).build();
    }

    /**
     * A rewake fire has no session — the envelope outlives whichever session scheduled it, and by the time it lands
     * that session may be closed, evicted, or on another node. So the fire is correlated by an {@link ExecutionId}
     * rather than being dressed up as a session id.
     *
     * <p>
     * The id pairs the envelope id with the {@link RewakeEnvelope#getAttemptNumber() attempt number}, because the
     * envelope id alone names the <em>envelope</em> and not this fire: a cron envelope keeps one id across every tick,
     * so all of its fires would be indistinguishable and per-fire state keyed on the id would pile into a single
     * bucket. The attempt number separates them — the Quartz service advances it after each cron fire, a chained
     * {@code delay} follow-up is issued under a fresh envelope id anyway, and an {@code event} envelope is cancelled
     * before it can land twice. The pair is therefore distinct per fire without a random tail, which keeps the id
     * deterministic and lines it up with the {@code attempt=} field this listener already logs.
     *
     * @param envelope
     *            the firing envelope (must not be null)
     * @return the correlation id for this fire (never null)
     */
    private static ExecutionId executionIdOf(RewakeEnvelope envelope) {
        return ExecutionId.of("rewake:" + envelope.getEnvelopeId() + ":" + envelope.getAttemptNumber());
    }

    private PreCompactContext buildPreCompactContext(RewakeEnvelope envelope, RewakeCapableRuntime capable,
            HookRegistry registry) {
        return PreCompactContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName(envelope.getAgentRuntimeId().agentName()).hookRegistry(registry)
                .environment(capable.getEnvironment()).trigger(CompactionTrigger.MANUAL)
                .executionId(executionIdOf(envelope)).messageCount(0).estimatedTokens(0).timestamp(Instant.now())
                .build();
    }

    private OnSessionStartContext buildOnSessionStartContext(RewakeEnvelope envelope, RewakeCapableRuntime capable,
            HookRegistry registry) {
        return OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName(envelope.getAgentRuntimeId().agentName()).hookRegistry(registry)
                .environment(capable.getEnvironment()).executionId(executionIdOf(envelope))
                .agentRuntimeId(envelope.getAgentRuntimeId().value()).timestamp(Instant.now()).build();
    }

    private OnSessionEndContext buildOnSessionEndContext(RewakeEnvelope envelope, RewakeCapableRuntime capable,
            HookRegistry registry) {
        return OnSessionEndContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName(envelope.getAgentRuntimeId().agentName()).hookRegistry(registry)
                .environment(capable.getEnvironment()).executionId(executionIdOf(envelope))
                .agentRuntimeId(envelope.getAgentRuntimeId().value()).clean(true).timestamp(Instant.now()).build();
    }
}
