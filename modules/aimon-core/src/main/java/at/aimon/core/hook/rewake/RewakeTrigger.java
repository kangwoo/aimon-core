package at.aimon.core.hook.rewake;

/**
 * Sealed marker for the three rewake trigger shapes — exactly one of {@link RewakeTriggerDelay},
 * {@link RewakeTriggerCron}, or {@link RewakeTriggerEvent}.
 *
 * <p>
 * Implementations are immutable value objects; consumers pattern-match (or {@code instanceof}-pattern) to dispatch.
 */
public sealed interface RewakeTrigger permits RewakeTriggerDelay, RewakeTriggerCron, RewakeTriggerEvent {
}
