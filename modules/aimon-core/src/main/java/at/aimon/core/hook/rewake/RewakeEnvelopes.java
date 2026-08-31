package at.aimon.core.hook.rewake;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.llm.ToolUse;

/**
 * Helper for materialising a {@link RewakeEnvelope} from a {@link RewakeSpec} the way the framework needs at runtime.
 *
 * <p>
 * The factory wires together fields the spec itself does not carry — agent runtime id derived from the
 * invoker name, originating-hook id from {@link ExecutionHook#getHookId()}, the original event type, and (for tool
 * scoped events) the captured tool name / input.
 *
 * <p>
 * Stateless utility — never instantiate.
 */
public final class RewakeEnvelopes {

    private RewakeEnvelopes() {
    }

    /**
     * Builds a fresh envelope for the first attempt.
     *
     * <p>
     * <b>Permission events are rejected.</b> Async-rewake on {@link HookEventType#PERMISSION_REQUEST
     * PERMISSION_REQUEST}
     * or {@link HookEventType#PERMISSION_DENIED PERMISSION_DENIED} would let a permission decision arrive after the
     * tool dispatcher has already moved on — by the time the rewake fires, the request that needed the answer is gone
     * and the only side effect is replay-as-noise. The factory throws {@link IllegalArgumentException} so the
     * surrounding {@link at.aimon.core.hook.DefaultHookExecutionManager hook executor} logs a clear WARN and the spec
     * is dropped.
     *
     * @param eventType
     *            the typed event being dispatched (must not be null)
     * @param context
     *            the hook context driving the dispatch (must not be null)
     * @param hook
     *            the originating hook (must not be null)
     * @param spec
     *            the spec emitted by the hook (must not be null)
     * @return new envelope (never null), with {@code attemptNumber=1} and a freshly-generated UUID id
     * @throws IllegalArgumentException
     *             if {@code eventType} is a permission event ({@code PERMISSION_REQUEST} / {@code PERMISSION_DENIED})
     */
    public static RewakeEnvelope from(HookEventType<?> eventType, HookContext context, ExecutionHook<?> hook,
            RewakeSpec spec) {
        if (eventType == HookEventType.PERMISSION_REQUEST || eventType == HookEventType.PERMISSION_DENIED) {
            throw new IllegalArgumentException(
                    "Async-rewake is not supported for " + eventType.name() + " — permission events must be synchronous"
                            + " to be meaningful (hookId=" + hook.getHookId() + ", reason='" + spec.getReason() + "')");
        }
        final Map<String, String> payload = new HashMap<>(spec.getPayload());
        final RewakeEnvelope.Builder builder = RewakeEnvelope.builder().envelopeId(UUID.randomUUID().toString())
                .agentRuntimeId(AgentRuntimeId.fromName(context.getInvokerName())).trigger(spec.getTrigger())
                .originalEventType(eventType).originatingHookId(hook.getHookId()).firstScheduledAt(Instant.now())
                .attemptNumber(1).maxAttempts(spec.getMaxAttempts()).timeout(spec.getTimeout()).reason(spec.getReason())
                .payload(payload);

        if (context instanceof PreToolContext pre) {
            final ToolUse tu = pre.getCurrentToolUse();
            builder.originalToolName(tu.getName()).originalToolInput(ToolInput.of(tu.getInput()));
        } else if (context instanceof PostToolContext post) {
            final ToolUse tu = post.getToolUse();
            builder.originalToolName(tu.getName()).originalToolInput(ToolInput.of(tu.getInput()));
        }
        return builder.build();
    }

    /**
     * Builds a chained envelope when a fired rewake's hook returns another {@link RewakeSpec}.
     *
     * <p>
     * Carries the originating identity ({@code agentRuntimeId}, {@code originalEventType},
     * {@code originatingHookId}, {@code originalToolName}, {@code originalToolInput}, {@code firstScheduledAt}) from
     * {@code previous}, takes {@code trigger} / {@code reason} / {@code payload} from {@code nextSpec}, and bumps
     * {@code attemptNumber} by one. A fresh UUID is assigned so cancellation by id targets each link independently.
     *
     * <p>
     * Caller is responsible for capping the chain against {@link RewakeSpec#getMaxAttempts() nextSpec.maxAttempts}
     * before invoking this factory — the helper itself does not enforce the limit.
     *
     * @param previous
     *            the envelope that just fired (must not be null)
     * @param nextSpec
     *            the new spec returned by the re-dispatched hook (must not be null)
     * @return chained envelope (never null), with {@code attemptNumber = previous.attemptNumber + 1}
     */
    public static RewakeEnvelope chained(RewakeEnvelope previous, RewakeSpec nextSpec) {
        final Map<String, String> payload = new HashMap<>(nextSpec.getPayload());
        final RewakeEnvelope.Builder builder = RewakeEnvelope.builder().envelopeId(UUID.randomUUID().toString())
                .agentRuntimeId(previous.getAgentRuntimeId()).trigger(nextSpec.getTrigger())
                .originalEventType(previous.getOriginalEventType()).originatingHookId(previous.getOriginatingHookId())
                .firstScheduledAt(previous.getFirstScheduledAt()).attemptNumber(previous.getAttemptNumber() + 1)
                .maxAttempts(nextSpec.getMaxAttempts()).timeout(nextSpec.getTimeout()).reason(nextSpec.getReason())
                .payload(payload);
        previous.getOriginalToolName().ifPresent(builder::originalToolName);
        previous.getOriginalToolInput().ifPresent(builder::originalToolInput);
        return builder.build();
    }
}
