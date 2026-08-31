package at.aimon.cli.budget;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.budget.ExecutionBudget;

/**
 * Processes {@code /budget} slash-command invocations on behalf of the REPL.
 *
 * <p>
 * Handler instances are stateful — they own the current session default {@link ExecutionBudget} and mutate it in
 * response to {@code show}, {@code set}, and {@code clear} sub-commands. The REPL is expected to consult
 * {@link #getBudget()} when assembling outgoing agent requests.
 *
 * <p>
 * This class has no direct dependency on the REPL or terminal — commands return a structured {@link Result} so that
 * presentation concerns stay in the caller and unit tests can assert behaviour without capturing stdout.
 */
public final class BudgetCommandHandler {

    /** Sub-command name that prints the current budget. */
    public static final String SUBCOMMAND_SHOW = "show";
    /** Sub-command name that sets {@code maxIterations}. */
    public static final String SUBCOMMAND_MAX_ITERATIONS = "max-iterations";
    /** Sub-command name that sets {@code maxTokens}. */
    public static final String SUBCOMMAND_MAX_TOKENS = "max-tokens";
    /** Sub-command name that sets the wall-clock timeout. */
    public static final String SUBCOMMAND_TIMEOUT = "timeout";
    /** Sub-command name that removes every limit (returns to unbounded execution). */
    public static final String SUBCOMMAND_CLEAR = "clear";

    private ExecutionBudget budget;

    /**
     * Creates a handler seeded with the given initial budget.
     *
     * @param initialBudget
     *            the session default; may be {@code null} to start unbounded
     */
    public BudgetCommandHandler(ExecutionBudget initialBudget) {
        this.budget = initialBudget;
    }

    /**
     * @return the current session budget, or {@link Optional#empty()} when no limits are set
     */
    public Optional<ExecutionBudget> getBudget() {
        return Optional.ofNullable(budget);
    }

    /**
     * Handles a parsed {@code /budget ...} invocation.
     *
     * <p>
     * Accepts the argument portion of the command (everything after {@code /budget}). A {@code null} or empty argument
     * behaves the same as {@link #SUBCOMMAND_SHOW}.
     *
     * @param arguments
     *            the command arguments (may be null)
     * @return a non-null {@link Result} describing how the REPL should render the outcome
     */
    public Result handle(String arguments) {
        final String trimmed = arguments == null ? "" : arguments.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase(SUBCOMMAND_SHOW)) {
            return Result.info(formatCurrent());
        }

        final String[] tokens = trimmed.split("\\s+", 2);
        final String subcommand = tokens[0].toLowerCase(Locale.ROOT);
        final String value = tokens.length > 1 ? tokens[1].trim() : "";

        return switch (subcommand) {
            case SUBCOMMAND_CLEAR -> clear();
            case SUBCOMMAND_MAX_ITERATIONS -> setMaxIterations(value);
            case SUBCOMMAND_MAX_TOKENS -> setMaxTokens(value);
            case SUBCOMMAND_TIMEOUT -> setTimeout(value);
            default -> Result.error("Unknown /budget sub-command: '" + subcommand + "'. " + usage());
        };
    }

    private Result clear() {
        this.budget = null;
        return Result.info("Session budget cleared (unbounded execution).");
    }

    private Result setMaxIterations(String raw) {
        if (raw.isEmpty()) {
            return Result.error("/budget max-iterations requires a value, e.g. /budget max-iterations 20");
        }
        try {
            final int value = BudgetOptionParser.parsePositiveInt(raw, "max-iterations");
            this.budget = copyBuilder(budget).maxIterations(value).build();
            return Result.info("Session budget updated: max-iterations = " + value);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    private Result setMaxTokens(String raw) {
        if (raw.isEmpty()) {
            return Result.error("/budget max-tokens requires a value, e.g. /budget max-tokens 100000");
        }
        try {
            final int value = BudgetOptionParser.parsePositiveInt(raw, "max-tokens");
            this.budget = copyBuilder(budget).maxTokens(value).build();
            return Result.info("Session budget updated: max-tokens = " + value);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    private Result setTimeout(String raw) {
        if (raw.isEmpty()) {
            return Result.error("/budget timeout requires a value, e.g. /budget timeout 30s");
        }
        try {
            final Duration duration = BudgetOptionParser.parseDuration(raw, "timeout");
            this.budget = copyBuilder(budget).maxWallClockDuration(duration).build();
            return Result.info("Session budget updated: timeout = " + formatDuration(duration));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    private String formatCurrent() {
        if (budget == null || budget.isUnlimited()) {
            return "Session budget: unbounded. " + usage();
        }
        final StringBuilder sb = new StringBuilder("Session budget:");
        budget.getMaxIterations().ifPresent(v -> sb.append("\n  max-iterations = ").append(v));
        budget.getMaxTokens().ifPresent(v -> sb.append("\n  max-tokens     = ").append(v));
        budget.getMaxWallClockDuration().ifPresent(v -> sb.append("\n  timeout        = ").append(formatDuration(v)));
        return sb.toString();
    }

    private static ExecutionBudget.Builder copyBuilder(ExecutionBudget source) {
        final ExecutionBudget.Builder builder = ExecutionBudget.builder();
        if (source != null) {
            source.getMaxIterations().ifPresent(builder::maxIterations);
            source.getMaxTokens().ifPresent(builder::maxTokens);
            source.getMaxWallClockDuration().ifPresent(builder::maxWallClockDuration);
        }
        return builder;
    }

    private static String usage() {
        return "Usage: /budget [show|max-iterations <N>|max-tokens <N>|timeout <duration>|clear]";
    }

    private static String formatDuration(Duration duration) {
        final long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        if (millis % 60_000 == 0) {
            return (millis / 60_000) + "m";
        }
        if (millis % 1000 == 0) {
            return (millis / 1000) + "s";
        }
        return duration.toString();
    }

    /**
     * Level of the message returned by {@link BudgetCommandHandler#handle(String)}.
     */
    public enum Level {
        /** Informational output — normally rendered via {@code displayInfo}. */
        INFO,
        /** Error output — normally rendered via {@code displayError}. */
        ERROR
    }

    /**
     * Immutable outcome returned from {@link BudgetCommandHandler#handle(String)}.
     */
    public static final class Result {

        /**
         * @param message
         *            informational message (must not be null)
         * @return a new {@link Level#INFO} result
         */
        public static Result info(String message) {
            return new Result(Level.INFO, Objects.requireNonNull(message, "message cannot be null"));
        }

        /**
         * @param message
         *            error message (must not be null)
         * @return a new {@link Level#ERROR} result
         */
        public static Result error(String message) {
            return new Result(Level.ERROR, Objects.requireNonNull(message, "message cannot be null"));
        }

        private final Level level;
        private final String message;

        private Result(Level level, String message) {
            this.level = level;
            this.message = message;
        }

        /**
         * @return the result level
         */
        public Level getLevel() {
            return level;
        }

        /**
         * @return the rendered message
         */
        public String getMessage() {
            return message;
        }

        /**
         * @return true if {@link #getLevel()} is {@link Level#INFO}
         */
        public boolean isInfo() {
            return level == Level.INFO;
        }

        /**
         * @return true if {@link #getLevel()} is {@link Level#ERROR}
         */
        public boolean isError() {
            return level == Level.ERROR;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Result other)) {
                return false;
            }
            return level == other.level && message.equals(other.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(level, message);
        }

        @Override
        public String toString() {
            return "Result{" + "level=" + level + ", message='" + message + '\'' + '}';
        }
    }
}
