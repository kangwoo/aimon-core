package at.aimon.browser.playwright.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

import at.aimon.browser.playwright.dom.DomCandidate;

/**
 * 액션 실행 결과를 담는 불변 클래스.
 *
 * <p>
 * JSON으로 직렬화되어 {@code ToolResult.success()}의 content가 된다.
 * {@code error} 필드가 null이면 성공, 값이 있으면 에러를 의미한다.
 *
 * <p>
 * CLAUDE.md 규칙에 따라 record가 아닌 class + builder 사용.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class BrowserActionResult {

    private final String sessionId;
    private final String action;
    private final String url;
    private final String title;
    private final String error;
    private final String message;
    private final String content;
    private final String screenshot;
    private final String filePath;
    private final List<DomCandidate> candidates;
    private final List<String> warnings;

    private BrowserActionResult(Builder builder) {
        this.sessionId = builder.sessionId;
        this.action = builder.action;
        this.url = builder.url;
        this.title = builder.title;
        this.error = builder.error;
        this.message = builder.message;
        this.content = builder.content;
        this.screenshot = builder.screenshot;
        this.filePath = builder.filePath;
        this.candidates = builder.candidates != null
                ? Collections.unmodifiableList(new ArrayList<>(builder.candidates))
                : null;
        this.warnings = builder.warnings != null && !builder.warnings.isEmpty()
                ? Collections.unmodifiableList(new ArrayList<>(builder.warnings))
                : null;
    }

    /**
     * Creates a builder pre-populated for a successful result.
     *
     * @param sessionId
     *            the session ID
     * @param action
     *            the action that was performed
     * @return a new Builder with sessionId and action set
     */
    public static Builder success(String sessionId, String action) {
        return new Builder().sessionId(sessionId).action(action);
    }

    /**
     * Creates a complete error result.
     *
     * @param sessionId
     *            the session ID
     * @param action
     *            the action that was attempted
     * @param errorCode
     *            the error code (e.g., "ELEMENT_NOT_FOUND")
     * @param message
     *            the human-readable error message
     * @return a completed BrowserActionResult with error fields set
     */
    public static BrowserActionResult error(String sessionId, String action, String errorCode, String message) {
        return new Builder().sessionId(sessionId).action(action).error(errorCode).message(message).build();
    }

    /**
     * Creates a new empty Builder.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAction() {
        return action;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getContent() {
        return content;
    }

    public String getScreenshot() {
        return screenshot;
    }

    public String getFilePath() {
        return filePath;
    }

    public List<DomCandidate> getCandidates() {
        return candidates;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public boolean isError() {
        return error != null;
    }

    @Override
    public String toString() {
        return "BrowserActionResult{sessionId='" + sessionId + "', action='" + action + "', error='" + error + "'}";
    }

    public static final class Builder {

        private String sessionId;
        private String action;
        private String url;
        private String title;
        private String error;
        private String message;
        private String content;
        private String screenshot;
        private String filePath;
        private List<DomCandidate> candidates;
        private List<String> warnings;

        private Builder() {
        }

        /** Sets the session ID. */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** Sets the action type. */
        public Builder action(String action) {
            this.action = action;
            return this;
        }

        /** Sets the current page URL. */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /** Sets the current page title. */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /** Sets the error code. */
        public Builder error(String error) {
            this.error = error;
            return this;
        }

        /** Sets the human-readable message. */
        public Builder message(String message) {
            this.message = message;
            return this;
        }

        /** Sets the extracted content. */
        public Builder content(String content) {
            this.content = content;
            return this;
        }

        /** Sets the base64-encoded screenshot. */
        public Builder screenshot(String screenshot) {
            this.screenshot = screenshot;
            return this;
        }

        /** Sets the saved file path. */
        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        /** Sets the DOM candidate list. */
        public Builder candidates(List<DomCandidate> candidates) {
            this.candidates = candidates;
            return this;
        }

        /** Sets the warning messages. */
        public Builder warnings(List<String> warnings) {
            this.warnings = warnings;
            return this;
        }

        /**
         * Builds the BrowserActionResult.
         *
         * @throws NullPointerException
         *             if sessionId or action is null
         */
        public BrowserActionResult build() {
            Objects.requireNonNull(sessionId, "sessionId cannot be null");
            Objects.requireNonNull(action, "action cannot be null");
            return new BrowserActionResult(this);
        }
    }
}
