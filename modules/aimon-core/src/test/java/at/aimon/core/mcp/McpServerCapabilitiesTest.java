package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpServerCapabilitiesTest {

    @Test
    @DisplayName("Builder populates all fields including nullable serverVersion")
    void buildsWithAllFields() {
        McpServerCapabilities caps = McpServerCapabilities.builder().supportsTools(true).supportsResources(false)
                .supportsPrompts(true).serverName("github").serverVersion("1.2.3").build();

        assertThat(caps.supportsTools()).isTrue();
        assertThat(caps.supportsResources()).isFalse();
        assertThat(caps.supportsPrompts()).isTrue();
        assertThat(caps.getServerName()).isEqualTo("github");
        assertThat(caps.getServerVersion()).isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("serverVersion may be null")
    void serverVersionNullable() {
        McpServerCapabilities caps = McpServerCapabilities.builder().serverName("a").build();

        assertThat(caps.getServerVersion()).isNull();
        assertThat(caps.supportsTools()).isFalse();
        assertThat(caps.supportsResources()).isFalse();
        assertThat(caps.supportsPrompts()).isFalse();
    }

    @Test
    @DisplayName("serverName is required")
    void serverNameRequired() {
        assertThatNullPointerException().isThrownBy(() -> McpServerCapabilities.builder().build());
    }
}
