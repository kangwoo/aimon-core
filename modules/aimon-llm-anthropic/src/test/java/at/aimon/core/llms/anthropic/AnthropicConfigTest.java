package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
import at.aimon.core.llm.capability.ThinkingDialect;

@DisplayName("AnthropicConfig - Configuration Builder Tests")
class AnthropicConfigTest {

    @Test
    @DisplayName("Should build config with required API key only")
    void shouldBuildConfig_WithApiKeyOnly() {
        // Given: API key
        String apiKey = "sk-ant-test-key";

        // When: Building config with only API key
        AnthropicConfig config = AnthropicConfig.builder().apiKey(apiKey).build();

        // Then: Should use defaults for other fields
        assertThat(config.getApiKey()).isEqualTo(apiKey);
        assertThat(config.getModel()).isEqualTo("claude-sonnet-4-5");
        // Unset, not 0.0. A manufactured default would be sent on every request, and 0.0 is the one value the
        // current Claude generation refuses -- see docs/design/llm/request-parameters.md section 2.2.
        assertThat(config.getTemperature()).isEmpty();
        assertThat(config.getMaxTokens()).isEqualTo(4096);
        assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getBaseUrl()).isNull();
    }

    @Test
    @DisplayName("Should build config with all parameters")
    void shouldBuildConfig_WithAllParameters() {
        // Given: All configuration parameters
        String apiKey = "sk-ant-test-key";
        String model = "claude-opus-4-20250514";
        double temperature = 0.7;
        int maxTokens = 2048;
        Duration timeout = Duration.ofSeconds(30);
        String baseUrl = "https://api.custom.com";

        // When: Building config with all parameters
        AnthropicConfig config = AnthropicConfig.builder().apiKey(apiKey).model(model).temperature(temperature)
                .maxTokens(maxTokens).timeout(timeout).baseUrl(baseUrl).build();

        // Then: All fields should match
        assertThat(config.getApiKey()).isEqualTo(apiKey);
        assertThat(config.getModel()).isEqualTo(model);
        assertThat(config.getTemperature()).contains(temperature);
        assertThat(config.getMaxTokens()).isEqualTo(maxTokens);
        assertThat(config.getTimeout()).isEqualTo(timeout);
        assertThat(config.getBaseUrl()).isEqualTo(baseUrl);
    }

    @Test
    @DisplayName("Should throw exception when API key is null")
    void shouldThrowException_WhenApiKeyIsNull() {
        // When/Then: Building without API key should fail
        assertThatThrownBy(() -> AnthropicConfig.builder().build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("API key cannot be null");
    }

    @Test
    @DisplayName("Should throw exception when API key is blank")
    void shouldThrowException_WhenApiKeyIsBlank() {
        // When/Then: Building with blank API key should fail
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("  ").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API key cannot be blank");
    }

    @Test
    @DisplayName("Should throw exception when API key is empty string")
    void shouldThrowException_WhenApiKeyIsEmpty() {
        // When/Then: Building with empty API key should fail
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API key cannot be blank");
    }

    @Test
    @DisplayName("Should throw exception when model is null")
    void shouldThrowException_WhenModelIsNull() {
        // When/Then: Setting null model should fail
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("test").model(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Model cannot be null");
    }

    @Test
    @DisplayName("Should throw exception when timeout is null")
    void shouldThrowException_WhenTimeoutIsNull() {
        // When/Then: Setting null timeout should fail
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("test").timeout(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Timeout cannot be null");
    }

    @Test
    @DisplayName("Should throw exception when temperature is out of range - too low")
    void shouldThrowException_WhenTemperatureIsTooLow() {
        // When/Then: Temperature below 0.0 should fail
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("test").temperature(-0.1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Temperature must be between 0.0 and 1.0");
    }

    @Test
    @DisplayName("Should throw exception when temperature is out of range - too high")
    void shouldThrowException_WhenTemperatureIsTooHigh() {
        // When/Then: Temperature above 1.0 should fail
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("test").temperature(1.1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Temperature must be between 0.0 and 1.0");
    }

    @Test
    @DisplayName("Should accept boundary temperature values")
    void shouldAcceptBoundaryTemperatureValues() {
        // Given: Boundary temperature values
        String apiKey = "test";

        // When/Then: 0.0 and 1.0 should be valid
        AnthropicConfig config1 = AnthropicConfig.builder().apiKey(apiKey).temperature(0.0).build();
        assertThat(config1.getTemperature()).contains(0.0);

        AnthropicConfig config2 = AnthropicConfig.builder().apiKey(apiKey).temperature(1.0).build();
        assertThat(config2.getTemperature()).contains(1.0);
    }

    @Test
    @DisplayName("Should throw exception when maxTokens is zero")
    void shouldThrowException_WhenMaxTokensIsZero() {
        // When/Then: Max tokens = 0 should fail
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("test").maxTokens(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Max tokens must be positive");
    }

    @Test
    @DisplayName("Should throw exception when maxTokens is negative")
    void shouldThrowException_WhenMaxTokensIsNegative() {
        // When/Then: Negative max tokens should fail
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("test").maxTokens(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Max tokens must be positive");
    }

    @Test
    @DisplayName("Should accept positive maxTokens")
    void shouldAcceptPositiveMaxTokens() {
        // Given: Positive max tokens
        String apiKey = "test";
        int maxTokens = 8192;

        // When: Building config
        AnthropicConfig config = AnthropicConfig.builder().apiKey(apiKey).maxTokens(maxTokens).build();

        // Then: Should accept value
        assertThat(config.getMaxTokens()).isEqualTo(maxTokens);
    }

    @Test
    @DisplayName("Should support builder chaining")
    void shouldSupportBuilderChaining() {
        // Given/When: Chaining builder calls
        AnthropicConfig config = AnthropicConfig.builder().apiKey("test").model("claude-opus-4-20250514")
                .temperature(0.5).maxTokens(1024).timeout(Duration.ofSeconds(45)).baseUrl("https://custom.api.com")
                .build();

        // Then: All values should be set
        assertThat(config).isNotNull();
        assertThat(config.getApiKey()).isEqualTo("test");
        assertThat(config.getModel()).isEqualTo("claude-opus-4-20250514");
        assertThat(config.getTemperature()).contains(0.5);
        assertThat(config.getMaxTokens()).isEqualTo(1024);
        assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(config.getBaseUrl()).isEqualTo("https://custom.api.com");
    }

    @Test
    @DisplayName("Should handle null baseUrl gracefully")
    void shouldHandleNullBaseUrl() {
        // Given: Config without baseUrl
        AnthropicConfig config = AnthropicConfig.builder().apiKey("test").build();

        // Then: baseUrl should be null (will use default)
        assertThat(config.getBaseUrl()).isNull();
    }

    @Test
    @DisplayName("Should handle empty baseUrl")
    void shouldHandleEmptyBaseUrl() {
        // Given: Config with empty baseUrl
        AnthropicConfig config = AnthropicConfig.builder().apiKey("test").baseUrl("").build();

        // Then: baseUrl should be empty string
        assertThat(config.getBaseUrl()).isEmpty();
    }

    @Test
    @DisplayName("Should be equal to same instance")
    void shouldBeEqualToSameInstance() {
        // Given: A config instance
        AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").build();

        // Then: Should be equal to itself
        assertThat(config).isEqualTo(config);
    }

    @Test
    @DisplayName("Should be equal to config with same values and have same hashCode")
    void shouldBeEqualToConfigWithSameValues() {
        // Given: Two configs with identical values
        AnthropicConfig config1 = AnthropicConfig.builder().apiKey("test-key").model("claude-sonnet-4-20250514")
                .temperature(0.5).maxTokens(2048).build();

        AnthropicConfig config2 = AnthropicConfig.builder().apiKey("test-key").model("claude-sonnet-4-20250514")
                .temperature(0.5).maxTokens(2048).build();

        // Then: Should be equal and have same hashCode
        assertThat(config1).isEqualTo(config2);
        assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    @DisplayName("Should not be equal to config with different values")
    void shouldNotBeEqualToConfigWithDifferentValues() {
        // Given: Two configs with different values
        AnthropicConfig config1 = AnthropicConfig.builder().apiKey("key-1").model("claude-sonnet-4-20250514").build();

        AnthropicConfig config2 = AnthropicConfig.builder().apiKey("key-2").model("claude-opus-4-20250514").build();

        // Then: Should not be equal
        assertThat(config1).isNotEqualTo(config2);
    }

    @Test
    @DisplayName("Should not be equal to null")
    void shouldNotBeEqualToNull() {
        // Given: A config instance
        AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").build();

        // Then: Should not be equal to null
        assertThat(config).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should not include API key in toString")
    void shouldNotIncludeApiKeyInToString() {
        // Given: Config with API key
        AnthropicConfig config = AnthropicConfig.builder().apiKey("sk-ant-super-secret-key").build();

        // Then: toString should not expose the API key
        String result = config.toString();
        assertThat(result).doesNotContain("sk-ant-super-secret-key");
    }

    @Test
    @DisplayName("Thinking is off by default, and stored blocks are replayed by default")
    void thinkingDefaults() {
        // OFF keeps the request body exactly as it was before thinking support existed; replay defaults to true
        // because on the always-on models the blocks arrive whether or not the operator asked for them.
        AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").build();

        assertThat(config.getThinkingMode()).isEqualTo(AnthropicThinkingMode.OFF);
        assertThat(config.getThinkingBudgetTokens()).isNull();
        assertThat(config.isReplayThinkingBlocks()).isTrue();
    }

    @Test
    @DisplayName("Should accept all three thinking settings")
    void shouldAcceptThinkingSettings() {
        AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key")
                .thinkingMode(AnthropicThinkingMode.EXTENDED).thinkingBudgetTokens(8000).maxTokens(16_000)
                .replayThinkingBlocks(false).build();

        assertThat(config.getThinkingMode()).isEqualTo(AnthropicThinkingMode.EXTENDED);
        assertThat(config.getThinkingBudgetTokens()).isEqualTo(8000);
        assertThat(config.isReplayThinkingBlocks()).isFalse();
    }

    @Test
    @DisplayName("Should throw exception when thinking mode is null")
    void shouldThrowWhenThinkingModeIsNull() {
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("test-key").thinkingMode(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Thinking mode cannot be null");
    }

    @Test
    @DisplayName("Should throw exception when the thinking budget is below the API floor")
    void shouldThrowWhenThinkingBudgetIsBelowTheFloor() {
        // The API rejects anything under 1024 on every request, so failing here beats failing on all of them.
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("test-key")
                .thinkingMode(AnthropicThinkingMode.EXTENDED).thinkingBudgetTokens(1023).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 1024");
    }

    @Test
    @DisplayName("Should throw exception when a thinking budget is set outside EXTENDED mode")
    void shouldThrowWhenBudgetIsSetOutsideExtendedMode() {
        // Adaptive has no budget field and OFF sends no thinking parameter, so the value would be silently ignored.
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("test-key")
                .thinkingMode(AnthropicThinkingMode.ADAPTIVE).thinkingBudgetTokens(4096).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("applies only to EXTENDED");
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("test-key").thinkingBudgetTokens(4096).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("applies only to EXTENDED");
    }

    @Test
    @DisplayName("Two configs differing only in a thinking setting are not equal")
    void thinkingSettingsParticipateInEquality() {
        AnthropicConfig off = AnthropicConfig.builder().apiKey("test-key").build();
        AnthropicConfig adaptive = AnthropicConfig.builder().apiKey("test-key")
                .thinkingMode(AnthropicThinkingMode.ADAPTIVE).build();
        AnthropicConfig noReplay = AnthropicConfig.builder().apiKey("test-key").replayThinkingBlocks(false).build();

        assertThat(off).isNotEqualTo(adaptive).isNotEqualTo(noReplay);
        assertThat(off).isEqualTo(AnthropicConfig.builder().apiKey("test-key").build());
        assertThat(off).hasSameHashCodeAs(AnthropicConfig.builder().apiKey("test-key").build());
    }

    @Test
    @DisplayName("reasoningEffort round-trips, defaults to unset, and participates in equality and toString")
    void reasoningEffortRoundTrips() {
        // Unset by default rather than defaulted to a rung: the client must be able to tell "nobody asked" from
        // "somebody asked for medium", because the first sends nothing and the second is what an inert-effort
        // warning is about. And equality has to carry it, because LlmClientFactoryTest pins "an empty block builds
        // a config equal to no block at all" through this method.
        assertThat(AnthropicConfig.builder().apiKey("test-key").build().getReasoningEffort()).isEmpty();
        assertThat(AnthropicConfig.builder().apiKey("test-key").reasoningEffort(ReasoningEffort.HIGH).build()
                .getReasoningEffort()).contains(ReasoningEffort.HIGH);

        final AnthropicConfig unset = AnthropicConfig.builder().apiKey("test-key").build();
        final AnthropicConfig high = AnthropicConfig.builder().apiKey("test-key").reasoningEffort(ReasoningEffort.HIGH)
                .build();

        assertThat(unset).isNotEqualTo(high);
        assertThat(high)
                .isEqualTo(AnthropicConfig.builder().apiKey("test-key").reasoningEffort(ReasoningEffort.HIGH).build());
        assertThat(high.toString()).contains("reasoningEffort=HIGH");
        assertThat(unset.toString()).contains("reasoningEffort=null");
    }

    @Test
    @DisplayName("toString reports the thinking settings and still hides the API key")
    void toStringReportsThinkingSettings() {
        AnthropicConfig config = AnthropicConfig.builder().apiKey("sk-ant-super-secret-key")
                .thinkingMode(AnthropicThinkingMode.EXTENDED).thinkingBudgetTokens(2048).maxTokens(8000).build();

        assertThat(config.toString()).contains("thinkingMode=EXTENDED").contains("thinkingBudgetTokens=2048")
                .contains("replayThinkingBlocks=true").doesNotContain("sk-ant-super-secret-key");
    }

    @Test
    @DisplayName("the default capability registry is the shipped table, and a supplied one replaces it")
    void modelCapabilityRegistryDefaultsToTheShippedTable() {
        AnthropicConfig shipped = AnthropicConfig.builder().apiKey("test-key").build();

        assertThat(shipped.getModelCapabilityRegistry().resolve("claude-opus-5").supportsSamplingParameters())
                .isFalse();

        AnthropicConfig supplied = AnthropicConfig.builder().apiKey("test-key")
                .modelCapabilityRegistry(ModelCapabilityRegistry.EMPTY).build();

        assertThat(supplied.getModelCapabilityRegistry().resolve("claude-opus-5"))
                .isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("the default model is a name the shipped capability table describes")
    void theDefaultModelIsDescribedByTheShippedTable() {
        // #116: the default before this one, claude-sonnet-4-20250514, had no row, and the Messages API answered it
        // with 404 on 2026-09-11. Tying the default to a measured row makes the next change of it face the table.
        AnthropicConfig shipped = AnthropicConfig.builder().apiKey("test-key").build();

        assertThat(shipped.getModelCapabilityRegistry().resolve(shipped.getModel()).thinkingDialect())
                .as("the default model %s is meant to be a name the built-in table describes: its row was measured "
                        + "2026-09-10, and the Messages API served it on 2026-09-11 (#116)", shipped.getModel())
                .isEqualTo(ThinkingDialect.BUDGETED);
    }

    @Test
    @DisplayName("a null capability registry is refused rather than turning the look-up off silently")
    void aNullCapabilityRegistryIsRefused() {
        assertThatThrownBy(() -> AnthropicConfig.builder().apiKey("test-key").modelCapabilityRegistry(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Model capability registry");
    }

    @Test
    @DisplayName("two configs carrying different registry instances are still equal")
    void theRegistryDoesNotParticipateInEquality() {
        // Every build() makes its own InMemoryModelCapabilityRegistry and no registry defines equals, so counting the
        // collaborator would make two configs that agree on every value an operator can write unequal.
        AnthropicConfig one = AnthropicConfig.builder().apiKey("test-key").build();
        AnthropicConfig two = AnthropicConfig.builder().apiKey("test-key").build();

        assertThat(one.getModelCapabilityRegistry()).isNotSameAs(two.getModelCapabilityRegistry());
        assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
    }
}
