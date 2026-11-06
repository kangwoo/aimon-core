package at.aimon.core.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one {@link ToolCallDispatcher#dispatch} returns.
 *
 * <p>
 * The tool's text and the side channels are kept apart: the environment's {@linkplain #getNotices() notices} and the
 * hooks' {@linkplain #getHookFeedback() advisory feedback} are lists here, not text already spliced into
 * {@link #getText()}. On the direct path the loop splices them in when it turns the result into the
 * {@code ToolUseResult} it records in the transcript, once; another consumer, such as a program that called the tool,
 * can collect them separately instead — which it could not do once they were joined.
 *
 * <p>
 * Immutable. Use {@link #builder()}.
 */
public final class ToolDispatchResult {

    private final String toolUseId;
    private final String text;
    private final List<String> notices;
    private final List<String> hookFeedback;
    private final boolean error;
    private final boolean retryable;
    private final Map<String, Object> renderPayload;

    private ToolDispatchResult(Builder builder) {
        this.toolUseId = Objects.requireNonNull(builder.toolUseId, "toolUseId cannot be null");
        this.text = Objects.requireNonNull(builder.text, "text cannot be null");
        this.notices = builder.notices == null ? List.of() : List.copyOf(builder.notices);
        this.hookFeedback = builder.hookFeedback == null ? List.of() : List.copyOf(builder.hookFeedback);
        this.error = builder.error;
        // A success is always retryable, as on ToolUseResult: the flag only says something about a failure.
        this.retryable = builder.retryable || !builder.error;
        this.renderPayload = builder.renderPayload == null ? null : Map.copyOf(builder.renderPayload);
    }

    /**
     * Creates a new builder. {@code retryable} defaults to {@code true}.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the id of the call this result answers.
     *
     * @return the tool-use id (never null)
     */
    public String getToolUseId() {
        return toolUseId;
    }

    /**
     * Returns the text result: what the tool returned, or the error, after any PostTool hook rewrote it.
     *
     * @return the text (never null)
     */
    public String getText() {
        return text;
    }

    /**
     * Returns the execution environment's notices drained after the call, one entry each.
     *
     * @return the notices (never null, possibly empty)
     */
    public List<String> getNotices() {
        return notices;
    }

    /**
     * Returns the advisory feedback the permission, PreTool and PostTool hook chains returned for this call.
     *
     * @return the feedback (never null, possibly empty)
     */
    public List<String> getHookFeedback() {
        return hookFeedback;
    }

    /**
     * Returns whether the call failed — denied, unknown, blocked, or failed while running.
     *
     * @return {@code true} for an error result
     */
    public boolean isError() {
        return error;
    }

    /**
     * Returns whether a failed call is worth repeating. Always {@code true} for a success.
     *
     * @return the retryable flag
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Returns the structured payload for renderers, invisible to the model.
     *
     * @return the payload, or empty when the tool produced none
     */
    public Optional<Map<String, Object>> getRenderPayload() {
        return Optional.ofNullable(renderPayload);
    }

    @Override
    public String toString() {
        return "ToolDispatchResult{toolUseId='" + toolUseId + "', error=" + error + ", retryable=" + retryable
                + ", notices=" + notices.size() + ", hookFeedback=" + hookFeedback.size() + '}';
    }

    /** Builder for {@link ToolDispatchResult}. */
    public static final class Builder {

        private String toolUseId;
        private String text;
        private List<String> notices;
        private List<String> hookFeedback;
        private boolean error;
        private boolean retryable = true;
        private Map<String, Object> renderPayload;

        private Builder() {
        }

        /**
         * Sets the id of the call this result answers.
         *
         * @param toolUseId
         *            the tool-use id (must not be null)
         * @return this builder
         */
        public Builder toolUseId(String toolUseId) {
            this.toolUseId = toolUseId;
            return this;
        }

        /**
         * Sets the text result.
         *
         * @param text
         *            the text (must not be null)
         * @return this builder
         */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /**
         * Sets the environment's notices.
         *
         * @param notices
         *            the notices (null means none)
         * @return this builder
         */
        public Builder notices(List<String> notices) {
            this.notices = notices;
            return this;
        }

        /**
         * Sets the hooks' advisory feedback.
         *
         * @param hookFeedback
         *            the feedback (null means none)
         * @return this builder
         */
        public Builder hookFeedback(List<String> hookFeedback) {
            this.hookFeedback = hookFeedback;
            return this;
        }

        /**
         * Sets whether the call failed.
         *
         * @param error
         *            {@code true} for an error result
         * @return this builder
         */
        public Builder error(boolean error) {
            this.error = error;
            return this;
        }

        /**
         * Sets whether a failed call is worth repeating. Ignored for a success.
         *
         * @param retryable
         *            the retryable flag
         * @return this builder
         */
        public Builder retryable(boolean retryable) {
            this.retryable = retryable;
            return this;
        }

        /**
         * Sets the structured payload for renderers.
         *
         * @param renderPayload
         *            the payload (null means none)
         * @return this builder
         */
        public Builder renderPayload(Map<String, Object> renderPayload) {
            this.renderPayload = renderPayload;
            return this;
        }

        /**
         * Builds the result.
         *
         * @return a new result (never null)
         * @throws NullPointerException
         *             if the tool-use id or the text is missing
         */
        public ToolDispatchResult build() {
            return new ToolDispatchResult(this);
        }
    }
}
