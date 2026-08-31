package at.aimon.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.permission.AllowedTool;

class BrowserToolPermissionRuleTest {

    private final BrowserToolPermissionRule rule = new BrowserToolPermissionRule();

    @Test
    void shouldAllowOpenActionWithWildcard() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Browser(open:*)"));
        ToolInput input = ToolInput.of(Map.of("action", "open", "url", "https://example.com"));

        assertThat(rule.isAllowed(input, ToolContext.empty(), allowed)).isTrue();
    }

    @Test
    void shouldDenyClickWhenOnlyOpenAllowed() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Browser(open:*)"));
        ToolInput input = ToolInput.of(Map.of("action", "click", "selector", "#btn"));

        assertThat(rule.isAllowed(input, ToolContext.empty(), allowed)).isFalse();
    }

    @Test
    void shouldAllowExtractWithWildcard() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Browser(extract:*)"));
        ToolInput input = ToolInput.of(Map.of("action", "extract"));

        assertThat(rule.isAllowed(input, ToolContext.empty(), allowed)).isTrue();
    }

    @Test
    void shouldDenyWhenActionIsMissing() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Browser(open:*)"));
        ToolInput input = ToolInput.of(Map.of("url", "https://example.com"));

        assertThat(rule.isAllowed(input, ToolContext.empty(), allowed)).isFalse();
    }

    @Test
    void shouldDenyWhenActionIsNotString() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Browser(open:*)"));
        ToolInput input = ToolInput.of(Map.of("action", 123));

        assertThat(rule.isAllowed(input, ToolContext.empty(), allowed)).isFalse();
    }

    /**
     * A rule runs before the tool and outside the guard that turns a failed execution into an error result, so a
     * wrong-typed argument has to produce a verdict rather than an exception. Which verdict follows from the pattern:
     * an unusable url drops out of the match target, leaving the bare action, so a grant that covers every url still
     * matches and a grant naming a host no longer does.
     */
    @Test
    void shouldNotThrowWhenUrlIsNotString() {
        ToolInput input = ToolInput.of(Map.of("action", "open", "url", 42));

        assertThat(rule.isAllowed(input, ToolContext.empty(), List.of(AllowedTool.parse("Browser(open:*)")))).isTrue();
        assertThat(rule.isAllowed(input, ToolContext.empty(),
                List.of(AllowedTool.parse("Browser(open:https://example.com:*)")))).isFalse();
    }

    @Test
    void shouldAllowMultipleActionPatterns() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Browser(open:*)"),
                AllowedTool.parse("Browser(extract:*)"), AllowedTool.parse("Browser(close:*)"));
        ToolInput extractInput = ToolInput.of(Map.of("action", "extract"));
        ToolInput closeInput = ToolInput.of(Map.of("action", "close"));

        assertThat(rule.isAllowed(extractInput, ToolContext.empty(), allowed)).isTrue();
        assertThat(rule.isAllowed(closeInput, ToolContext.empty(), allowed)).isTrue();
    }

    @Test
    void shouldBuildErrorDetail() {
        ToolInput input = ToolInput.of(Map.of("action", "open"));
        String detail = rule.buildErrorDetail(input, ToolContext.empty());

        assertThat(detail).contains("Browser action: open");
    }

    @Test
    void shouldBuildErrorDetailForMissingAction() {
        ToolInput input = ToolInput.of(Map.of("url", "https://example.com"));
        String detail = rule.buildErrorDetail(input, ToolContext.empty());

        assertThat(detail).contains("Browser action: unknown");
    }

    /**
     * The detail is appended to the denial message verbatim, so it has to carry its own separators — otherwise the
     * message reads "not allowedBrowser action: open.".
     */
    @Test
    void shouldWrapErrorDetailForDirectConcatenation() {
        ToolInput input = ToolInput.of(Map.of("action", "open"));

        assertThat(rule.buildErrorDetail(input, ToolContext.empty())).isEqualTo(" (Browser action: open)");
    }

    @Test
    void shouldDenyWhenAllowedToolsEmpty() {
        List<AllowedTool> allowed = List.of();
        ToolInput input = ToolInput.of(Map.of("action", "open", "url", "https://example.com"));

        assertThat(rule.isAllowed(input, ToolContext.empty(), allowed)).isFalse();
    }
}
