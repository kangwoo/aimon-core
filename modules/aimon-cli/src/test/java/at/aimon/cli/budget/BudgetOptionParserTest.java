package at.aimon.cli.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BudgetOptionParser Tests")
class BudgetOptionParserTest {

    @Nested
    @DisplayName("parsePositiveInt")
    class ParsePositiveInt {

        @Test
        @DisplayName("parses simple positive integers")
        void parsesSimplePositiveIntegers() {
            assertThat(BudgetOptionParser.parsePositiveInt("1", "max-iterations")).isEqualTo(1);
            assertThat(BudgetOptionParser.parsePositiveInt("42", "max-iterations")).isEqualTo(42);
            assertThat(BudgetOptionParser.parsePositiveInt("100000", "max-tokens")).isEqualTo(100_000);
        }

        @Test
        @DisplayName("trims surrounding whitespace")
        void trimsSurroundingWhitespace() {
            assertThat(BudgetOptionParser.parsePositiveInt("  42  ", "max-tokens")).isEqualTo(42);
        }

        @Test
        @DisplayName("rejects zero")
        void rejectsZero() {
            assertThatThrownBy(() -> BudgetOptionParser.parsePositiveInt("0", "max-iterations"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-iterations must be >= 1");
        }

        @Test
        @DisplayName("rejects negative values")
        void rejectsNegativeValues() {
            assertThatThrownBy(() -> BudgetOptionParser.parsePositiveInt("-5", "max-tokens"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-tokens must be >= 1");
        }

        @Test
        @DisplayName("rejects non-numeric input")
        void rejectsNonNumericInput() {
            assertThatThrownBy(() -> BudgetOptionParser.parsePositiveInt("abc", "max-iterations"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid max-iterations")
                    .hasMessageContaining("abc");
        }

        @Test
        @DisplayName("rejects empty string")
        void rejectsEmptyString() {
            assertThatThrownBy(() -> BudgetOptionParser.parsePositiveInt("   ", "max-iterations"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be empty");
        }

        @Test
        @DisplayName("rejects null input")
        void rejectsNullInput() {
            assertThatThrownBy(() -> BudgetOptionParser.parsePositiveInt(null, "max-iterations"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects values exceeding Integer.MAX_VALUE")
        void rejectsIntegerOverflow() {
            // Integer.MAX_VALUE == 2147483647; this value would silently wrap to a negative
            // if parsed as a Java int primitive. Integer.parseInt must reject it as NFE and
            // the parser must wrap it in a friendly IllegalArgumentException.
            assertThatThrownBy(() -> BudgetOptionParser.parsePositiveInt("2147483648", "max-iterations"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid max-iterations")
                    .hasMessageContaining("2147483648");
        }

        @Test
        @DisplayName("rejects decimal-looking input")
        void rejectsDecimalInput() {
            assertThatThrownBy(() -> BudgetOptionParser.parsePositiveInt("1.5", "max-tokens"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid max-tokens");
        }

        @Test
        @DisplayName("accepts Integer.MAX_VALUE boundary")
        void acceptsIntegerMaxValue() {
            assertThat(BudgetOptionParser.parsePositiveInt("2147483647", "max-iterations"))
                    .isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("rejects null fieldName")
        void rejectsNullFieldName() {
            assertThatThrownBy(() -> BudgetOptionParser.parsePositiveInt("1", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("parseDuration")
    class ParseDuration {

        @Test
        @DisplayName("parses milliseconds suffix")
        void parsesMilliseconds() {
            assertThat(BudgetOptionParser.parseDuration("500ms", "timeout")).isEqualTo(Duration.ofMillis(500));
        }

        @Test
        @DisplayName("parses seconds suffix")
        void parsesSeconds() {
            assertThat(BudgetOptionParser.parseDuration("30s", "timeout")).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("parses minutes suffix")
        void parsesMinutes() {
            assertThat(BudgetOptionParser.parseDuration("5m", "timeout")).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("parses hours suffix")
        void parsesHours() {
            assertThat(BudgetOptionParser.parseDuration("2h", "timeout")).isEqualTo(Duration.ofHours(2));
        }

        @Test
        @DisplayName("parses days suffix")
        void parsesDays() {
            assertThat(BudgetOptionParser.parseDuration("1d", "timeout")).isEqualTo(Duration.ofDays(1));
        }

        @Test
        @DisplayName("defaults bare numbers to seconds")
        void defaultsBareNumbersToSeconds() {
            assertThat(BudgetOptionParser.parseDuration("45", "timeout")).isEqualTo(Duration.ofSeconds(45));
        }

        @Test
        @DisplayName("accepts whitespace between value and unit")
        void acceptsWhitespaceBetweenValueAndUnit() {
            assertThat(BudgetOptionParser.parseDuration("30 s", "timeout")).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("is case-insensitive for units")
        void isCaseInsensitiveForUnits() {
            assertThat(BudgetOptionParser.parseDuration("30S", "timeout")).isEqualTo(Duration.ofSeconds(30));
            assertThat(BudgetOptionParser.parseDuration("5M", "timeout")).isEqualTo(Duration.ofMinutes(5));
            assertThat(BudgetOptionParser.parseDuration("500MS", "timeout")).isEqualTo(Duration.ofMillis(500));
        }

        @Test
        @DisplayName("handles uppercase unit with whitespace")
        void handlesUppercaseUnitWithWhitespace() {
            assertThat(BudgetOptionParser.parseDuration("30 M", "timeout")).isEqualTo(Duration.ofMinutes(30));
            assertThat(BudgetOptionParser.parseDuration("500  MS", "timeout")).isEqualTo(Duration.ofMillis(500));
        }

        @Test
        @DisplayName("parses ISO-8601 format")
        void parsesIso8601() {
            assertThat(BudgetOptionParser.parseDuration("PT30S", "timeout")).isEqualTo(Duration.ofSeconds(30));
            assertThat(BudgetOptionParser.parseDuration("PT5M", "timeout")).isEqualTo(Duration.ofMinutes(5));
            assertThat(BudgetOptionParser.parseDuration("PT1H30M", "timeout")).isEqualTo(Duration.ofMinutes(90));
        }

        @Test
        @DisplayName("trims surrounding whitespace")
        void trimsSurroundingWhitespace() {
            assertThat(BudgetOptionParser.parseDuration("  30s  ", "timeout")).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("rejects empty string")
        void rejectsEmptyString() {
            assertThatThrownBy(() -> BudgetOptionParser.parseDuration("   ", "timeout"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be empty");
        }

        @Test
        @DisplayName("rejects zero duration")
        void rejectsZeroDuration() {
            assertThatThrownBy(() -> BudgetOptionParser.parseDuration("0s", "timeout"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be positive");
        }

        @Test
        @DisplayName("rejects negative ISO-8601 duration")
        void rejectsNegativeIsoDuration() {
            assertThatThrownBy(() -> BudgetOptionParser.parseDuration("PT-5S", "timeout"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be positive");
        }

        @Test
        @DisplayName("rejects unknown unit")
        void rejectsUnknownUnit() {
            assertThatThrownBy(() -> BudgetOptionParser.parseDuration("30x", "timeout"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid timeout")
                    .hasMessageContaining("30x");
        }

        @Test
        @DisplayName("rejects garbage input")
        void rejectsGarbageInput() {
            assertThatThrownBy(() -> BudgetOptionParser.parseDuration("abc", "timeout"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid timeout");
        }

        @Test
        @DisplayName("uses provided fieldName in error messages")
        void usesProvidedFieldNameInErrors() {
            assertThatThrownBy(() -> BudgetOptionParser.parseDuration("abc", "--timeout"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid --timeout");
            assertThatThrownBy(() -> BudgetOptionParser.parseDuration("0s", "--timeout"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--timeout must be positive");
            assertThatThrownBy(() -> BudgetOptionParser.parseDuration("  ", "--timeout"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--timeout cannot be empty");
        }

        @Test
        @DisplayName("rejects null input")
        void rejectsNullInput() {
            assertThatThrownBy(() -> BudgetOptionParser.parseDuration(null, "timeout"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null fieldName")
        void rejectsNullFieldName() {
            assertThatThrownBy(() -> BudgetOptionParser.parseDuration("30s", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
