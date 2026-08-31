/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz.cron;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.CronExpression;

import at.aimon.core.scheduling.cron.UnixCronExpression;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;

@DisplayName("QuartzCronTranslator")
class QuartzCronTranslatorTest {

    @Nested
    @DisplayName("translation")
    class Translation {

        @ParameterizedTest(name = "[{0}] -> [{1}]")
        @CsvSource({
                // Day-of-week unrestricted: day-of-month passes through and day-of-week takes the '?'.
                "'* * * * *', '0 * * * * ?'", "'*/5 * * * *', '0 */5 * * * ?'", "'0 0 * * *', '0 0 0 * * ?'",
                "'30 9 * * *', '0 30 9 * * ?'", "'0 3 1 * *', '0 0 3 1 * ?'", "'0 0 1,15 * *', '0 0 0 1,15 * ?'",
                "'0 0 15 6 *', '0 0 0 15 6 ?'",
                // Day-of-week restricted: renumbered to Sunday=1 and listed, and day-of-month takes the '?'.
                "'0 0 * * 0', '0 0 0 ? * 1'", "'0 0 * * 1', '0 0 0 ? * 2'", "'0 0 * * 6', '0 0 0 ? * 7'",
                "'0 0 * * MON', '0 0 0 ? * 2'", "'30 9 * * MON-FRI', '0 30 9 ? * 2,3,4,5,6'",
                "'0 12 * * SAT,SUN', '0 0 12 ? * 1,7'", "'0 0 * * */2', '0 0 0 ? * 1,3,5,7'",
                // The forms a per-number +1 would get wrong: 7 is Sunday again, 0-7 is the whole week (so not a
                // restriction at all), and 2/3 counts to the end of the week rather than naming two days.
                "'0 0 * * 7', '0 0 0 ? * 1'", "'0 0 * * 0-7', '0 0 0 * * ?'", "'0 0 * * 2/3', '0 0 0 ? * 3,6'"})
        void rewritesTheExpression(String unix, String expectedQuartz) {
            assertThat(QuartzCronTranslator.toQuartz(unix)).isEqualTo(expectedQuartz);
        }

        @Test
        @DisplayName("prepends a zero seconds field, because five-field expressions have minute resolution")
        void prependsAZeroSecondsField() {
            assertThat(QuartzCronTranslator.toQuartz("* * * * *")).startsWith("0 ");
        }

        @Test
        @DisplayName("shifts every numeric day by one — the change that would otherwise never be noticed")
        void shiftsEveryNumericDayByOne() {
            // Friday. Passing the field through unchanged also produces a valid weekly schedule — on Thursday.
            assertThat(QuartzCronTranslator.toQuartz("0 0 * * 5")).isEqualTo("0 0 0 ? * 6");
        }

        @Test
        @DisplayName("accepts an already-parsed expression without reparsing it")
        void acceptsAnAlreadyParsedExpression() {
            assertThat(QuartzCronTranslator.toQuartz(UnixCronExpression.parse("0 0 * * MON"))).isEqualTo("0 0 0 ? * 2");
        }

        @Test
        @DisplayName("produces expressions Quartz itself accepts")
        void producesExpressionsQuartzAccepts() throws ParseException {
            for (String expression : SCHEDULES) {
                CronExpression.validateExpression(QuartzCronTranslator.toQuartz(expression));
            }
        }

        @Test
        @DisplayName("and Quartz rejects every untranslated one, so skipping this class cannot fail silently")
        void quartzRejectsTheUntranslatedForm() {
            // Pins a third-party behaviour the class javadoc now asserts, and which an earlier version of it got
            // wrong. Five tokens is one short of the six Quartz requires, so an untranslated expression cannot
            // shift into something Quartz accepts — the messages vary (a day name lands in the month field and is
            // reported as an invalid month; a bare number runs out of expression) but the outcome never does.
            //
            // Worth a test rather than a sentence because it is the load-bearing half of a decision recorded
            // elsewhere: the cost of the second cron dialect on the rewake and dreamer surfaces is confusion and
            // a late error, not a schedule that runs at the wrong time.
            for (String expression : SCHEDULES) {
                assertThatThrownBy(() -> CronExpression.validateExpression(expression))
                        .as("Quartz accepted the untranslated [%s]", expression).isInstanceOf(ParseException.class);
            }
        }
    }

    @Nested
    @DisplayName("refusal")
    class Refusal {

        @Test
        @DisplayName("of both day fields restricted, which Quartz has no shape for")
        void ofBothDayFieldsRestricted() {
            // "the 15th, and every Monday". Quartz needs a '?' in one of the two fields, so it can say either half of
            // this and never the sum — and both halves run successfully while being the wrong schedule.
            assertThatThrownBy(() -> QuartzCronTranslator.toQuartz("0 0 15 * MON"))
                    .isInstanceOf(InvalidCronExpressionException.class).hasMessageContaining("0 0 15 * MON")
                    .hasMessageContaining("separate tasks");
        }

        @ParameterizedTest(name = "of [{0}], which is not in the dialect it translates from")
        @ValueSource(strings = {"0 0 12 * * ?", "0 0 * *", "0 0 L * *", "@daily", "invalid"})
        void ofExpressionsOutsideTheSourceDialect(String expression) {
            assertThatThrownBy(() -> QuartzCronTranslator.toQuartz(expression))
                    .isInstanceOf(InvalidCronExpressionException.class);
        }

        @Test
        @DisplayName("of null, loudly")
        void ofNull() {
            assertThatThrownBy(() -> QuartzCronTranslator.toQuartz((String) null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> QuartzCronTranslator.toQuartz((UnixCronExpression) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * The test that would have caught the day-of-week off-by-one on its own: rather than asserting on the text, it
     * walks
     * both engines forward and compares when they actually fire. A translation that is one day out cannot survive it,
     * and neither can one that quietly drops a field.
     */
    @Nested
    @DisplayName("equivalence with the source dialect")
    class Equivalence {

        private static final ZonedDateTime START = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        private static final int FIRINGS = 24;

        @ParameterizedTest(name = "[{0}] fires at the same times in both dialects")
        @ValueSource(strings = {"* * * * *", "*/5 * * * *", "0 * * * *", "30 9 * * *", "*/15 8-18 * * *", "0 0 * * 0",
                "0 0 * * 1", "0 0 * * 5", "0 0 * * 6", "0 0 * * 7", "0 0 * * 0-7", "0 0 * * */2", "0 0 * * 2/3",
                "30 9 * * MON-FRI", "0 12 * * SAT,SUN", "0 0 1 * *", "0 0 1,15 * *", "0 0 15 6 *"})
        void firesAtTheSameTimes(String expression) throws ParseException {
            assertThat(quartzFirings(QuartzCronTranslator.toQuartz(expression)))
                    .containsExactlyElementsOf(unixFirings(expression));
        }

        private List<Instant> unixFirings(String expression) {
            final UnixCronExpression cron = UnixCronExpression.parse(expression);
            final List<Instant> firings = new ArrayList<>();
            ZonedDateTime cursor = START;
            for (int i = 0; i < FIRINGS; i++) {
                cursor = cron.nextExecution(cursor).orElseThrow();
                firings.add(cursor.toInstant());
            }
            return firings;
        }

        private List<Instant> quartzFirings(String quartzExpression) throws ParseException {
            final CronExpression cron = new CronExpression(quartzExpression);
            cron.setTimeZone(TimeZone.getTimeZone(ZoneOffset.UTC));
            final List<Instant> firings = new ArrayList<>();
            Date cursor = Date.from(START.toInstant());
            for (int i = 0; i < FIRINGS; i++) {
                cursor = cron.getNextValidTimeAfter(cursor);
                firings.add(cursor.toInstant());
            }
            return firings;
        }
    }

    /** Expressions used where the assertion is "Quartz accepts whatever comes out", not a specific string. */
    private static final List<String> SCHEDULES = List.of("* * * * *", "*/5 * * * *", "0 * * * *", "30 9 * * *",
            "0 0 * * 0", "0 0 * * 7", "0 0 * * 0-7", "0 0 * * 2/3", "30 9 * * MON-FRI", "0 0 1 * *", "0 0 15 6 *",
            "0 0 29 2 *");
}
