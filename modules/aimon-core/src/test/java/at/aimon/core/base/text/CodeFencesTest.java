package at.aimon.core.base.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CodeFences.strip — the single shared Markdown-fence peeler")
class CodeFencesTest {

    @Test
    @DisplayName("null yields empty, never throws")
    void nullYieldsEmpty() {
        assertThat(CodeFences.strip(null)).isEmpty();
    }

    @Test
    @DisplayName("text without a leading fence is returned stripped, unchanged")
    void noFenceUnchanged() {
        assertThat(CodeFences.strip("{\"pages\": []}")).isEqualTo("{\"pages\": []}");
        assertThat(CodeFences.strip("  plain text  ")).isEqualTo("plain text");
    }

    @Test
    @DisplayName("peels a ```json fence and a bare ``` fence")
    void peelsMultiLineFences() {
        assertThat(CodeFences.strip("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(CodeFences.strip("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("a single-line fenced payload survives (old memory/wiki copies dropped it)")
    void peelsSingleLineFence() {
        assertThat(CodeFences.strip("```{\"a\":1}```")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("a missing trailing fence + an inner ``` inside a value is not truncated")
    void innerFenceNotTruncated() {
        assertThat(CodeFences.strip("```json\n{\"a\":\"x```y\"}")).isEqualTo("{\"a\":\"x```y\"}");
    }

    @Test
    @DisplayName("prose the model appends after the closing fence is dropped (regression: strict-mapper caller)")
    void dropsTrailingProseAfterClosingFence() {
        // The scenario that broke LlmJudgeSurprisalScorer (its ObjectMapper enables FAIL_ON_TRAILING_TOKENS):
        // the old lastIndexOf variant discarded the post-fence prose; endsWith alone would have kept it.
        assertThat(CodeFences.strip("```json\n{\"similarity\": 0.9}\n```\nThese two are near-duplicates."))
                .isEqualTo("{\"similarity\": 0.9}");
    }

    @Test
    @DisplayName("closing fence is found at the line boundary even with an inner ``` AND trailing prose")
    void closingFenceAtLineBoundaryWithInnerFenceAndProse() {
        assertThat(CodeFences.strip("```json\n{\"a\":\"x```y\"}\n```\ndone")).isEqualTo("{\"a\":\"x```y\"}");
    }

    @Test
    @DisplayName("only one fence layer is peeled")
    void peelsOneLayer() {
        assertThat(CodeFences.strip("```\n```\n{\"a\":1}\n```\n```")).isEqualTo("```\n{\"a\":1}\n```");
    }
}
