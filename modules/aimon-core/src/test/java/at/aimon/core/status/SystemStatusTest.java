package at.aimon.core.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class SystemStatusTest {

    @Test
    void shouldBuildWithSections() {
        StatusSection appSection = StatusSection.builder("Application").entry("Version", "1.0.0").build();
        StatusSection compSection = StatusSection.builder("Components").entry("Tools", "5").build();

        SystemStatus status = SystemStatus.builder().section(appSection).section(compSection).build();

        assertThat(status.getSections()).hasSize(2);
        assertThat(status.getSections().get(0).getTitle()).isEqualTo("Application");
        assertThat(status.getSections().get(1).getTitle()).isEqualTo("Components");
    }

    @Test
    void shouldBuildWithNoSections() {
        SystemStatus status = SystemStatus.builder().build();

        assertThat(status.getSections()).isEmpty();
    }

    @Test
    void builderShouldRejectNullSection() {
        assertThatThrownBy(() -> SystemStatus.builder().section(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void sectionsShouldBeUnmodifiable() {
        SystemStatus status = SystemStatus.builder().section(StatusSection.builder("Test").build()).build();
        List<StatusSection> sections = status.getSections();

        assertThatThrownBy(() -> sections.add(StatusSection.builder("New").build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equalsShouldWorkCorrectly() {
        StatusSection section = StatusSection.builder("App").entry("Version", "1.0").build();
        SystemStatus a = SystemStatus.builder().section(section).build();
        SystemStatus b = SystemStatus.builder().section(section).build();
        SystemStatus c = SystemStatus.builder().build();

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toStringShouldContainSections() {
        SystemStatus status = SystemStatus.builder().section(StatusSection.builder("App").build()).build();

        assertThat(status.toString()).contains("sections");
    }
}
