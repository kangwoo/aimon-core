package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryModelContextWindowRegistry - the built-in table")
class InMemoryModelContextWindowRegistryTest {

    @Test
    @DisplayName("the gpt-6 prefix gives every gpt-6 and gpt-6.1 name the vendor's 1.05M window")
    void gpt6FamilyHasTheRealWindow() {
        final InMemoryModelContextWindowRegistry registry = InMemoryModelContextWindowRegistry.withDefaults();

        for (final String name : new String[]{"gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-6.1-sol", "GPT-6.1-x"}) {
            final ModelContextLimits limits = registry.resolve(name);
            assertThat(limits.getContextWindow()).as(name).isEqualTo(1_050_000);
            assertThat(limits.getAutoCompactRatio()).as(name).isEqualTo(ModelContextLimits.DEFAULT_AUTO_COMPACT_RATIO);
            assertThat(limits.getAutoCompactThreshold()).as(name).isEqualTo(824_000);
        }
        assertThat(registry.resolve("unknown-model").getContextWindow())
                .isEqualTo(ModelContextLimits.DEFAULT_CONTEXT_WINDOW);
    }
}
