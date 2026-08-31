package at.aimon.core.agent.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.cost.Money;

@DisplayName("BudgetTracker Tests")
class BudgetTrackerTest {

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneId.of("UTC"));
    }

    private static Clock mutableClock(AtomicReference<Instant> ref) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return ref.get();
            }
        };
    }

    @Nested
    @DisplayName("Unlimited budget")
    class UnlimitedBudget {

        @Test
        @DisplayName("check() returns CONTINUE regardless of state")
        void alwaysContinue() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.unlimited());
            tracker.recordIteration();
            tracker.recordIteration();
            tracker.recordTokens(TokenUsage.of(1_000_000, 500_000, 1_500_000));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.CONTINUE);
            assertThat(tracker.getStopReason()).isEmpty();
        }
    }

    @Nested
    @DisplayName("maxIterations dimension")
    class IterationsDimension {

        @Test
        @DisplayName("STOP exactly when iterations == maxIterations (boundary)")
        void stopAtBoundary() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().maxIterations(1).build());

            assertThat(tracker.check()).isEqualTo(BudgetDecision.CONTINUE);
            tracker.recordIteration();
            assertThat(tracker.check()).isEqualTo(BudgetDecision.STOP);
            assertThat(tracker.getStopReason()).contains(CompletionReason.MAX_ITERATIONS);
        }

        @Test
        @DisplayName("CONTINUE while iterations < maxIterations")
        void continueBelow() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().maxIterations(3).build());
            tracker.recordIteration();
            tracker.recordIteration();

            assertThat(tracker.iterations()).isEqualTo(2);
            assertThat(tracker.check()).isEqualTo(BudgetDecision.CONTINUE);
        }

        @Test
        @DisplayName("STOP when iterations exceed maxIterations")
        void stopWhenExceeded() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().maxIterations(2).build());
            tracker.recordIteration();
            tracker.recordIteration();
            tracker.recordIteration();

            assertThat(tracker.check()).isEqualTo(BudgetDecision.STOP);
            assertThat(tracker.getStopReason()).contains(CompletionReason.MAX_ITERATIONS);
        }
    }

    @Nested
    @DisplayName("maxTokens dimension")
    class TokensDimension {

        @Test
        @DisplayName("CONTINUE when no tokens recorded")
        void continueAtZero() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().maxTokens(100).build());

            assertThat(tracker.check()).isEqualTo(BudgetDecision.CONTINUE);
        }

        @Test
        @DisplayName("STOP at the exact boundary")
        void stopAtBoundary() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().maxTokens(100).build());
            tracker.recordTokens(TokenUsage.of(60, 40, 100));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.STOP);
            assertThat(tracker.getStopReason()).contains(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        }

        @Test
        @DisplayName("Usage accumulates across recordings")
        void usageAccumulates() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().maxTokens(200).build());
            tracker.recordTokens(TokenUsage.of(50, 30, 80));
            tracker.recordTokens(TokenUsage.of(70, 60, 130));

            assertThat(tracker.tokens().getTotalTokens()).isEqualTo(210);
            assertThat(tracker.check()).isEqualTo(BudgetDecision.STOP);
            assertThat(tracker.getStopReason()).contains(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        }

        @Test
        @DisplayName("recordTokens rejects null")
        void rejectNull() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.unlimited());
            assertThatThrownBy(() -> tracker.recordTokens(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("maxCostUsd dimension")
    class CostDimension {

        @Test
        @DisplayName("CONTINUE when no cost recorded")
        void continueAtZero() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().maxCostUsd(Money.usd(1.00)).build());

            assertThat(tracker.check()).isEqualTo(BudgetDecision.CONTINUE);
        }

        @Test
        @DisplayName("STOP at the exact cost boundary with COST_BUDGET_EXCEEDED")
        void stopAtBoundary() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().maxCostUsd(Money.usd(1.00)).build());
            tracker.recordCost(Money.usd(1.00));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.STOP);
            assertThat(tracker.getStopReason()).contains(CompletionReason.COST_BUDGET_EXCEEDED);
        }

        @Test
        @DisplayName("cost accumulates across recordings")
        void costAccumulates() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().maxCostUsd(Money.usd(1.00)).build());
            tracker.recordCost(Money.usd(0.40));
            tracker.recordCost(Money.usd(0.70));

            assertThat(tracker.cost().getAmount()).isEqualByComparingTo("1.10");
            assertThat(tracker.check()).isEqualTo(BudgetDecision.STOP);
            assertThat(tracker.getStopReason()).contains(CompletionReason.COST_BUDGET_EXCEEDED);
        }

        @Test
        @DisplayName("CONTINUE while cost stays below the ceiling")
        void continueBelowCeiling() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().maxCostUsd(Money.usd(1.00)).build());
            tracker.recordCost(Money.usd(0.99));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.CONTINUE);
        }

        @Test
        @DisplayName("recordCost rejects null")
        void rejectNull() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.unlimited());
            assertThatThrownBy(() -> tracker.recordCost(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("recordCost rejects a non-USD amount")
        void rejectNonUsd() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.unlimited());
            assertThatThrownBy(() -> tracker.recordCost(Money.of(java.math.BigDecimal.ONE, "EUR")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("USD");
        }

        @Test
        @DisplayName("initial cost is zero USD")
        void initialCostZero() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.unlimited());

            assertThat(tracker.cost().isZero()).isTrue();
            assertThat(tracker.cost().getCurrency()).isEqualTo(Money.USD);
        }
    }

    @Nested
    @DisplayName("maxWallClockDuration dimension")
    class DurationDimension {

        @Test
        @DisplayName("CONTINUE before duration elapses")
        void continueBefore() {
            Instant start = Instant.parse("2026-01-01T00:00:00Z");
            AtomicReference<Instant> now = new AtomicReference<>(start);
            ExecutionBudget budget = ExecutionBudget.builder().maxWallClockDuration(Duration.ofSeconds(10)).build();
            BudgetTracker tracker = new BudgetTracker(budget, mutableClock(now));

            now.set(start.plusSeconds(5));

            assertThat(tracker.elapsed()).isEqualTo(Duration.ofSeconds(5));
            assertThat(tracker.check()).isEqualTo(BudgetDecision.CONTINUE);
        }

        @Test
        @DisplayName("STOP at the exact duration boundary")
        void stopAtBoundary() {
            Instant start = Instant.parse("2026-01-01T00:00:00Z");
            AtomicReference<Instant> now = new AtomicReference<>(start);
            ExecutionBudget budget = ExecutionBudget.builder().maxWallClockDuration(Duration.ofSeconds(10)).build();
            BudgetTracker tracker = new BudgetTracker(budget, mutableClock(now));

            now.set(start.plusSeconds(10));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.STOP);
            assertThat(tracker.getStopReason()).contains(CompletionReason.WALL_CLOCK_EXCEEDED);
        }

        @Test
        @DisplayName("STOP past the duration boundary")
        void stopPastBoundary() {
            Instant start = Instant.parse("2026-01-01T00:00:00Z");
            AtomicReference<Instant> now = new AtomicReference<>(start);
            ExecutionBudget budget = ExecutionBudget.builder().maxWallClockDuration(Duration.ofMillis(500)).build();
            BudgetTracker tracker = new BudgetTracker(budget, mutableClock(now));

            now.set(start.plusSeconds(1));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.STOP);
            assertThat(tracker.getStopReason()).contains(CompletionReason.WALL_CLOCK_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("compactionTokenThreshold dimension")
    class CompactionTokenThresholdDimension {

        @Test
        @DisplayName("SHOULD_COMPACT at the exact boundary, and getStopReason() stays empty")
        void shouldCompactAtBoundary() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().compactionTokenThreshold(100).build());
            tracker.recordTokens(TokenUsage.of(60, 40, 100));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.SHOULD_COMPACT);
            assertThat(tracker.getStopReason()).isEmpty();
        }

        @Test
        @DisplayName("SHOULD_COMPACT past the boundary")
        void shouldCompactPastBoundary() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().compactionTokenThreshold(100).build());
            tracker.recordTokens(TokenUsage.of(90, 60, 150));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.SHOULD_COMPACT);
            assertThat(tracker.getStopReason()).isEmpty();
        }

        @Test
        @DisplayName("CONTINUE just below the boundary")
        void continueBelowBoundary() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.builder().compactionTokenThreshold(100).build());
            tracker.recordTokens(TokenUsage.of(50, 49, 99));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.CONTINUE);
        }

        @Test
        @DisplayName("no threshold set never yields SHOULD_COMPACT")
        void noThresholdNeverCompacts() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.unlimited());
            tracker.recordTokens(TokenUsage.of(1_000_000, 500_000, 1_500_000));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.CONTINUE);
        }

        @Test
        @DisplayName("STOP wins over SHOULD_COMPACT when maxTokens is also reached")
        void hardTokenStopWinsOverCompactionHint() {
            BudgetTracker tracker = new BudgetTracker(
                    ExecutionBudget.builder().maxTokens(50).compactionTokenThreshold(10).build());
            tracker.recordTokens(TokenUsage.of(60, 40, 100));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.STOP);
            assertThat(tracker.getStopReason()).contains(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        }

        @Test
        @DisplayName("STOP wins over SHOULD_COMPACT when maxIterations is also reached")
        void hardIterationStopWinsOverCompactionHint() {
            BudgetTracker tracker = new BudgetTracker(
                    ExecutionBudget.builder().maxIterations(1).compactionTokenThreshold(10).build());
            tracker.recordIteration();
            tracker.recordTokens(TokenUsage.of(60, 40, 100));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.STOP);
            assertThat(tracker.getStopReason()).contains(CompletionReason.MAX_ITERATIONS);
        }

        @Test
        @DisplayName("SHOULD_COMPACT when compaction threshold reached but hard maxTokens not yet reached")
        void compactsBeforeHardTokenLimitReached() {
            BudgetTracker tracker = new BudgetTracker(
                    ExecutionBudget.builder().maxTokens(1000).compactionTokenThreshold(100).build());
            tracker.recordTokens(TokenUsage.of(60, 40, 100));

            assertThat(tracker.check()).isEqualTo(BudgetDecision.SHOULD_COMPACT);
            assertThat(tracker.getStopReason()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Dimension priority")
    class DimensionPriority {

        @Test
        @DisplayName("iterations wins over tokens when both would STOP")
        void iterationsBeatsTokens() {
            BudgetTracker tracker = new BudgetTracker(
                    ExecutionBudget.builder().maxIterations(1).maxTokens(100).build());
            tracker.recordIteration();
            tracker.recordTokens(TokenUsage.of(60, 40, 100));

            assertThat(tracker.getStopReason()).contains(CompletionReason.MAX_ITERATIONS);
        }

        @Test
        @DisplayName("tokens wins over wall-clock when both would STOP but iterations still OK")
        void tokensBeatsWallClock() {
            Instant start = Instant.parse("2026-01-01T00:00:00Z");
            AtomicReference<Instant> now = new AtomicReference<>(start);
            ExecutionBudget budget = ExecutionBudget.builder().maxIterations(10).maxTokens(50)
                    .maxWallClockDuration(Duration.ofSeconds(1)).build();
            BudgetTracker tracker = new BudgetTracker(budget, mutableClock(now));
            tracker.recordTokens(TokenUsage.of(30, 25, 55));
            now.set(start.plusSeconds(2));

            assertThat(tracker.getStopReason()).contains(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        }

        @Test
        @DisplayName("tokens wins over cost when both would STOP")
        void tokensBeatsCost() {
            BudgetTracker tracker = new BudgetTracker(
                    ExecutionBudget.builder().maxTokens(50).maxCostUsd(Money.usd(0.50)).build());
            tracker.recordTokens(TokenUsage.of(30, 25, 55));
            tracker.recordCost(Money.usd(1.00));

            assertThat(tracker.getStopReason()).contains(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        }

        @Test
        @DisplayName("cost wins over wall-clock when both would STOP but tokens still OK")
        void costBeatsWallClock() {
            Instant start = Instant.parse("2026-01-01T00:00:00Z");
            AtomicReference<Instant> now = new AtomicReference<>(start);
            ExecutionBudget budget = ExecutionBudget.builder().maxCostUsd(Money.usd(0.50))
                    .maxWallClockDuration(Duration.ofSeconds(1)).build();
            BudgetTracker tracker = new BudgetTracker(budget, mutableClock(now));
            tracker.recordCost(Money.usd(1.00));
            now.set(start.plusSeconds(2));

            assertThat(tracker.getStopReason()).contains(CompletionReason.COST_BUDGET_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("Constructor contract")
    class ConstructorContract {

        @Test
        @DisplayName("rejects null budget")
        void rejectNullBudget() {
            assertThatThrownBy(() -> new BudgetTracker(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null clock")
        void rejectNullClock() {
            assertThatThrownBy(() -> new BudgetTracker(ExecutionBudget.unlimited(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("initial state reports zero counters")
        void initialState() {
            BudgetTracker tracker = new BudgetTracker(ExecutionBudget.unlimited(),
                    fixedClock(Instant.parse("2026-01-01T00:00:00Z")));

            assertThat(tracker.iterations()).isZero();
            assertThat(tracker.tokens()).isEqualTo(TokenUsage.empty());
            assertThat(tracker.elapsed()).isZero();
            assertThat(tracker.getBudget().isUnlimited()).isTrue();
        }
    }
}
