package at.aimon.cli.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;

@DisplayName("BudgetCommandHandler Tests")
class BudgetCommandHandlerTest {

    @Nested
    @DisplayName("show sub-command")
    class Show {

        @Test
        @DisplayName("reports unbounded state when constructed without a budget")
        void reportsUnboundedWithoutBudget() {
            BudgetCommandHandler handler = new BudgetCommandHandler(null);

            BudgetCommandHandler.Result result = handler.handle("");

            assertThat(result.isInfo()).isTrue();
            assertThat(result.getMessage()).contains("unbounded");
            assertThat(handler.getBudget()).isEmpty();
        }

        @Test
        @DisplayName("reports unbounded state when budget has no limits")
        void reportsUnboundedForUnlimitedBudget() {
            BudgetCommandHandler handler = new BudgetCommandHandler(ExecutionBudget.unlimited());

            BudgetCommandHandler.Result result = handler.handle("show");

            assertThat(result.isInfo()).isTrue();
            assertThat(result.getMessage()).contains("unbounded");
        }

        @Test
        @DisplayName("renders each configured dimension on show")
        void rendersConfiguredDimensions() {
            ExecutionBudget initial = ExecutionBudget.builder().maxIterations(20).maxTokens(100_000)
                    .maxWallClockDuration(Duration.ofSeconds(30)).build();
            BudgetCommandHandler handler = new BudgetCommandHandler(initial);

            BudgetCommandHandler.Result result = handler.handle("   ");

            assertThat(result.isInfo()).isTrue();
            assertThat(result.getMessage()).contains("max-iterations = 20").contains("max-tokens     = 100000")
                    .contains("timeout        = 30s");
        }
    }

    @Nested
    @DisplayName("max-iterations sub-command")
    class MaxIterations {

        @Test
        @DisplayName("updates session budget from unset state")
        void updatesFromUnset() {
            BudgetCommandHandler handler = new BudgetCommandHandler(null);

            BudgetCommandHandler.Result result = handler.handle("max-iterations 20");

            assertThat(result.isInfo()).isTrue();
            assertThat(result.getMessage()).contains("max-iterations = 20");
            assertThat(handler.getBudget()).isPresent();
            assertThat(handler.getBudget().get().getMaxIterations()).contains(20);
            assertThat(handler.getBudget().get().getMaxTokens()).isEmpty();
            assertThat(handler.getBudget().get().getMaxWallClockDuration()).isEmpty();
        }

        @Test
        @DisplayName("preserves other dimensions when overriding one")
        void preservesOtherDimensions() {
            ExecutionBudget initial = ExecutionBudget.builder().maxTokens(500).build();
            BudgetCommandHandler handler = new BudgetCommandHandler(initial);

            handler.handle("max-iterations 7");

            assertThat(handler.getBudget()).isPresent();
            assertThat(handler.getBudget().get().getMaxIterations()).contains(7);
            assertThat(handler.getBudget().get().getMaxTokens()).contains(500);
        }

        @Test
        @DisplayName("rejects missing value")
        void rejectsMissingValue() {
            BudgetCommandHandler handler = new BudgetCommandHandler(null);

            BudgetCommandHandler.Result result = handler.handle("max-iterations");

            assertThat(result.isError()).isTrue();
            assertThat(result.getMessage()).contains("requires a value");
            assertThat(handler.getBudget()).isEmpty();
        }

        @Test
        @DisplayName("rejects invalid value without mutating state")
        void rejectsInvalidValue() {
            ExecutionBudget initial = ExecutionBudget.builder().maxTokens(100).build();
            BudgetCommandHandler handler = new BudgetCommandHandler(initial);

            BudgetCommandHandler.Result result = handler.handle("max-iterations 0");

            assertThat(result.isError()).isTrue();
            assertThat(handler.getBudget()).contains(initial);
        }
    }

    @Nested
    @DisplayName("max-tokens sub-command")
    class MaxTokens {

        @Test
        @DisplayName("updates token budget")
        void updatesTokenBudget() {
            BudgetCommandHandler handler = new BudgetCommandHandler(null);

            BudgetCommandHandler.Result result = handler.handle("max-tokens 100000");

            assertThat(result.isInfo()).isTrue();
            assertThat(handler.getBudget()).isPresent();
            assertThat(handler.getBudget().get().getMaxTokens()).contains(100_000);
        }

        @Test
        @DisplayName("rejects non-numeric values")
        void rejectsNonNumeric() {
            BudgetCommandHandler handler = new BudgetCommandHandler(null);

            BudgetCommandHandler.Result result = handler.handle("max-tokens abc");

            assertThat(result.isError()).isTrue();
            assertThat(result.getMessage()).contains("Invalid max-tokens");
        }
    }

    @Nested
    @DisplayName("timeout sub-command")
    class Timeout {

        @Test
        @DisplayName("updates wall-clock timeout")
        void updatesTimeout() {
            BudgetCommandHandler handler = new BudgetCommandHandler(null);

            BudgetCommandHandler.Result result = handler.handle("timeout 30s");

            assertThat(result.isInfo()).isTrue();
            assertThat(handler.getBudget()).isPresent();
            assertThat(handler.getBudget().get().getMaxWallClockDuration()).contains(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("accepts ISO-8601 format")
        void acceptsIso8601() {
            BudgetCommandHandler handler = new BudgetCommandHandler(null);

            handler.handle("timeout PT5M");

            assertThat(handler.getBudget()).isPresent();
            assertThat(handler.getBudget().get().getMaxWallClockDuration()).contains(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("rejects missing value")
        void rejectsMissingValue() {
            BudgetCommandHandler handler = new BudgetCommandHandler(null);

            BudgetCommandHandler.Result result = handler.handle("timeout");

            assertThat(result.isError()).isTrue();
            assertThat(result.getMessage()).contains("requires a value");
        }

        @Test
        @DisplayName("rejects garbage value")
        void rejectsGarbageValue() {
            BudgetCommandHandler handler = new BudgetCommandHandler(null);

            BudgetCommandHandler.Result result = handler.handle("timeout abc");

            assertThat(result.isError()).isTrue();
            assertThat(result.getMessage()).contains("Invalid timeout");
        }
    }

    @Nested
    @DisplayName("clear sub-command")
    class Clear {

        @Test
        @DisplayName("removes every dimension and reports success")
        void clearsSession() {
            ExecutionBudget initial = ExecutionBudget.builder().maxIterations(5).maxTokens(1000).build();
            BudgetCommandHandler handler = new BudgetCommandHandler(initial);

            BudgetCommandHandler.Result result = handler.handle("clear");

            assertThat(result.isInfo()).isTrue();
            assertThat(result.getMessage()).contains("cleared");
            assertThat(handler.getBudget()).isEmpty();
        }

        @Test
        @DisplayName("clear on already-empty state is a no-op informational response")
        void clearIsIdempotent() {
            BudgetCommandHandler handler = new BudgetCommandHandler(null);

            BudgetCommandHandler.Result result = handler.handle("clear");

            assertThat(result.isInfo()).isTrue();
            assertThat(handler.getBudget()).isEmpty();
        }
    }

    @Nested
    @DisplayName("unknown sub-commands")
    class Unknown {

        @Test
        @DisplayName("returns error with usage hint")
        void returnsErrorForUnknownSubcommand() {
            BudgetCommandHandler handler = new BudgetCommandHandler(null);

            BudgetCommandHandler.Result result = handler.handle("bogus");

            assertThat(result.isError()).isTrue();
            assertThat(result.getMessage()).contains("Unknown").contains("Usage");
        }
    }

    @Nested
    @DisplayName("Result value object")
    class ResultValueObject {

        @Test
        @DisplayName("info factory returns INFO level")
        void infoFactoryReturnsInfoLevel() {
            BudgetCommandHandler.Result result = BudgetCommandHandler.Result.info("hello");

            assertThat(result.getLevel()).isEqualTo(BudgetCommandHandler.Level.INFO);
            assertThat(result.getMessage()).isEqualTo("hello");
            assertThat(result.isInfo()).isTrue();
            assertThat(result.isError()).isFalse();
        }

        @Test
        @DisplayName("error factory returns ERROR level")
        void errorFactoryReturnsErrorLevel() {
            BudgetCommandHandler.Result result = BudgetCommandHandler.Result.error("bad");

            assertThat(result.getLevel()).isEqualTo(BudgetCommandHandler.Level.ERROR);
            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("equals/hashCode/toString are sensible")
        void valueEqualitySemantics() {
            BudgetCommandHandler.Result a = BudgetCommandHandler.Result.info("x");
            BudgetCommandHandler.Result b = BudgetCommandHandler.Result.info("x");
            BudgetCommandHandler.Result c = BudgetCommandHandler.Result.error("x");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
            assertThat(a.toString()).contains("INFO").contains("x");
        }
    }
}
