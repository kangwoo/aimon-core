package at.aimon.core.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StatusEntryTest {

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> StatusEntry.of(null, "value")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullValue() {
        assertThatThrownBy(() -> StatusEntry.of("name", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldStoreNameAndValue() {
        StatusEntry entry = StatusEntry.of("Version", "1.0.0");

        assertThat(entry.getName()).isEqualTo("Version");
        assertThat(entry.getValue()).isEqualTo("1.0.0");
    }

    @Test
    void equalsShouldWorkCorrectly() {
        StatusEntry a = StatusEntry.of("name", "value");
        StatusEntry b = StatusEntry.of("name", "value");
        StatusEntry c = StatusEntry.of("name", "other");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toStringShouldContainFields() {
        StatusEntry entry = StatusEntry.of("Version", "1.0.0");

        assertThat(entry.toString()).contains("Version").contains("1.0.0");
    }
}
