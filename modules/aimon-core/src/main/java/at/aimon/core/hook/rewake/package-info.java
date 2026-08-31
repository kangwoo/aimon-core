/**
 * Async-rewake primitives — value classes describing a hook's request to be re-fired later.
 *
 * <p>
 * A hook that needs to defer its decision returns a {@link at.aimon.core.hook.execution.HookResult HookResult} carrying
 * a {@link at.aimon.core.hook.rewake.RewakeSpec RewakeSpec}. The framework converts that spec into a serializable
 * {@link at.aimon.core.hook.rewake.RewakeEnvelope RewakeEnvelope} and schedules a follow-up firing through the
 * application-scoped {@code RewakeService}.
 *
 * <p>
 * For the live turn, a result that carries rewake specs is observably equivalent to
 * {@link at.aimon.core.hook.execution.Decision#ALLOW Decision.ALLOW} — only the deferred firing is affected. See
 * {@code docs/design/hook/async-rewake.md} for the full semantics.
 *
 * <h2>Triggers</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.hook.rewake.RewakeTriggerDelay} — fire once at {@code now + delay}.
 * <li>{@link at.aimon.core.hook.rewake.RewakeTriggerCron} — recurring fire bounded by {@code RewakeSpec.timeout}.
 * <li>{@link at.aimon.core.hook.rewake.RewakeTriggerEvent} — fire when an external event with the matching
 * {@code (eventType, eventKey)} pair is resolved.
 * </ul>
 */
package at.aimon.core.hook.rewake;
