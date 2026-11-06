package at.aimon.core.programmatic;

import java.util.Objects;
import java.util.Optional;

/**
 * What an engine reports after running one program ({@code docs/design/tool/programmatic-tool-calling.md} §9, §12.1).
 *
 * <p>
 * Standard output is the observation; standard error is the log. A JavaScript program's return value, when it is not
 * {@code undefined}, is reported apart so the observation can append it after the output (decision 7). The record of
 * nested calls is kept by the bridge, which made them, not here.
 *
 * <p>
 * Immutable. Use {@link #builder()}.
 */
public final class CodeExecutionOutcome {

    private final CodeExecutionStatus status;
    private final String stdout;
    private final String logs;
    private final String returnValue;
    private final String errorMessage;

    private CodeExecutionOutcome(Builder builder) {
        this.status = Objects.requireNonNull(builder.status, "status cannot be null");
        this.stdout = Objects.requireNonNull(builder.stdout, "stdout cannot be null");
        this.logs = Objects.requireNonNull(builder.logs, "logs cannot be null");
        this.returnValue = builder.returnValue;
        this.errorMessage = builder.errorMessage;
    }

    /**
     * Returns a new builder. Output and logs default to empty.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns how the program ended.
     *
     * @return the status (never null)
     */
    public CodeExecutionStatus getStatus() {
        return status;
    }

    /**
     * Returns what the program wrote to standard output, possibly already capped by the engine.
     *
     * @return the output (never null)
     */
    public String getStdout() {
        return stdout;
    }

    /**
     * Returns what the program wrote to standard error, possibly already capped by the engine.
     *
     * @return the logs (never null)
     */
    public String getLogs() {
        return logs;
    }

    /**
     * Returns the program's return value rendered as text (JavaScript only), when it was not {@code undefined}.
     *
     * @return the return value, empty when there is none
     */
    public Optional<String> getReturnValue() {
        return Optional.ofNullable(returnValue);
    }

    /**
     * Returns the message of a status other than {@link CodeExecutionStatus#COMPLETED} — the script error, the limit
     * that was crossed.
     *
     * @return the message, empty when there is none
     */
    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    @Override
    public String toString() {
        return "CodeExecutionOutcome{status=" + status + ", stdout=" + stdout.length() + " chars, logs=" + logs.length()
                + " chars" + (errorMessage == null ? "" : ", error='" + errorMessage + '\'') + '}';
    }

    /** Builder for {@link CodeExecutionOutcome}. */
    public static final class Builder {

        private CodeExecutionStatus status;
        private String stdout = "";
        private String logs = "";
        private String returnValue;
        private String errorMessage;

        private Builder() {
        }

        /**
         * @param status
         *            how the program ended (must not be null)
         * @return this builder
         */
        public Builder status(CodeExecutionStatus status) {
            this.status = status;
            return this;
        }

        /**
         * @param stdout
         *            the standard output (must not be null)
         * @return this builder
         */
        public Builder stdout(String stdout) {
            this.stdout = stdout;
            return this;
        }

        /**
         * @param logs
         *            the standard error (must not be null)
         * @return this builder
         */
        public Builder logs(String logs) {
            this.logs = logs;
            return this;
        }

        /**
         * @param returnValue
         *            the rendered return value, or {@code null} for none
         * @return this builder
         */
        public Builder returnValue(String returnValue) {
            this.returnValue = returnValue;
            return this;
        }

        /**
         * @param errorMessage
         *            the message of a non-completed status, or {@code null}
         * @return this builder
         */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /**
         * @return the outcome
         * @throws NullPointerException
         *             if the status is missing
         */
        public CodeExecutionOutcome build() {
            return new CodeExecutionOutcome(this);
        }
    }
}
