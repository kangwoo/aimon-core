package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContextEngineKindTest {

    @Test
    void parsesTheConfigurationSpellingIgnoringCaseAndWhitespace() {
        assertThat(ContextEngineKind.fromConfig("rolling")).isEqualTo(ContextEngineKind.ROLLING);
        assertThat(ContextEngineKind.fromConfig(" DEFAULT ")).isEqualTo(ContextEngineKind.DEFAULT);
        assertThat(ContextEngineKind.ROLLING.configValue()).isEqualTo("rolling");
    }

    @Test
    void anUnknownValueNamesTheAcceptedOnes() {
        assertThatThrownBy(() -> ContextEngineKind.fromConfig("sliding")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sliding").hasMessageContaining("default, rolling");
    }

    @Test
    void theAgentCarriesItsChoice() {
        final Agent agent = DefaultAgent.builder().name("a").systemPrompt("p").contextEngine(ContextEngineKind.ROLLING)
                .build();

        assertThat(agent.getMetadata().getContextEngine()).contains(ContextEngineKind.ROLLING);
        assertThat(DefaultAgent.builder().name("a").systemPrompt("p").build().getMetadata().getContextEngine())
                .isEmpty();
    }
}
