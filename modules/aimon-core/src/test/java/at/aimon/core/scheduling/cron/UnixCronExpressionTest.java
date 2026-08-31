/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.cron;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.core.scheduling.exception.InvalidCronExpressionException;

@DisplayName("UnixCronExpression")
class UnixCronExpressionTest {

    @Nested
    @DisplayName("fields")
    class Fields {

        @Test
        @DisplayName("are exposed one per position")
        void areExposedOnePerPosition() {
            final UnixCronExpression cron = UnixCronExpression.parse("15 3 1 6 2");

            assertThat(cron.minute()).isEqualTo("15");
            assertThat(cron.hour()).isEqualTo("3");
            assertThat(cron.dayOfMonth()).isEqualTo("1");
            assertThat(cron.month()).isEqualTo("6");
            assertThat(cron.dayOfWeek()).isEqualTo("2");
        }

        @Test
        @DisplayName("normalise names to numbers, so a translator never has to know the month spellings")
        void normaliseNamesToNumbers() {
            assertThat(UnixCronExpression.parse("0 0 1 MAR *").month()).isEqualTo("3");
        }

        @Test
        @DisplayName("keep the source text available for messages")
        void keepTheSourceText() {
            assertThat(UnixCronExpression.parse("  0 0 * * MON  ").expression()).isEqualTo("0 0 * * MON");
        }
    }

    @Nested
    @DisplayName("day-of-week resolution")
    class DayOfWeekResolution {

        @ParameterizedTest(name = "[{0}] selects {1}")
        @CsvSource({"'*', '0,1,2,3,4,5,6'", "'0', '0'", "'6', '6'", "'MON', '1'", "'mon', '1'", "'1-5', '1,2,3,4,5'",
                "'MON-FRI', '1,2,3,4,5'", "'1,3,5', '1,3,5'", "'SUN,SAT', '0,6'", "'1-3,5', '1,2,3,5'",
                "'*/2', '0,2,4,6'", "'1-5/2', '1,3,5'"})
        void resolvesTheField(String dayOfWeek, String expected) {
            assertThat(UnixCronExpression.parse("0 0 * * " + dayOfWeek).daysOfWeek())
                    .containsExactlyElementsOf(days(expected));
        }

        @Test
        @DisplayName("folds 7 onto Sunday, which is the other name for the same day")
        void foldsSevenOntoSunday() {
            assertThat(UnixCronExpression.parse("0 0 * * 7").daysOfWeek()).containsExactly(0);
            assertThat(UnixCronExpression.parse("0 0 * * 0,7").daysOfWeek()).containsExactly(0);
        }

        @Test
        @DisplayName("reads 0-7 as the whole week, not as Sunday twice")
        void readsZeroToSevenAsTheWholeWeek() {
            // The trap this exists for: a translator that adds one to each endpoint turns 0-7 into 1-1, which is a
            // valid expression meaning Sundays only. Resolving the range first is what makes that unreachable.
            assertThat(UnixCronExpression.parse("0 0 * * 0-7").daysOfWeek()).containsExactly(0, 1, 2, 3, 4, 5, 6);
        }

        @Test
        @DisplayName("counts a bare step to the end of the week")
        void countsABareStepToTheEndOfTheWeek() {
            // `2/3` is an open-ended start, not a value: Tuesday, then every third day while the week lasts.
            assertThat(UnixCronExpression.parse("0 0 * * 2/3").daysOfWeek()).containsExactly(2, 5);
        }
    }

    @Nested
    @DisplayName("restriction")
    class Restriction {

        @Test
        @DisplayName("is absent when both day fields are *")
        void isAbsentWhenBothDayFieldsAreStars() {
            final UnixCronExpression cron = UnixCronExpression.parse("0 0 * * *");

            assertThat(cron.isDayOfMonthRestricted()).isFalse();
            assertThat(cron.isDayOfWeekRestricted()).isFalse();
            assertThat(cron.restrictsBothDayFields()).isFalse();
        }

        @Test
        @DisplayName("is reported per day field")
        void isReportedPerDayField() {
            assertThat(UnixCronExpression.parse("0 0 15 * *").isDayOfMonthRestricted()).isTrue();
            assertThat(UnixCronExpression.parse("0 0 15 * *").isDayOfWeekRestricted()).isFalse();
            assertThat(UnixCronExpression.parse("0 0 * * MON").isDayOfMonthRestricted()).isFalse();
            assertThat(UnixCronExpression.parse("0 0 * * MON").isDayOfWeekRestricted()).isTrue();
        }

        @Test
        @DisplayName("of a day-of-week naming every day is no restriction at all")
        void ofADayOfWeekNamingEveryDayIsNoRestriction() {
            assertThat(UnixCronExpression.parse("0 0 * * 0-6").isDayOfWeekRestricted()).isFalse();
        }

        @Test
        @DisplayName("of both day fields is flagged, because it is the case a Quartz-shaped engine cannot express")
        void ofBothDayFieldsIsFlagged() {
            assertThat(UnixCronExpression.parse("0 0 15 * MON").restrictsBothDayFields()).isTrue();
        }
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @ParameterizedTest(name = "rejects [{0}]")
        @ValueSource(strings = {"0 0 * *", "0 0 * * * *", "0 0 0 * * *", "0 0 ? * MON", "0 0 * * ?", "0 0 L * *",
                "0 0 * * MON#2", "@daily", "0 0 * * FRI-MON", "0 0 * * 8", "0 0 32 * *", "", "   "})
        void rejects(String expression) {
            assertThatThrownBy(() -> UnixCronExpression.parse(expression))
                    .isInstanceOf(InvalidCronExpressionException.class);
        }

        @Test
        @DisplayName("names the offending expression in the failure")
        void namesTheOffendingExpression() {
            assertThatThrownBy(() -> UnixCronExpression.parse("0 0 * * 9"))
                    .isInstanceOfSatisfying(InvalidCronExpressionException.class,
                            e -> assertThat(e.getCronExpression()).isEqualTo("0 0 * * 9"))
                    .hasMessageContaining("0 0 * * 9");
        }

        @Test
        @DisplayName("rejects null loudly rather than as an invalid expression")
        void rejectsNull() {
            assertThatThrownBy(() -> UnixCronExpression.parse(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Cron expression cannot be null");
        }

        @ParameterizedTest(name = "accepts [{0}]")
        @ValueSource(strings = {"* * * * *", "*/5 * * * *", "0 0 * * *", "30 9 * * MON-FRI", "0 3 1 * *", "0 */6 * * *",
                "15,45 * * * *", "0 0 1 1 *", "0 0 * JAN-MAR *"})
        void accepts(String expression) {
            assertThat(UnixCronExpression.parse(expression).expression()).isEqualTo(expression);
        }
    }

    @Nested
    @DisplayName("next execution")
    class NextExecution {

        @Test
        @DisplayName("is computed in the dialect's own terms, so nothing else has to hold a parser")
        void isComputed() {
            final ZonedDateTime midnight = ZonedDateTime.of(2026, 3, 2, 0, 0, 0, 0, ZoneOffset.UTC);

            assertThat(UnixCronExpression.parse("30 9 * * *").nextExecution(midnight))
                    .contains(ZonedDateTime.of(2026, 3, 2, 9, 30, 0, 0, ZoneOffset.UTC));
        }

        @Test
        @DisplayName("respects the day-of-week numbering — 5 is Friday here")
        void respectsTheDayOfWeekNumbering() {
            // 2026-03-02 is a Monday; the next Friday is the 6th.
            final ZonedDateTime monday = ZonedDateTime.of(2026, 3, 2, 0, 0, 0, 0, ZoneOffset.UTC);

            assertThat(UnixCronExpression.parse("0 0 * * 5").nextExecution(monday))
                    .contains(ZonedDateTime.of(2026, 3, 6, 0, 0, 0, 0, ZoneOffset.UTC));
        }
    }

    private static List<Integer> days(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).map(Integer::valueOf).toList();
    }
}
