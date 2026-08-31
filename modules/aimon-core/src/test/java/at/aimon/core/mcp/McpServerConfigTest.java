package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.mcp.McpServerConfig.AnnotationTrust;
import at.aimon.core.mcp.McpServerConfig.McpTransportType;

class McpServerConfigTest {

    @Test
    @DisplayName("STDIO config builds successfully with command")
    void stdioConfigBuildsWithCommand() {
        McpServerConfig config = McpServerConfig.builder().name("github").transportType(McpTransportType.STDIO)
                .command("npx").args(List.of("-y", "@modelcontextprotocol/server-github"))
                .env(Map.of("GITHUB_TOKEN", "secret")).requestTimeout(Duration.ofSeconds(60)).build();

        assertThat(config.getName()).isEqualTo("github");
        assertThat(config.getTransportType()).isEqualTo(McpTransportType.STDIO);
        assertThat(config.getCommand()).isEqualTo("npx");
        assertThat(config.getArgs()).containsExactly("-y", "@modelcontextprotocol/server-github");
        assertThat(config.getEnv()).containsEntry("GITHUB_TOKEN", "secret");
        assertThat(config.getRequestTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("SSE config builds successfully with url")
    void sseConfigBuildsWithUrl() {
        McpServerConfig config = McpServerConfig.builder().name("remote").transportType(McpTransportType.SSE)
                .url("http://localhost:8080/mcp").build();

        assertThat(config.getUrl()).isEqualTo("http://localhost:8080/mcp");
        assertThat(config.getRequestTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("STREAMABLE_HTTP config builds with url")
    void streamableHttpConfigBuilds() {
        McpServerConfig config = McpServerConfig.builder().name("streamable-server")
                .transportType(McpTransportType.STREAMABLE_HTTP).url("http://localhost:9090/mcp").build();

        assertThat(config.getTransportType()).isEqualTo(McpTransportType.STREAMABLE_HTTP);
        assertThat(config.getUrl()).isEqualTo("http://localhost:9090/mcp");
    }

    @Test
    @DisplayName("Defaults: empty args/env, 30s timeout")
    void defaultsApplied() {
        McpServerConfig config = McpServerConfig.builder().name("a").transportType(McpTransportType.STDIO)
                .command("/bin/cat").build();

        assertThat(config.getArgs()).isEmpty();
        assertThat(config.getEnv()).isEmpty();
        assertThat(config.getRequestTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.getUrl()).isNull();
        assertThat(config.getAnnotationTrust()).isEqualTo(AnnotationTrust.IGNORE);
    }

    @Test
    @DisplayName("annotationTrust round-trips, and null means the default rather than null")
    void annotationTrustConfigured() {
        McpServerConfig trusting = McpServerConfig.builder().name("mine").transportType(McpTransportType.STDIO)
                .command("/bin/cat").annotationTrust(AnnotationTrust.TRUST).build();
        McpServerConfig unset = McpServerConfig.builder().name("theirs").transportType(McpTransportType.STDIO)
                .command("/bin/cat").annotationTrust(null).build();

        assertThat(trusting.getAnnotationTrust()).isEqualTo(AnnotationTrust.TRUST);
        assertThat(unset.getAnnotationTrust()).isEqualTo(AnnotationTrust.IGNORE);
    }

    @Test
    @DisplayName("Args list is defensively copied (immutable)")
    void argsAreImmutable() {
        McpServerConfig config = McpServerConfig.builder().name("a").transportType(McpTransportType.STDIO)
                .command("/bin/cat").args(List.of("a", "b")).build();

        assertThat(config.getArgs()).isUnmodifiable();
    }

    @Test
    @DisplayName("Env map is defensively copied (immutable)")
    void envIsImmutable() {
        McpServerConfig config = McpServerConfig.builder().name("a").transportType(McpTransportType.STDIO)
                .command("/bin/cat").env(Map.of("KEY", "VALUE")).build();

        assertThat(config.getEnv()).isUnmodifiable();
    }

    @Test
    @DisplayName("Null name rejected")
    void nullNameRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> McpServerConfig.builder().transportType(McpTransportType.STDIO).command("x").build());
    }

    @Test
    @DisplayName("Null transportType rejected")
    void nullTransportTypeRejected() {
        assertThatNullPointerException().isThrownBy(() -> McpServerConfig.builder().name("a").command("x").build());
    }

    @Test
    @DisplayName("Invalid name patterns are rejected")
    void invalidNamesRejected() {
        // Uppercase
        assertThatIllegalArgumentException().isThrownBy(() -> McpServerConfig.builder().name("GitHub")
                .transportType(McpTransportType.STDIO).command("x").build());
        // Underscore
        assertThatIllegalArgumentException().isThrownBy(() -> McpServerConfig.builder().name("git_hub")
                .transportType(McpTransportType.STDIO).command("x").build());
        // Leading hyphen
        assertThatIllegalArgumentException().isThrownBy(() -> McpServerConfig.builder().name("-github")
                .transportType(McpTransportType.STDIO).command("x").build());
        // Trailing hyphen
        assertThatIllegalArgumentException().isThrownBy(() -> McpServerConfig.builder().name("github-")
                .transportType(McpTransportType.STDIO).command("x").build());
        // Empty
        assertThatIllegalArgumentException().isThrownBy(
                () -> McpServerConfig.builder().name("").transportType(McpTransportType.STDIO).command("x").build());
    }

    @Test
    @DisplayName("Valid name patterns accepted")
    void validNamesAccepted() {
        // Single char
        assertThat(McpServerConfig.builder().name("a").transportType(McpTransportType.STDIO).command("x").build()
                .getName()).isEqualTo("a");
        // Digits
        assertThat(McpServerConfig.builder().name("server123").transportType(McpTransportType.STDIO).command("x")
                .build().getName()).isEqualTo("server123");
        // Hyphens in middle
        assertThat(McpServerConfig.builder().name("my-cool-server").transportType(McpTransportType.STDIO).command("x")
                .build().getName()).isEqualTo("my-cool-server");
    }

    @Test
    @DisplayName("STDIO without command throws IAE")
    void stdioWithoutCommand() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> McpServerConfig.builder().name("a").transportType(McpTransportType.STDIO).build())
                .withMessageContaining("command is required for STDIO");
    }

    @Test
    @DisplayName("SSE without url throws IAE")
    void sseWithoutUrl() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> McpServerConfig.builder().name("a").transportType(McpTransportType.SSE).build())
                .withMessageContaining("url is required for SSE");
    }

    @Test
    @DisplayName("STREAMABLE_HTTP without url throws IAE")
    void streamableHttpWithoutUrl() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> McpServerConfig.builder().name("a").transportType(McpTransportType.STREAMABLE_HTTP).build())
                .withMessageContaining("url is required for STREAMABLE_HTTP");
    }

    @Test
    @DisplayName("McpTransportType enum values")
    void transportTypeEnumValues() {
        assertThat(McpTransportType.values()).containsExactly(McpTransportType.STDIO, McpTransportType.SSE,
                McpTransportType.STREAMABLE_HTTP);
        assertThat(McpTransportType.valueOf("STDIO")).isEqualTo(McpTransportType.STDIO);
    }
}
