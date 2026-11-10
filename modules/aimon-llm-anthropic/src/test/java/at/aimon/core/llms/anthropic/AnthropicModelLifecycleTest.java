package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AnthropicModelLifecycle - deprecated and retired model names")
class AnthropicModelLifecycleTest {

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-4-5", "claude-sonnet-4-5-20250929", "Claude-Sonnet-4-5"})
    @DisplayName("claude-sonnet-4-5 and its snapshot are deprecated, naming the date and the successor")
    void sonnetFourFiveIsDeprecated(String name) {
        assertThat(AnthropicModelLifecycle.warningFor(name)).get().asString().contains(name).contains("deprecated")
                .contains("2026-11-30").contains("claude-sonnet-5-5");
    }

    @Test
    @DisplayName("claude-mythos-preview is deprecated with no announced date, naming the successors")
    void mythosPreviewIsDeprecatedWithoutADate() {
        assertThat(AnthropicModelLifecycle.warningFor("claude-mythos-preview")).get().asString()
                .contains("claude-mythos-preview").contains("deprecated").contains("not been announced")
                .contains("claude-mythos-5-1").contains("claude-fable-5-1").doesNotContain("retires on");
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-sonnet-4-0",
            "claude-opus-4-0", "claude-opus-4-1", "claude-3-5-haiku-20241022", "claude-3-7-sonnet-20250219",
            "claude-2.1"})
    @DisplayName("retired names say so")
    void retiredNames(String name) {
        assertThat(AnthropicModelLifecycle.warningFor(name)).get().asString().contains(name).contains("retired");
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-haiku-4-5", "claude-haiku-4-5-20251001", "claude-opus-4-5", "claude-sonnet-4-6",
            "claude-opus-4-6", "claude-sonnet-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5",
            "claude-fable-5-1", "claude-mythos-5", "claude-mythos-5-1", "gateway-claude-deployment"})
    @DisplayName("active names get no warning")
    void activeNamesAreSilent(String name) {
        assertThat(AnthropicModelLifecycle.warningFor(name)).isEmpty();
    }

    @Test
    @DisplayName("null and empty get no warning")
    void nullAndEmpty() {
        assertThat(AnthropicModelLifecycle.warningFor(null)).isEmpty();
        assertThat(AnthropicModelLifecycle.warningFor("")).isEmpty();
    }
}
