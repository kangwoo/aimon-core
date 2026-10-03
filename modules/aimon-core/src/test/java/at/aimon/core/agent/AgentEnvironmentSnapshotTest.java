package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.UserLocale;

@DisplayName("AgentEnvironmentSnapshot Tests")
class AgentEnvironmentSnapshotTest {

    private static AgentEnvironmentSnapshot.Builder validBuilder() {
        return AgentEnvironmentSnapshot.builder().workingDirectory("/workspace/project")
                .currentDate(Instant.parse("2026-04-23T00:00:00Z")).userLocale(UserLocale.createDefault());
    }

    @Test
    @DisplayName("Builder produces instance with all fields populated")
    void build_withAllFields() {
        Instant now = Instant.parse("2026-04-23T12:34:56Z");
        UserLocale userLocale = UserLocale.createDefault();
        Map<String, String> extensions = Map.of("branch", "main", "user", "alice");

        AgentEnvironmentSnapshot snapshot = AgentEnvironmentSnapshot.builder().workingDirectory("/wd").currentDate(now)
                .userLocale(userLocale).extensions(extensions).build();

        assertThat(snapshot.getWorkingDirectory()).isEqualTo("/wd");
        assertThat(snapshot.getCurrentDate()).isEqualTo(now);
        assertThat(snapshot.getUserLocale()).isEqualTo(userLocale);
        assertThat(snapshot.getExtensions()).isEqualTo(extensions);
    }

    @Test
    @DisplayName("Builder defaults extensions to empty immutable map when not set")
    void build_defaultExtensionsIsEmpty() {
        AgentEnvironmentSnapshot snapshot = validBuilder().build();

        assertThat(snapshot.getExtensions()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("build() throws NPE when workingDirectory is missing")
    void build_missingWorkingDirectory_throwsNPE() {
        AgentEnvironmentSnapshot.Builder b = AgentEnvironmentSnapshot.builder().currentDate(Instant.now())
                .userLocale(UserLocale.createDefault());

        assertThatThrownBy(b::build).isInstanceOf(NullPointerException.class).hasMessageContaining("workingDirectory");
    }

    @Test
    @DisplayName("build() throws NPE when currentDate is missing")
    void build_missingCurrentDate_throwsNPE() {
        AgentEnvironmentSnapshot.Builder b = AgentEnvironmentSnapshot.builder().workingDirectory("/wd")
                .userLocale(UserLocale.createDefault());

        assertThatThrownBy(b::build).isInstanceOf(NullPointerException.class).hasMessageContaining("currentDate");
    }

    @Test
    @DisplayName("build() throws NPE when userLocale is missing")
    void build_missingUserLocale_throwsNPE() {
        AgentEnvironmentSnapshot.Builder b = AgentEnvironmentSnapshot.builder().workingDirectory("/wd")
                .currentDate(Instant.now());

        assertThatThrownBy(b::build).isInstanceOf(NullPointerException.class).hasMessageContaining("userLocale");
    }

    @Test
    @DisplayName("Builder setters reject null arguments")
    void builder_setters_rejectNull() {
        AgentEnvironmentSnapshot.Builder b = AgentEnvironmentSnapshot.builder();

        assertThatThrownBy(() -> b.workingDirectory(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("workingDirectory");
        assertThatThrownBy(() -> b.currentDate(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currentDate");
        assertThatThrownBy(() -> b.userLocale(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userLocale");
        assertThatThrownBy(() -> b.extensions(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("extensions");
    }

    @Test
    @DisplayName("Extensions are defensively copied: mutating caller's map does not affect built instance")
    void extensions_defensivelyCopied() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("k1", "v1");

        AgentEnvironmentSnapshot snapshot = validBuilder().extensions(mutable).build();

        // Mutate caller's map after build
        mutable.put("k2", "v2");
        mutable.remove("k1");

        assertThat(snapshot.getExtensions()).containsExactly(Map.entry("k1", "v1"));
    }

    @Test
    @DisplayName("Returned extensions map is immutable")
    void extensions_returnedMapIsImmutable() {
        AgentEnvironmentSnapshot snapshot = validBuilder().extensions(Map.of("a", "1")).build();

        assertThatThrownBy(() -> snapshot.getExtensions().put("b", "2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("equals and hashCode reflect all fields")
    void equalsAndHashCode() {
        Instant fixed = Instant.parse("2026-04-23T00:00:00Z");
        UserLocale userLocale = UserLocale.createDefault();

        AgentEnvironmentSnapshot a = AgentEnvironmentSnapshot.builder().workingDirectory("/wd").currentDate(fixed)
                .userLocale(userLocale).extensions(Map.of("x", "1")).build();
        AgentEnvironmentSnapshot b = AgentEnvironmentSnapshot.builder().workingDirectory("/wd").currentDate(fixed)
                .userLocale(userLocale).extensions(Map.of("x", "1")).build();
        AgentEnvironmentSnapshot c = AgentEnvironmentSnapshot.builder().workingDirectory("/other").currentDate(fixed)
                .userLocale(userLocale).extensions(Map.of("x", "1")).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("string");
    }

    @Test
    @DisplayName("toString contains key fields")
    void toString_containsKeyData() {
        Instant fixed = Instant.parse("2026-04-23T00:00:00Z");
        AgentEnvironmentSnapshot snapshot = AgentEnvironmentSnapshot.builder().workingDirectory("/wd")
                .currentDate(fixed).userLocale(UserLocale.createDefault()).extensions(Map.of("branch", "main")).build();

        String s = snapshot.toString();

        assertThat(s).contains("workingDirectory").contains("/wd").contains("currentDate").contains(fixed.toString())
                .contains("userLocale").contains("extensions").contains("branch").contains("main");
    }
}
