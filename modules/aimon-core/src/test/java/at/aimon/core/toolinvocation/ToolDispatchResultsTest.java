package at.aimon.core.toolinvocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.tools.EnvironmentNotices;

/**
 * The conversion a model-facing loop applies once, when it records a dispatch result: the side channels meet the text
 * there, and the sidecars the model never reads — the error flag, the retryable mark, the render payload — must cross
 * it unchanged (tool-call-dispatcher design §3 #7).
 */
@DisplayName("ToolDispatchResults — the one conversion into a transcript result")
class ToolDispatchResultsTest {

    @Test
    @DisplayName("notices go in front, hook feedback behind, in a hook-feedback reminder block")
    void noticesFrontFeedbackBehind() {
        final ToolDispatchResult result = ToolDispatchResults.of(ToolUseResult.success("tu-1", "body"),
                List.of("sandbox re-created"), List.of("prefer Grep"));

        final ToolUseResult converted = ToolDispatchResults.toToolUseResult(result);

        assertThat(converted.getToolUseId()).isEqualTo("tu-1");
        assertThat(converted.getContent()).startsWith(EnvironmentNotices.PREFIX + "sandbox re-created\nbody\n\n")
                .contains("key=\"hook-feedback\"").contains("prefer Grep");
        assertThat(converted.isError()).isFalse();
    }

    @Test
    @DisplayName("with no side channels the text is the tool's text, untouched")
    void noSideChannels() {
        final ToolUseResult converted = ToolDispatchResults
                .toToolUseResult(ToolDispatchResults.of(ToolUseResult.success("tu-1", "body"), List.of(), List.of()));

        assertThat(converted.getContent()).isEqualTo("body");
    }

    @Test
    @DisplayName("feedback on an empty text is the whole text, with no leading blank lines")
    void feedbackOnEmptyText() {
        final ToolUseResult converted = ToolDispatchResults
                .toToolUseResult(ToolDispatchResults.of(ToolUseResult.success("tu-1", ""), List.of(), List.of("note")));

        assertThat(converted.getContent()).startsWith("<system-reminder").contains("note");
    }

    @Test
    @DisplayName("the error flag, a not-retryable mark and the render payload cross the round trip unchanged")
    void sidecarsSurviveTheRoundTrip() {
        final ToolUseResult original = ToolUseResult.error("tu-2", "denied").notRetryable()
                .withRenderPayload(Map.of("kind", "diff"));

        final ToolDispatchResult dispatched = ToolDispatchResults.of(original, List.of("n"), List.of("f"));
        assertThat(dispatched.isError()).isTrue();
        assertThat(dispatched.isRetryable()).isFalse();
        assertThat(dispatched.getRenderPayload()).contains(Map.of("kind", "diff"));

        final ToolUseResult converted = ToolDispatchResults.toToolUseResult(dispatched);
        assertThat(converted.isError()).isTrue();
        assertThat(converted.isRetryable()).isFalse();
        assertThat(converted.getRenderPayload()).isEqualTo(Map.of("kind", "diff"));
    }

    @Test
    @DisplayName("an ordinary error stays retryable")
    void ordinaryErrorStaysRetryable() {
        final ToolUseResult converted = ToolDispatchResults
                .toToolUseResult(ToolDispatchResults.error("tu-3", "Tool execution error: boom"));

        assertThat(converted.isError()).isTrue();
        assertThat(converted.isRetryable()).isTrue();
        assertThat(converted.getContent()).isEqualTo("Tool execution error: boom");
    }
}
