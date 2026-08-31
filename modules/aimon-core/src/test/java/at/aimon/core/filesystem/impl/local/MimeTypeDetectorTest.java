package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MimeTypeDetector Tests")
class MimeTypeDetectorTest {

    @Test
    @DisplayName("Should detect known text extensions")
    void detectMimeTypeByExtension_knownTextExtensions() {
        assertThat(MimeTypeDetector.detectMimeTypeByExtension("file.txt")).isEqualTo("text/plain");
        assertThat(MimeTypeDetector.detectMimeTypeByExtension("page.html")).isEqualTo("text/html");
        assertThat(MimeTypeDetector.detectMimeTypeByExtension("data.json")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("Should detect known image extensions")
    void detectMimeTypeByExtension_knownImageExtensions() {
        assertThat(MimeTypeDetector.detectMimeTypeByExtension("photo.jpg")).isEqualTo("image/jpeg");
        assertThat(MimeTypeDetector.detectMimeTypeByExtension("icon.png")).isEqualTo("image/png");
    }

    @Test
    @DisplayName("Should detect known document extensions")
    void detectMimeTypeByExtension_knownDocumentExtensions() {
        assertThat(MimeTypeDetector.detectMimeTypeByExtension("report.pdf")).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("Should return null for unknown extension")
    void detectMimeTypeByExtension_unknownExtension() {
        assertThat(MimeTypeDetector.detectMimeTypeByExtension("file.xyz")).isNull();
    }

    @Test
    @DisplayName("Should return null for file without extension")
    void detectMimeTypeByExtension_noExtension() {
        assertThat(MimeTypeDetector.detectMimeTypeByExtension("Makefile")).isNull();
    }

    @Test
    @DisplayName("Should return null for dot-only filename")
    void detectMimeTypeByExtension_dotOnly() {
        assertThat(MimeTypeDetector.detectMimeTypeByExtension("file.")).isNull();
    }

    @Test
    @DisplayName("Should be case-insensitive for extensions")
    void detectMimeTypeByExtension_caseInsensitive() {
        assertThat(MimeTypeDetector.detectMimeTypeByExtension("photo.JPG")).isEqualTo("image/jpeg");
        assertThat(MimeTypeDetector.detectMimeTypeByExtension("page.HTML")).isEqualTo("text/html");
    }
}
