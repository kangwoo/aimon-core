package at.aimon.core.skill.hook.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HttpAction")
class HttpActionTest {

    @Test
    @DisplayName("builds with defaults (POST, 10s timeout, no body, no env)")
    void buildsWithDefaults() {
        final HttpAction action = HttpAction.builder().url("https://example.com/hook").build();

        assertThat(action.getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(action.getTimeout()).isEqualTo(HttpAction.DEFAULT_TIMEOUT);
        assertThat(action.getBodyTemplate()).isNull();
        assertThat(action.getHeaders()).isEmpty();
        assertThat(action.getAllowedEnvVars()).isEmpty();
    }

    @Test
    @DisplayName("rejects non-http(s) schemes")
    void rejectsBadScheme() {
        assertThatThrownBy(() -> HttpAction.builder().url("file:///etc/passwd").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scheme");
    }

    @Test
    @DisplayName("rejects null url")
    void rejectsNullUrl() {
        assertThatThrownBy(() -> HttpAction.builder().build()).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects non-positive timeout")
    void rejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> HttpAction.builder().url("https://x").timeout(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Timeout");
    }

    @Test
    @DisplayName("captures full configuration")
    void capturesConfiguration() {
        final HttpAction action = HttpAction.builder().url("https://example.com/hook").method(HttpMethod.PUT)
                .addHeader("Authorization", "Bearer ${env.API_TOKEN}").bodyTemplate("{\"x\":\"${tool_input.x}\"}")
                .timeout(Duration.ofSeconds(5)).allowedEnvVars(List.of("API_TOKEN")).build();

        assertThat(action.getMethod()).isEqualTo(HttpMethod.PUT);
        assertThat(action.getHeaders()).containsEntry("Authorization", "Bearer ${env.API_TOKEN}");
        assertThat(action.getBodyTemplate()).contains("${tool_input.x}");
        assertThat(action.getTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(action.getAllowedEnvVars()).containsExactly("API_TOKEN");
    }
}
