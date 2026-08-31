package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RunId — deterministic run:<script>[:<disc>] identifier")
class RunIdTest {

    @Test
    @DisplayName("from(scriptName) yields run:<name> with no discriminator")
    void fromScriptName() {
        final RunId id = RunId.from("nightly-audit");

        assertThat(id.value()).isEqualTo("run:nightly-audit");
        assertThat(id.scriptName()).isEqualTo("nightly-audit");
        assertThat(id.discriminator()).isEmpty();
        assertThat(id).hasToString("run:nightly-audit");
    }

    @Test
    @DisplayName("from(scriptName, discriminator) yields run:<name>:<disc>")
    void fromScriptNameAndDiscriminator() {
        final RunId id = RunId.from("nightly-audit", "2026-07-22");

        assertThat(id.value()).isEqualTo("run:nightly-audit:2026-07-22");
        assertThat(id.scriptName()).isEqualTo("nightly-audit");
        assertThat(id.discriminator()).contains("2026-07-22");
    }

    @Test
    @DisplayName("of(value) round-trips both forms")
    void ofRoundTrips() {
        assertThat(RunId.of("run:audit")).isEqualTo(RunId.from("audit"));
        assertThat(RunId.of("run:audit:acme")).isEqualTo(RunId.from("audit", "acme"));
    }

    @Test
    @DisplayName("deterministic: same inputs produce equal ids (equals/hashCode on value)")
    void deterministicEquality() {
        assertThat(RunId.from("s", "d")).isEqualTo(RunId.from("s", "d")).hasSameHashCodeAs(RunId.from("s", "d"));
        assertThat(RunId.from("s")).isNotEqualTo(RunId.from("s", "d"));
    }

    @Test
    @DisplayName("of rejects a null or wrong-prefix or empty-segment value")
    void ofRejectsInvalid() {
        assertThatThrownBy(() -> RunId.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RunId.of("audit")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunId.of("run:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunId.of("run::disc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunId.of("run:name:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunId.of("run:name:a:b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("from rejects null/blank/colon segments")
    void fromRejectsInvalidSegments() {
        assertThatThrownBy(() -> RunId.from(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunId.from("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunId.from("a:b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunId.from("s", "d:x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunId.from("s", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
