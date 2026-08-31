/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz.cron;

import java.text.ParseException;
import java.util.Objects;
import java.util.SortedSet;
import java.util.stream.Collectors;

import org.quartz.CronExpression;

import at.aimon.core.scheduling.cron.UnixCronExpression;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;

/**
 * Rewrites a scheduled task's five-field cron expression into the six-field one Quartz reads.
 *
 * <p>
 * The two dialects are close enough to look interchangeable and are not. Handing Quartz a five-field expression fails,
 * but rarely says why: Quartz reads the first field as seconds, so every remaining field shifts one place left and is
 * validated against the wrong position. {@code 0 0 * * MON} comes back as a complaint about an invalid <em>month</em>,
 * and {@code 0 0 * * 5} as {@code Unexpected end of expression} — a message about a day of the week, reported as a
 * missing sixth field.
 *
 * <p>
 * It cannot come back <em>silently</em>, and an earlier version of this note claimed otherwise. Five tokens is always
 * one short of the six Quartz requires, so a five-field expression is rejected on arrival whatever it contains
 * (verified against Quartz 2.5.2 across the shifted-name, shifted-number and short-expression cases). The silent
 * failure this class exists to prevent is the one below — the day-of-week renumbering, which produces an expression
 * Quartz accepts and runs on the wrong day.
 *
 * <p>
 * Three things change on the way across:
 *
 * <ul>
 * <li><b>A seconds field is prepended</b>, fixed at {@code 0}. Five-field expressions have a one-minute resolution and
 * this preserves it.
 * <li><b>Day-of-week is renumbered.</b> The stored dialect starts the week at Sunday {@code 0}; Quartz starts it at
 * Sunday {@code 1}. Every numeric day therefore differs by one, and this is the change that would go unnoticed if it
 * were skipped — a schedule written for Friday would run on Thursday and still run weekly, on time, forever. The
 * translation works from {@link UnixCronExpression#daysOfWeek()}, the resolved set of days, rather than from the text,
 * because the text has forms ({@code 0-7}, {@code 2/3}) that adding one to each number would get wrong.
 * <li><b>One day field becomes {@code ?}.</b> Quartz requires exactly one of day-of-month and day-of-week to be
 * unspecified; the stored dialect has no such character. Whichever field is unrestricted becomes {@code ?}.
 * </ul>
 *
 * <p>
 * <b>What cannot cross.</b> When <em>both</em> day fields are restricted, the stored dialect fires on the union of them
 * — {@code 0 0 15 * MON} means Mondays and the 15th — and Quartz has no way to say that: one of its two day fields must
 * be {@code ?}, so it can express either half but never the sum. Such an expression is rejected with an explanation.
 * Rejecting is the whole point: both plausible repairs (drop a field, or {@code AND} them) produce a schedule that runs
 * successfully and is not the one that was asked for.
 */
public final class QuartzCronTranslator {

    /** The seconds field prepended to every translated expression. */
    private static final String SECONDS = "0";

    /** Quartz's "no value here" character, which the five-field dialect does not have. */
    private static final String NO_VALUE = "?";

    /** Sunday is 0 in the stored dialect and 1 in Quartz's. */
    private static final int DAY_OF_WEEK_OFFSET = 1;

    private QuartzCronTranslator() {
    }

    /**
     * Translates a five-field expression into Quartz's six-field dialect.
     *
     * @param unixExpression
     *            the expression as a scheduled task stores it
     * @return an expression Quartz accepts, describing the same schedule
     * @throws NullPointerException
     *             if {@code unixExpression} is null
     * @throws InvalidCronExpressionException
     *             if it is not a valid five-field expression, or describes a schedule Quartz cannot express
     */
    public static String toQuartz(String unixExpression) {
        Objects.requireNonNull(unixExpression, "Cron expression cannot be null");
        return toQuartz(UnixCronExpression.parse(unixExpression));
    }

    /**
     * Translates a parsed five-field expression into Quartz's six-field dialect.
     *
     * @param unix
     *            the parsed expression
     * @return an expression Quartz accepts, describing the same schedule
     * @throws NullPointerException
     *             if {@code unix} is null
     * @throws InvalidCronExpressionException
     *             if it describes a schedule Quartz cannot express
     */
    public static String toQuartz(UnixCronExpression unix) {
        Objects.requireNonNull(unix, "Cron expression cannot be null");

        if (unix.restrictsBothDayFields()) {
            throw new InvalidCronExpressionException(unix.expression(), "day-of-month '" + unix.dayOfMonth()
                    + "' and day-of-week '" + unix.dayOfWeek()
                    + "' are both restricted, which means \"either day\" here but cannot be expressed in Quartz at all"
                    + " — one of its two day fields must be '?', so it can say either half and never the union."
                    + " Register the two halves as separate tasks.");
        }

        final String dayOfMonth;
        final String dayOfWeek;
        if (unix.isDayOfWeekRestricted()) {
            dayOfMonth = NO_VALUE;
            dayOfWeek = quartzDaysOfWeek(unix.daysOfWeek());
        } else {
            dayOfMonth = unix.dayOfMonth();
            dayOfWeek = NO_VALUE;
        }

        final String quartzExpression = String.join(" ", SECONDS, unix.minute(), unix.hour(), dayOfMonth, unix.month(),
                dayOfWeek);

        // Belt and braces: the fields above pass through unchanged, so a construct the two dialects happen to disagree
        // on would otherwise surface as a Quartz failure at trigger-build time, naming an expression the caller never
        // wrote. Checking here lets the error carry both forms.
        try {
            CronExpression.validateExpression(quartzExpression);
        } catch (ParseException e) {
            throw new InvalidCronExpressionException(unix.expression(),
                    "translates to Quartz as '" + quartzExpression + "', which Quartz rejects: " + e.getMessage());
        }
        return quartzExpression;
    }

    private static String quartzDaysOfWeek(SortedSet<Integer> days) {
        return days.stream().map(day -> String.valueOf(day + DAY_OF_WEEK_OFFSET)).collect(Collectors.joining(","));
    }
}
