package at.aimon.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.cli.exception.CliException;
import at.aimon.core.agent.budget.ExecutionBudget;
import picocli.CommandLine;

@DisplayName("AimonCli CLI option parsing Tests")
class AimonCliTest {

    @Nested
    @DisplayName("buildInitialBudget")
    class BuildInitialBudget {

        @Test
        @DisplayName("returns null when no budget options are supplied")
        void returnsNullWhenNoOptions() {
            AimonCli cli = parse();

            assertThat(cli.buildInitialBudget()).isNull();
        }

        @Test
        @DisplayName("applies --max-iterations")
        void appliesMaxIterations() {
            AimonCli cli = parse("--max-iterations", "25");

            ExecutionBudget budget = cli.buildInitialBudget();

            assertThat(budget).isNotNull();
            assertThat(budget.getMaxIterations()).contains(25);
            assertThat(budget.getMaxTokens()).isEmpty();
            assertThat(budget.getMaxWallClockDuration()).isEmpty();
        }

        @Test
        @DisplayName("applies --max-tokens")
        void appliesMaxTokens() {
            AimonCli cli = parse("--max-tokens", "200000");

            ExecutionBudget budget = cli.buildInitialBudget();

            assertThat(budget).isNotNull();
            assertThat(budget.getMaxTokens()).contains(200_000);
        }

        @Test
        @DisplayName("applies --timeout with compact suffix")
        void appliesTimeoutCompact() {
            AimonCli cli = parse("--timeout", "45s");

            ExecutionBudget budget = cli.buildInitialBudget();

            assertThat(budget).isNotNull();
            assertThat(budget.getMaxWallClockDuration()).contains(Duration.ofSeconds(45));
        }

        @Test
        @DisplayName("applies --timeout with ISO-8601")
        void appliesTimeoutIso() {
            AimonCli cli = parse("--timeout", "PT5M");

            ExecutionBudget budget = cli.buildInitialBudget();

            assertThat(budget).isNotNull();
            assertThat(budget.getMaxWallClockDuration()).contains(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("combines all three dimensions")
        void combinesAllDimensions() {
            AimonCli cli = parse("--max-iterations", "20", "--max-tokens", "100000", "--timeout", "30s");

            ExecutionBudget budget = cli.buildInitialBudget();

            assertThat(budget).isNotNull();
            assertThat(budget.getMaxIterations()).contains(20);
            assertThat(budget.getMaxTokens()).contains(100_000);
            assertThat(budget.getMaxWallClockDuration()).contains(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("rejects zero max-iterations")
        void rejectsZeroMaxIterations() {
            AimonCli cli = parse("--max-iterations", "0");

            assertThatThrownBy(cli::buildInitialBudget).isInstanceOf(CliException.class)
                    .hasMessageContaining("--max-iterations must be >= 1");
        }

        @Test
        @DisplayName("rejects negative max-tokens")
        void rejectsNegativeMaxTokens() {
            AimonCli cli = parse("--max-tokens", "-10");

            assertThatThrownBy(cli::buildInitialBudget).isInstanceOf(CliException.class)
                    .hasMessageContaining("--max-tokens must be >= 1");
        }

        @Test
        @DisplayName("rejects invalid timeout with CliException")
        void rejectsInvalidTimeout() {
            AimonCli cli = parse("--timeout", "invalid");

            assertThatThrownBy(cli::buildInitialBudget).isInstanceOf(CliException.class)
                    .hasMessageContaining("Invalid --timeout");
        }

        @Test
        @DisplayName("rejects max-iterations that exceeds Integer.MAX_VALUE")
        void rejectsMaxIterationsOverflow() {
            AimonCli cli = parse("--max-iterations", "2147483648");

            assertThatThrownBy(cli::buildInitialBudget).isInstanceOf(CliException.class)
                    .hasMessageContaining("Invalid --max-iterations");
        }

        @Test
        @DisplayName("rejects non-numeric max-tokens")
        void rejectsNonNumericMaxTokens() {
            AimonCli cli = parse("--max-tokens", "abc");

            assertThatThrownBy(cli::buildInitialBudget).isInstanceOf(CliException.class)
                    .hasMessageContaining("Invalid --max-tokens");
        }

        @Test
        @DisplayName("blank max-iterations is treated as unset")
        void blankMaxIterationsTreatedAsUnset() {
            AimonCli cli = parse("--max-iterations", "   ");

            assertThat(cli.buildInitialBudget()).isNull();
        }

        @Test
        @DisplayName("rejects zero timeout")
        void rejectsZeroTimeout() {
            AimonCli cli = parse("--timeout", "0s");

            assertThatThrownBy(cli::buildInitialBudget).isInstanceOf(CliException.class)
                    .hasMessageContaining("must be positive");
        }

        @Test
        @DisplayName("blank timeout is treated as unset")
        void blankTimeoutTreatedAsUnset() {
            AimonCli cli = parse("--timeout", "   ");

            assertThat(cli.buildInitialBudget()).isNull();
        }
    }

    @Nested
    @DisplayName("streaming flag (PSTREAM-11)")
    class StreamingFlag {

        @Test
        @DisplayName("returns null when neither --streaming nor --no-streaming is supplied")
        void returnsNullWhenFlagUnset() {
            AimonCli cli = parse();

            assertThat(cli.streamingOverride()).isNull();
        }

        @Test
        @DisplayName("--streaming sets the override to TRUE")
        void streamingFlagSetsTrue() {
            AimonCli cli = parse("--streaming");

            assertThat(cli.streamingOverride()).isTrue();
        }

        @Test
        @DisplayName("--no-streaming sets the override to FALSE")
        void noStreamingFlagSetsFalse() {
            AimonCli cli = parse("--no-streaming");

            assertThat(cli.streamingOverride()).isFalse();
        }
    }

    private static AimonCli parse(String... args) {
        AimonCli cli = new AimonCli();
        new CommandLine(cli).parseArgs(args);
        return cli;
    }
}
