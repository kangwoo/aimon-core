/**
 * Where the idle-session waker ({@code aimon-session-routing}'s {@code IdleSessionWaker}) keeps its per-session caps:
 * the {@link at.aimon.core.agent.session.wake.WakeLedger} SPI, its value
 * {@link at.aimon.core.agent.session.wake.WakeState},
 * and the in-memory default.
 *
 * <h2>Why this lives in aimon-core</h2>
 *
 * <p>
 * The same reason the other session SPIs do ({@code SessionInbox}, {@code IdempotencyStore}, ...): the backends that
 * implement a shared ledger ({@code aimon-session-redis}, {@code -postgres}, {@code -mongodb}) depend on core and not
 * on
 * the routing module, and a ledger in routing would have given them that dependency back. Design
 * {@code docs/design/session/idle-wake.md} §4.3.
 */
package at.aimon.core.agent.session.wake;
