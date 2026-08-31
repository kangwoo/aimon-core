package at.aimon.core.agent.budget;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.cost.Money;

/**
 * Accumulates per-execution counters and decides when an {@link ExecutionBudget} dimension has been exhausted.
 *
 * <p>
 * Not thread-safe. A tracker is meant to be confined to a single ReAct loop execution and accessed sequentially by the
 * loop driver. The tracker owns:
 *
 * <ul>
 * <li><b>iteration count</b> — incremented via {@link #recordIteration()} just before each iteration runs.
 * <li><b>token usage</b> — accumulated via {@link #recordTokens(TokenUsage)} after each LLM response.
 * <li><b>cost</b> — accumulated via {@link #recordCost(Money)} after each LLM response; stays at zero unless a
 * cost estimator feeds it.
 * <li><b>wall-clock start</b> — captured at construction time via the supplied {@link Clock}; {@link #elapsed()}
 * derives the elapsed duration on demand.
 * </ul>
 *
 * <p>
 * The decision method {@link #check()} compares the current counters against the configured budget and returns a
 * {@link BudgetDecision}. When it returns {@link BudgetDecision#STOP} the matching {@link CompletionReason} is exposed
 * via {@link #getStopReason()} so callers can finalise the execution with a consistent completion reason.
 *
 * <p>
 * When a budget dimension is not set, that dimension is skipped — an {@link ExecutionBudget#unlimited()} budget always
 * yields {@link BudgetDecision#CONTINUE}.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     BudgetTracker tracker = new BudgetTracker(budget);
 *     while (true) {
 *         if (tracker.check() == BudgetDecision.STOP) {
 *             CompletionReason reason = tracker.getStopReason().orElse(CompletionReason.ERROR);
 *             // finalise execution with reason
 *             break;
 *         }
 *         tracker.recordIteration();
 *         LlmResponse response = llmClient.send(...);
 *         tracker.recordTokens(response.getTokenUsage());
 *     }
 * }
 * </pre>
 */
public final class BudgetTracker {

    private final ExecutionBudget budget;
    private final Clock clock;
    private final Instant startedAt;

    private int iterations;
    private TokenUsage tokens = TokenUsage.empty();
    private Money cost = Money.zeroUsd();

    /**
     * Creates a tracker using {@link Clock#systemUTC()}.
     *
     * @param budget
     *            the budget (never null — callers pass {@link ExecutionBudget#unlimited()} for "no limit")
     * @throws NullPointerException
     *             if {@code budget} is null
     */
    public BudgetTracker(ExecutionBudget budget) {
        this(budget, Clock.systemUTC());
    }

    /**
     * Creates a tracker with an injectable clock (useful for deterministic tests).
     *
     * @param budget
     *            the budget (never null)
     * @param clock
     *            the clock used to stamp the start and compute {@link #elapsed()}
     * @throws NullPointerException
     *             if any argument is null
     */
    public BudgetTracker(ExecutionBudget budget, Clock clock) {
        this.budget = Objects.requireNonNull(budget, "Budget cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        this.startedAt = clock.instant();
    }

    /**
     * Registers that a new iteration is about to start. Must be called before each iteration's body runs so the counter
     * reflects the iteration that is currently executing.
     */
    public void recordIteration() {
        iterations++;
    }

    /**
     * Accumulates the given token usage into the running total.
     *
     * @param usage
     *            the usage reported by the LLM call (never null — pass {@link TokenUsage#empty()} for calls without
     *            usage data)
     * @throws NullPointerException
     *             if {@code usage} is null
     */
    public void recordTokens(TokenUsage usage) {
        Objects.requireNonNull(usage, "Token usage cannot be null");
        tokens = tokens.add(usage);
    }

    /**
     * Accumulates the given estimated cost into the running total. Only meaningful when a cost estimator is wired
     * into the executor; otherwise callers pass {@link Money#zeroUsd()} and the cost dimension never trips.
     *
     * @param additionalCost
     *            the estimated cost of the most recent LLM call — must be a USD amount (never null; pass
     *            {@link Money#zeroUsd()} for un-priced calls)
     * @throws NullPointerException
     *             if {@code additionalCost} is null
     * @throws IllegalArgumentException
     *             if {@code additionalCost} is not denominated in USD
     */
    public void recordCost(Money additionalCost) {
        Objects.requireNonNull(additionalCost, "Cost cannot be null");
        if (!Money.USD.equals(additionalCost.getCurrency())) {
            throw new IllegalArgumentException("Cost must be denominated in USD, got: " + additionalCost);
        }
        cost = cost.add(additionalCost);
    }

    /**
     * @return the number of iterations registered so far
     */
    public int iterations() {
        return iterations;
    }

    /**
     * @return the accumulated token usage (never null; zero counts when nothing recorded)
     */
    public TokenUsage tokens() {
        return tokens;
    }

    /**
     * @return the accumulated estimated cost (never null; {@link Money#zeroUsd()} when nothing recorded)
     */
    public Money cost() {
        return cost;
    }

    /**
     * @return the duration from tracker construction until {@code now}, derived from the injected clock
     */
    public Duration elapsed() {
        return Duration.between(startedAt, clock.instant());
    }

    /**
     * @return the budget this tracker enforces
     */
    public ExecutionBudget getBudget() {
        return budget;
    }

    /**
     * Evaluates the current counters against the budget and returns the appropriate decision. Pure — repeated calls
     * with unchanged state produce the same result.
     *
     * <p>
     * Priority when multiple dimensions would STOP simultaneously: iterations → tokens → cost → wall-clock.
     *
     * @return the decision (never null)
     */
    public BudgetDecision check() {
        // Hard STOP dimensions first — each guards on its own presence, so an unset dimension is skipped and a fully
        // unlimited (hard) budget simply falls through to the soft compaction check below.
        final Optional<Integer> maxIterations = budget.getMaxIterations();
        if (maxIterations.isPresent() && iterations >= maxIterations.get()) {
            return BudgetDecision.STOP;
        }

        final Optional<Integer> maxTokens = budget.getMaxTokens();
        if (maxTokens.isPresent() && tokens.getTotalTokens() >= maxTokens.get()) {
            return BudgetDecision.STOP;
        }

        final Optional<Money> maxCost = budget.getMaxCostUsd();
        if (maxCost.isPresent() && cost.compareTo(maxCost.get()) >= 0) {
            return BudgetDecision.STOP;
        }

        final Optional<Duration> maxDuration = budget.getMaxWallClockDuration();
        if (maxDuration.isPresent() && elapsed().compareTo(maxDuration.get()) >= 0) {
            return BudgetDecision.STOP;
        }

        // Soft compaction hint — evaluated only after every hard-STOP dimension has passed, so STOP always wins.
        // Opt-in: absent threshold means this never fires and the decision stays CONTINUE (no behavioural change).
        final Optional<Integer> compactionThreshold = budget.getCompactionTokenThreshold();
        if (compactionThreshold.isPresent() && tokens.getTotalTokens() >= compactionThreshold.get()) {
            return BudgetDecision.SHOULD_COMPACT;
        }

        return BudgetDecision.CONTINUE;
    }

    /**
     * Returns the {@link CompletionReason} that matches the current STOP condition, if any.
     *
     * <p>
     * The dimension priority mirrors {@link #check()}. If the tracker is not currently in a STOP state, this returns an
     * empty optional.
     *
     * @return the reason that caused (or would cause) {@link BudgetDecision#STOP}, or empty if none
     */
    public Optional<CompletionReason> getStopReason() {
        if (budget.isUnlimited()) {
            return Optional.empty();
        }

        final Optional<Integer> maxIterations = budget.getMaxIterations();
        if (maxIterations.isPresent() && iterations >= maxIterations.get()) {
            return Optional.of(CompletionReason.MAX_ITERATIONS);
        }

        final Optional<Integer> maxTokens = budget.getMaxTokens();
        if (maxTokens.isPresent() && tokens.getTotalTokens() >= maxTokens.get()) {
            return Optional.of(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        }

        final Optional<Money> maxCost = budget.getMaxCostUsd();
        if (maxCost.isPresent() && cost.compareTo(maxCost.get()) >= 0) {
            return Optional.of(CompletionReason.COST_BUDGET_EXCEEDED);
        }

        final Optional<Duration> maxDuration = budget.getMaxWallClockDuration();
        if (maxDuration.isPresent() && elapsed().compareTo(maxDuration.get()) >= 0) {
            return Optional.of(CompletionReason.WALL_CLOCK_EXCEEDED);
        }

        return Optional.empty();
    }
}
