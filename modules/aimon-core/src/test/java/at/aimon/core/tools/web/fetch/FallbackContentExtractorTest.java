package at.aimon.core.tools.web.fetch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("FallbackContentExtractor Tests")
@ExtendWith(MockitoExtension.class)
class FallbackContentExtractorTest {

    @Mock
    private ContentExtractor primary;

    @Mock
    private ContentExtractor fallback;

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw for null primary")
        void testNullPrimary() {
            assertThatThrownBy(() -> new FallbackContentExtractor(null, fallback, 500))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for null fallback")
        void testNullFallback() {
            assertThatThrownBy(() -> new FallbackContentExtractor(primary, null, 500))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for minLength < 1")
        void testInvalidMinLength() {
            assertThatThrownBy(() -> new FallbackContentExtractor(primary, fallback, 0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minLength must be > 0");
        }

        @Test
        @DisplayName("Should accept minLength of 1")
        void testMinLengthOne() {
            assertThatCode(() -> new FallbackContentExtractor(primary, fallback, 1)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("extract - fallback logic")
    class ExtractLogic {

        @Test
        @DisplayName("Should return primary result when length >= minLength")
        void testPrimarySufficient() {
            String longContent = "A".repeat(500);
            when(primary.extract("html", "url", "markdown")).thenReturn(longContent);

            FallbackContentExtractor extractor = new FallbackContentExtractor(primary, fallback, 500);
            String result = extractor.extract("html", "url", "markdown");

            assertThat(result).isEqualTo(longContent);
            verify(primary).extract("html", "url", "markdown");
            verifyNoInteractions(fallback);
        }

        @Test
        @DisplayName("Should use fallback when primary result is shorter than minLength")
        void testFallbackUsed() {
            String shortContent = "short";
            String fallbackContent = "This is a much longer content from the fallback extractor.";
            when(primary.extract("html", "url", "markdown")).thenReturn(shortContent);
            when(fallback.extract("html", "url", "markdown")).thenReturn(fallbackContent);

            FallbackContentExtractor extractor = new FallbackContentExtractor(primary, fallback, 500);
            String result = extractor.extract("html", "url", "markdown");

            assertThat(result).isEqualTo(fallbackContent);
            verify(primary).extract("html", "url", "markdown");
            verify(fallback).extract("html", "url", "markdown");
        }

        @Test
        @DisplayName("Should use fallback when primary returns empty string")
        void testPrimaryEmpty() {
            when(primary.extract("html", "url", "text")).thenReturn("");
            when(fallback.extract("html", "url", "text")).thenReturn("fallback result");

            FallbackContentExtractor extractor = new FallbackContentExtractor(primary, fallback, 100);
            String result = extractor.extract("html", "url", "text");

            assertThat(result).isEqualTo("fallback result");
            verify(fallback).extract("html", "url", "text");
        }

        @Test
        @DisplayName("Should not use fallback when primary result equals minLength exactly")
        void testPrimaryExactlyMinLength() {
            String exactContent = "A".repeat(100);
            when(primary.extract("html", "url", "markdown")).thenReturn(exactContent);

            FallbackContentExtractor extractor = new FallbackContentExtractor(primary, fallback, 100);
            String result = extractor.extract("html", "url", "markdown");

            assertThat(result).isEqualTo(exactContent);
            verifyNoInteractions(fallback);
        }

        @Test
        @DisplayName("Should pass all parameters to both extractors")
        void testParameterPropagation() {
            when(primary.extract("my html", "https://example.com", "text")).thenReturn("x");
            when(fallback.extract("my html", "https://example.com", "text")).thenReturn("fallback");

            FallbackContentExtractor extractor = new FallbackContentExtractor(primary, fallback, 10);
            extractor.extract("my html", "https://example.com", "text");

            verify(primary).extract("my html", "https://example.com", "text");
            verify(fallback).extract("my html", "https://example.com", "text");
        }

        @Test
        @DisplayName("Should return empty fallback result when both extractors produce short content")
        void testBothShort() {
            when(primary.extract("html", "url", "markdown")).thenReturn("a");
            when(fallback.extract("html", "url", "markdown")).thenReturn("b");

            FallbackContentExtractor extractor = new FallbackContentExtractor(primary, fallback, 500);
            String result = extractor.extract("html", "url", "markdown");

            // Falls back and returns whatever fallback provides, even if short
            assertThat(result).isEqualTo("b");
        }

        @Test
        @DisplayName("Should use fallback when primary returns null")
        void testPrimaryReturnsNull() {
            when(primary.extract("html", "url", "markdown")).thenReturn(null);
            when(fallback.extract("html", "url", "markdown")).thenReturn("fallback result");

            FallbackContentExtractor extractor = new FallbackContentExtractor(primary, fallback, 100);
            String result = extractor.extract("html", "url", "markdown");

            assertThat(result).isEqualTo("fallback result");
            verify(fallback).extract("html", "url", "markdown");
        }
    }
}
