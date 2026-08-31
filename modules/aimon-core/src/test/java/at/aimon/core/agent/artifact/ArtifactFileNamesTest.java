package at.aimon.core.agent.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ArtifactFileNames Tests")
class ArtifactFileNamesTest {

    @Test
    @DisplayName("Should extract file name from Unix path")
    void shouldExtractFileNameFromUnixPath() {
        assertThat(ArtifactFileNames.extractFileName("/reports/sales-2024.csv")).isEqualTo("sales-2024.csv");
    }

    @Test
    @DisplayName("Should extract file name from Windows path")
    void shouldExtractFileNameFromWindowsPath() {
        assertThat(ArtifactFileNames.extractFileName("C:\\reports\\sales.csv")).isEqualTo("sales.csv");
    }

    @Test
    @DisplayName("Should handle trailing slash")
    void shouldHandleTrailingSlash() {
        assertThat(ArtifactFileNames.extractFileName("/reports/")).isEqualTo("reports");
    }

    @Test
    @DisplayName("Should handle multiple trailing slashes")
    void shouldHandleMultipleTrailingSlashes() {
        assertThat(ArtifactFileNames.extractFileName("/reports///")).isEqualTo("reports");
    }

    @Test
    @DisplayName("Should handle file name only (no path separator)")
    void shouldHandleFileNameOnly() {
        assertThat(ArtifactFileNames.extractFileName("file.txt")).isEqualTo("file.txt");
    }

    @Test
    @DisplayName("Should return null for null input")
    void shouldReturnNullForNullInput() {
        assertThat(ArtifactFileNames.extractFileName(null)).isNull();
    }

    @Test
    @DisplayName("Should return empty string for empty input")
    void shouldReturnEmptyForEmptyInput() {
        assertThat(ArtifactFileNames.extractFileName("")).isEmpty();
    }

    @Test
    @DisplayName("Should handle root path")
    void shouldHandleRootPath() {
        assertThat(ArtifactFileNames.extractFileName("/")).isEqualTo("/");
    }

    @Test
    @DisplayName("Should handle deeply nested path")
    void shouldHandleDeeplyNestedPath() {
        assertThat(ArtifactFileNames.extractFileName("/a/b/c/d/file.png")).isEqualTo("file.png");
    }

}
