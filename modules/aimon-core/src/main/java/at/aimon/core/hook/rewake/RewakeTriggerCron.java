package at.aimon.core.hook.rewake;

import java.time.ZoneId;
import java.util.Objects;

import at.aimon.core.scheduling.cron.UnixCronExpression;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;

/**
 * Cron-style recurring trigger, in the framework's five-field dialect.
 *
 * <p>
 * The expression is a {@link UnixCronExpression} — minute, hour, day-of-month, month, day-of-week, Sunday {@code 0} —
 * the same one {@code ScheduledTask} stores. A scheduler backend whose engine speaks something else translates on the
 * way in; nothing downstream of this type receives another dialect.
 *
 * <p>
 * <b>Why it is validated here and not at the scheduler.</b> It used to be Quartz's six-field dialect, checked by the
 * Quartz-backed service at scheduling time. The cost was not that a wrong expression ran on the wrong schedule — five
 * tokens are always one short of six, so Quartz rejected every untranslated one — but <em>when</em> it was caught:
 * {@code aimon-core} cannot see Quartz, so a bad cron in a skill's YAML loaded cleanly and first died when the hook
 * fired, possibly in the middle of an agent turn. Parsing here moves that failure to load time, where the file that
 * caused it is still on screen.
 *
 * <p>
 * Recurring fires are bounded by {@link RewakeSpec#getTimeout()}; without a timeout the spec defaults to one hour.
 *
 * <p>
 * Immutable; safe to share across threads.
 */
public final class RewakeTriggerCron implements RewakeTrigger {

    private final String expression;
    private final ZoneId zone;

    /**
     * Creates a cron trigger.
     *
     * @param expression
     *            five-field cron expression (must not be null, blank, or invalid)
     * @param zone
     *            time zone in which the expression is evaluated (must not be null; use {@link ZoneId#of(String)
     *            ZoneId.of("UTC")} as a default)
     * @throws NullPointerException
     *             if {@code expression} or {@code zone} is null
     * @throws IllegalArgumentException
     *             if {@code expression} is blank
     * @throws InvalidCronExpressionException
     *             if it is not a valid five-field expression — including a six-field Quartz one, which this dialect
     *             rejects
     */
    public RewakeTriggerCron(String expression, ZoneId zone) {
        Objects.requireNonNull(expression, "expression cannot be null");
        if (expression.isBlank()) {
            throw new IllegalArgumentException("expression cannot be blank");
        }
        this.expression = UnixCronExpression.parse(expression).expression();
        this.zone = Objects.requireNonNull(zone, "zone cannot be null");
    }

    /**
     * Returns the cron expression.
     *
     * @return expression (never null or blank)
     */
    public String getExpression() {
        return expression;
    }

    /**
     * Returns the time zone in which the expression is evaluated.
     *
     * @return time zone (never null)
     */
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RewakeTriggerCron that)) {
            return false;
        }
        return expression.equals(that.expression) && zone.equals(that.zone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expression, zone);
    }

    @Override
    public String toString() {
        return "RewakeTriggerCron{expression='" + expression + "', zone=" + zone + '}';
    }
}
