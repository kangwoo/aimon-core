package at.aimon.browser.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.browser.playwright.dom.DomCandidate;

class BrowserActionResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldBuildSuccessResult() {
        BrowserActionResult result = BrowserActionResult.success("bs_12345678", "open").url("https://example.com")
                .title("Example Domain")
                .candidates(List.of(DomCandidate.builder().label("More info").selector("a[href]").role("link").build()))
                .build();

        assertThat(result.getSessionId()).isEqualTo("bs_12345678");
        assertThat(result.getAction()).isEqualTo("open");
        assertThat(result.getUrl()).isEqualTo("https://example.com");
        assertThat(result.getTitle()).isEqualTo("Example Domain");
        assertThat(result.getError()).isNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.getCandidates()).hasSize(1);
    }

    @Test
    void shouldBuildErrorResult() {
        BrowserActionResult result = BrowserActionResult.error("bs_12345678", "click", "ELEMENT_NOT_FOUND",
                "No element matching selector: #nonexistent");

        assertThat(result.getSessionId()).isEqualTo("bs_12345678");
        assertThat(result.getAction()).isEqualTo("click");
        assertThat(result.getError()).isEqualTo("ELEMENT_NOT_FOUND");
        assertThat(result.getMessage()).isEqualTo("No element matching selector: #nonexistent");
        assertThat(result.isError()).isTrue();
    }

    @Test
    void shouldSerializeToJsonExcludingNullFields() throws Exception {
        BrowserActionResult result = BrowserActionResult.success("bs_12345678", "open").url("https://example.com")
                .title("Example Domain").build();

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"sessionId\"");
        assertThat(json).contains("\"action\"");
        assertThat(json).contains("\"url\"");
        assertThat(json).contains("\"title\"");
        assertThat(json).doesNotContain("\"error\"");
        assertThat(json).doesNotContain("\"message\"");
        assertThat(json).doesNotContain("\"content\"");
        assertThat(json).doesNotContain("\"screenshot\"");
        assertThat(json).doesNotContain("\"candidates\"");
        assertThat(json).doesNotContain("\"warnings\"");
    }

    @Test
    void shouldSerializeErrorResultToJson() throws Exception {
        BrowserActionResult result = BrowserActionResult.error("bs_12345678", "open", "SSRF_BLOCKED", "URL blocked");

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"error\":\"SSRF_BLOCKED\"");
        assertThat(json).contains("\"message\":\"URL blocked\"");
    }

    @Test
    void shouldSerializeWarningsToJson() throws Exception {
        BrowserActionResult result = BrowserActionResult.success("bs_12345678", "screenshot")
                .warnings(List.of("Session uses minimal resource policy")).build();

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"warnings\"");
        assertThat(json).contains("Session uses minimal resource policy");
    }

    @Test
    void shouldExcludeEmptyWarningsFromJson() throws Exception {
        BrowserActionResult result = BrowserActionResult.success("bs_12345678", "click").warnings(List.of()).build();

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).doesNotContain("\"warnings\"");
    }

    @Test
    void shouldThrowNpeWhenSessionIdIsNull() {
        assertThatNullPointerException().isThrownBy(() -> BrowserActionResult.builder().action("open").build())
                .withMessageContaining("sessionId");
    }

    @Test
    void shouldThrowNpeWhenActionIsNull() {
        assertThatNullPointerException().isThrownBy(() -> BrowserActionResult.builder().sessionId("bs_123").build())
                .withMessageContaining("action");
    }

    @Test
    void shouldThrowNpeWhenBothSessionIdAndActionAreNull() {
        assertThatNullPointerException().isThrownBy(() -> BrowserActionResult.builder().build());
    }

    @Test
    void shouldHaveImmutableCandidatesAndWarnings() {
        List<DomCandidate> candidates = new java.util.ArrayList<>();
        candidates.add(DomCandidate.builder().label("OK").build());

        List<String> warnings = new java.util.ArrayList<>();
        warnings.add("warning1");

        BrowserActionResult result = BrowserActionResult.success("bs_123", "open").candidates(candidates)
                .warnings(warnings).build();

        // Modifying original list should not affect result
        candidates.add(DomCandidate.builder().label("Cancel").build());
        warnings.add("warning2");

        assertThat(result.getCandidates()).hasSize(1);
        assertThat(result.getWarnings()).hasSize(1);
    }
}
