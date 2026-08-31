package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.VirtualFileSystem;

@DisplayName("WikiSource")
class WikiSourceTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("valid arguments create instance with expected fields")
        void validConstruction() {
            VirtualFileSystem fs = mock(VirtualFileSystem.class);
            WikiSource source = new WikiSource(fs, "/raw/articles");

            assertThat(source.getFileSystem()).isSameAs(fs);
            assertThat(source.getDirectory()).isEqualTo("/raw/articles");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("null fileSystem throws NullPointerException")
        void nullFileSystemThrowsNpe() {
            assertThatThrownBy(() -> new WikiSource(null, "/raw/articles")).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null directory throws NullPointerException")
        void nullDirectoryThrowsNpe() {
            VirtualFileSystem fs = mock(VirtualFileSystem.class);
            assertThatThrownBy(() -> new WikiSource(fs, null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("empty directory throws IllegalArgumentException")
        void emptyDirectoryThrowsIae() {
            VirtualFileSystem fs = mock(VirtualFileSystem.class);
            assertThatThrownBy(() -> new WikiSource(fs, "")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringMethod {

        @Test
        @DisplayName("contains directory")
        void toStringContainsDirectory() {
            VirtualFileSystem fs = mock(VirtualFileSystem.class);
            WikiSource source = new WikiSource(fs, "/raw/articles");

            assertThat(source.toString()).contains("/raw/articles");
        }
    }
}
