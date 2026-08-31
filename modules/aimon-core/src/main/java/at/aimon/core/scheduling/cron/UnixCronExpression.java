/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.cron;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.field.CronFieldName;
import com.cronutils.model.field.expression.Always;
import com.cronutils.model.field.expression.And;
import com.cronutils.model.field.expression.Between;
import com.cronutils.model.field.expression.Every;
import com.cronutils.model.field.expression.FieldExpression;
import com.cronutils.model.field.expression.On;
import com.cronutils.model.field.value.FieldValue;
import com.cronutils.model.field.value.IntegerFieldValue;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import at.aimon.core.scheduling.exception.InvalidCronExpressionException;

/**
 * A cron expression in the dialect AIMON stores: five fields, no seconds, Sunday is {@code 0}.
 *
 * <p>
 * This is the <em>canonical</em> dialect of the framework. Every {@code ScheduledTask.cronExpression} is in it, it is
 * what {@code ScheduleTaskTool} advertises to the model, and every
 * {@link at.aimon.core.scheduling.scheduler.TaskScheduler}
 * backend receives it. A backend whose engine speaks something else translates on the way in — never the other way
 * round, because this direction is the lossless one: Quartz's seconds field, {@code L}, {@code W} and {@code #} have no
 * five-field equivalent, while every expression below maps onto a Quartz one.
 *
 * <p>
 * <b>The grammar, stated because a translator has to cover all of it.</b> Fields are minute, hour, day-of-month, month,
 * day-of-week. Each accepts {@code *}, a value, {@code from-to}, a {@code /step} on any of those, and comma-separated
 * lists of them. Names are accepted for month and day-of-week and are normalised to numbers by {@link #month()} and
 * {@link #daysOfWeek()}. Deliberately absent — because cron-utils' UNIX definition rejects them, and a scheduled task
 * that parses here has to parse everywhere: {@code ?}, {@code L}, {@code W}, {@code #}, {@code @daily} and friends, and
 * ranges that wrap the end of the field ({@code FRI-MON}).
 *
 * <p>
 * <b>Day-of-week is the field that silently goes wrong.</b> Here Sunday is {@code 0} and {@code 7} is also Sunday;
 * Quartz numbers Sunday {@code 1}. Every numeric day therefore means a different day in the two dialects, while the
 * names agree — which is why a translator must not pass this field through and why {@link #daysOfWeek()} hands out the
 * resolved set of days rather than the text that produced it. Resolving here also removes the traps in the text:
 * {@code 0-7} is every day (not, as a naive {@code n+1} would make it, Sunday twice), and {@code 2/3} counts up to the
 * end of the week from Tuesday.
 *
 * <p>
 * Instances are immutable and thread-safe. Two instances are not comparable — compare {@link #expression()} if source
 * text is what you mean; there is no test for two expressions describing the same schedule.
 *
 * @see at.aimon.core.scheduling.ScheduledTaskManager#validateCronExpression(String)
 */
public final class UnixCronExpression {

    /** Sunday, in the numbering {@link #daysOfWeek()} returns. */
    public static final int SUNDAY = 0;

    /** Saturday, in the numbering {@link #daysOfWeek()} returns. */
    public static final int SATURDAY = 6;

    /** The number of fields a valid expression has. */
    public static final int FIELD_COUNT = 5;

    private static final int DAYS_IN_WEEK = 7;

    private static final CronParser PARSER = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    private final String expression;

    private final String minute;

    private final String hour;

    private final String dayOfMonth;

    private final String month;

    private final String dayOfWeek;

    private final boolean dayOfMonthRestricted;

    private final SortedSet<Integer> daysOfWeek;

    private final ExecutionTime executionTime;

    private UnixCronExpression(String expression, Cron cron) {
        this.expression = expression;
        minute = fieldText(cron, CronFieldName.MINUTE);
        hour = fieldText(cron, CronFieldName.HOUR);
        dayOfMonth = fieldText(cron, CronFieldName.DAY_OF_MONTH);
        month = fieldText(cron, CronFieldName.MONTH);
        dayOfWeek = fieldText(cron, CronFieldName.DAY_OF_WEEK);
        dayOfMonthRestricted = !(cron.retrieve(CronFieldName.DAY_OF_MONTH).getExpression() instanceof Always);
        daysOfWeek = Collections.unmodifiableSortedSet(resolveDaysOfWeek(expression, cron));
        executionTime = ExecutionTime.forCron(cron);
    }

    /**
     * Parses a five-field expression.
     *
     * @param expression
     *            the expression to parse
     * @return the parsed expression
     * @throws NullPointerException
     *             if {@code expression} is null
     * @throws InvalidCronExpressionException
     *             if it is not a valid five-field expression
     */
    public static UnixCronExpression parse(String expression) {
        Objects.requireNonNull(expression, "Cron expression cannot be null");

        final Cron cron;
        try {
            cron = PARSER.parse(expression);
            cron.validate();
        } catch (RuntimeException e) {
            throw new InvalidCronExpressionException(expression, e);
        }
        return new UnixCronExpression(expression.trim(), cron);
    }

    /** Returns the expression as it was given, trimmed. */
    public String expression() {
        return expression;
    }

    /** Returns the minute field, with any names normalised to numbers. */
    public String minute() {
        return minute;
    }

    /** Returns the hour field, with any names normalised to numbers. */
    public String hour() {
        return hour;
    }

    /** Returns the day-of-month field, with any names normalised to numbers. */
    public String dayOfMonth() {
        return dayOfMonth;
    }

    /** Returns the month field, with any names normalised to numbers. */
    public String month() {
        return month;
    }

    /**
     * Returns the day-of-week field as text, for messages.
     *
     * <p>
     * Use {@link #daysOfWeek()} to act on it — the text is in this dialect's numbering and means a different set of
     * days
     * read as Quartz.
     */
    public String dayOfWeek() {
        return dayOfWeek;
    }

    /**
     * Returns the days this expression selects, {@code 0} = Sunday through {@code 6} = Saturday.
     *
     * <p>
     * Always non-empty; all seven when the field is unrestricted.
     *
     * @return the resolved days, ascending and unmodifiable
     */
    public SortedSet<Integer> daysOfWeek() {
        return daysOfWeek;
    }

    /**
     * Returns whether the day-of-month field selects fewer than every day.
     *
     * <p>
     * {@code *} is unrestricted; everything else is a restriction. {@code &#42;/1} is the one expression that reads
     * like
     * a restriction and is not: cron-utils folds it back into {@code *}, so it is reported unrestricted.
     */
    public boolean isDayOfMonthRestricted() {
        return dayOfMonthRestricted;
    }

    /** Returns whether the day-of-week field selects fewer than all seven days. */
    public boolean isDayOfWeekRestricted() {
        return daysOfWeek.size() < DAYS_IN_WEEK;
    }

    /**
     * Returns whether both day fields are restricted.
     *
     * <p>
     * This dialect <b>unions</b> them: {@code 0 0 15 * MON} fires on Mondays <em>and</em> on the 15th. The combination
     * is worth asking about because it is the one an engine with Quartz's day-field rules cannot express at all —
     * there,
     * one of the two fields must be {@code ?} — so such an expression can only be rejected, never translated.
     */
    public boolean restrictsBothDayFields() {
        return dayOfMonthRestricted && isDayOfWeekRestricted();
    }

    /**
     * Returns the next time this expression fires after the given instant.
     *
     * @param after
     *            the instant to search from
     * @return the next firing time, or empty if there is none
     */
    public Optional<ZonedDateTime> nextExecution(ZonedDateTime after) {
        Objects.requireNonNull(after, "Instant cannot be null");
        return executionTime.nextExecution(after);
    }

    @Override
    public String toString() {
        return expression;
    }

    private static String fieldText(Cron cron, CronFieldName name) {
        return cron.retrieve(name).getExpression().asString();
    }

    private static SortedSet<Integer> resolveDaysOfWeek(String expression, Cron cron) {
        final SortedSet<Integer> days = new TreeSet<>();
        collectDays(expression, cron.retrieve(CronFieldName.DAY_OF_WEEK).getExpression(), days);
        return days;
    }

    private static void collectDays(String expression, FieldExpression field, SortedSet<Integer> days) {
        if (field instanceof Always) {
            addStepped(expression, SUNDAY, SATURDAY, 1, days);
        } else if (field instanceof On on) {
            days.add(normalizeDay(on.getTime().getValue()));
        } else if (field instanceof Between between) {
            addStepped(expression, endpoint(expression, between.getFrom()), endpoint(expression, between.getTo()), 1,
                    days);
        } else if (field instanceof Every every) {
            collectSteppedDays(expression, every, days);
        } else if (field instanceof And and) {
            for (FieldExpression element : and.getExpressions()) {
                collectDays(expression, element, days);
            }
        } else {
            throw new InvalidCronExpressionException(expression,
                    "unsupported day-of-week expression '" + field.asString() + "'");
        }
    }

    private static void collectSteppedDays(String expression, Every every, SortedSet<Integer> days) {
        final int step = every.getPeriod().getValue();
        final FieldExpression base = every.getExpression();
        if (base instanceof Always) {
            addStepped(expression, SUNDAY, SATURDAY, step, days);
        } else if (base instanceof On on) {
            // `2/3` means "from Tuesday, every third day, to the end of the week" — an open-ended start, not a value.
            addStepped(expression, on.getTime().getValue(), SATURDAY, step, days);
        } else if (base instanceof Between between) {
            addStepped(expression, endpoint(expression, between.getFrom()), endpoint(expression, between.getTo()), step,
                    days);
        } else {
            throw new InvalidCronExpressionException(expression,
                    "unsupported day-of-week expression '" + every.asString() + "'");
        }
    }

    private static void addStepped(String expression, int from, int to, int step, SortedSet<Integer> days) {
        if (step < 1) {
            throw new InvalidCronExpressionException(expression, "day-of-week step must be at least 1, got " + step);
        }
        for (int day = from; day <= to; day += step) {
            days.add(normalizeDay(day));
        }
    }

    private static int endpoint(String expression, FieldValue<?> value) {
        if (value instanceof IntegerFieldValue integer) {
            return integer.getValue();
        }
        throw new InvalidCronExpressionException(expression, "unsupported day-of-week range endpoint '" + value + "'");
    }

    /** Folds {@code 7} onto {@code 0} — both are Sunday in this dialect. */
    private static int normalizeDay(int day) {
        return day % DAYS_IN_WEEK;
    }
}
