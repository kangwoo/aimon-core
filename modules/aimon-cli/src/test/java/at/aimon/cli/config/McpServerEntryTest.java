package at.aimon.cli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.mcp.McpServerConfig;
import at.aimon.core.mcp.McpServerConfig.AnnotationTrust;
import at.aimon.core.mcp.McpServerConfig.McpTransportType;

@DisplayName("McpServerEntry")
class McpServerEntryTest {

    private McpServerEntry entry;

    @BeforeEach
    void setUp() {
        entry = new McpServerEntry();
        entry.setName("github");
        entry.setTransportType("STDIO");
        entry.setCommand("npx");
    }

    @Test
    @DisplayName("An entry without annotationTrust converts to the default IGNORE")
    void defaultsToIgnore() {
        McpServerConfig config = entry.toMcpServerConfig();

        assertThat(config.getTransportType()).isEqualTo(McpTransportType.STDIO);
        assertThat(config.getAnnotationTrust()).isEqualTo(AnnotationTrust.IGNORE);
    }

    @Test
    @DisplayName("annotationTrust is parsed case-insensitively")
    void parsesCaseInsensitively() {
        entry.setAnnotationTrust("trust");

        assertThat(entry.toMcpServerConfig().getAnnotationTrust()).isEqualTo(AnnotationTrust.TRUST);

        entry.setAnnotationTrust("  Ignore  ");

        assertThat(entry.toMcpServerConfig().getAnnotationTrust()).isEqualTo(AnnotationTrust.IGNORE);
    }

    @Test
    @DisplayName("An unrecognised annotationTrust fails at startup instead of quietly meaning IGNORE")
    void rejectsUnknownValue() {
        entry.setAnnotationTrust("SOMETIMES");

        assertThatIllegalArgumentException().isThrownBy(() -> entry.toMcpServerConfig())
                .withMessageContaining("Invalid annotationTrust 'SOMETIMES'").withMessageContaining("github")
                .withMessageContaining("IGNORE, TRUST");
    }

    @Test
    @DisplayName("An invalid transportType still reports transportType, not annotationTrust")
    void transportTypeErrorUnaffected() {
        entry.setTransportType("CARRIER_PIGEON");

        assertThatIllegalArgumentException().isThrownBy(() -> entry.toMcpServerConfig())
                .withMessageContaining("Invalid transportType");
    }

    @Test
    @DisplayName("annotationTrust participates in equality, so two entries differing only in trust are different")
    void equalityIncludesAnnotationTrust() {
        McpServerEntry other = new McpServerEntry();
        other.setName("github");
        other.setTransportType("STDIO");
        other.setCommand("npx");

        assertThat(entry).isEqualTo(other).hasSameHashCodeAs(other);

        other.setAnnotationTrust("TRUST");

        assertThat(entry).isNotEqualTo(other);
    }
}
