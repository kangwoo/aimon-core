package at.aimon.core.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class StatusSectionTest {

    @Test
    void builderShouldRejectNullTitle() {
        assertThatThrownBy(() -> StatusSection.builder(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldBuildWithTitleAndEntries() {
        StatusSection section = StatusSection.builder("Application").entry("Version", "1.0.0").entry("Java", "17.0.1")
                .build();

        assertThat(section.getTitle()).isEqualTo("Application");
        assertThat(section.getEntries()).hasSize(2);
        assertThat(section.getEntries().get(0)).isEqualTo(StatusEntry.of("Version", "1.0.0"));
        assertThat(section.getEntries().get(1)).isEqualTo(StatusEntry.of("Java", "17.0.1"));
    }

    @Test
    void shouldBuildWithEmptyEntries() {
        StatusSection section = StatusSection.builder("Empty").build();

        assertThat(section.getTitle()).isEqualTo("Empty");
        assertThat(section.getEntries()).isEmpty();
    }

    @Test
    void entriesShouldBeUnmodifiable() {
        StatusSection section = StatusSection.builder("Test").entry("key", "value").build();
        List<StatusEntry> entries = section.getEntries();

        assertThatThrownBy(() -> entries.add(StatusEntry.of("new", "entry")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldAcceptIntValue() {
        StatusSection section = StatusSection.builder("Stats").entry("Count", 42).build();

        assertThat(section.getEntries()).hasSize(1);
        assertThat(section.getEntries().get(0)).isEqualTo(StatusEntry.of("Count", "42"));
    }

    @Test
    void shouldAcceptLongValue() {
        StatusSection section = StatusSection.builder("Stats").entry("Total", 100_000L).build();

        assertThat(section.getEntries()).hasSize(1);
        assertThat(section.getEntries().get(0)).isEqualTo(StatusEntry.of("Total", "100000"));
    }

    @Test
    void equalsShouldWorkCorrectly() {
        StatusSection a = StatusSection.builder("Title").entry("k", "v").build();
        StatusSection b = StatusSection.builder("Title").entry("k", "v").build();
        StatusSection c = StatusSection.builder("Other").entry("k", "v").build();

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toStringShouldContainTitle() {
        StatusSection section = StatusSection.builder("Application").build();

        assertThat(section.toString()).contains("Application");
    }
}
