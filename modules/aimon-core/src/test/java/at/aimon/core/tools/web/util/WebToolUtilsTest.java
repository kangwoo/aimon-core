package at.aimon.core.tools.web.util;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WebToolUtils Tests")
class WebToolUtilsTest {

    @Nested
    @DisplayName("clamp")
    class Clamp {

        @Test
        @DisplayName("Should return value when within range")
        void testWithinRange() {
            assertThat(WebToolUtils.clamp(5, 1, 10)).isEqualTo(5);
        }

        @Test
        @DisplayName("Should return min when value is below range")
        void testBelowRange() {
            assertThat(WebToolUtils.clamp(-1, 1, 10)).isEqualTo(1);
        }

        @Test
        @DisplayName("Should return max when value is above range")
        void testAboveRange() {
            assertThat(WebToolUtils.clamp(15, 1, 10)).isEqualTo(10);
        }

        @Test
        @DisplayName("Should return boundary values")
        void testBoundaryValues() {
            assertThat(WebToolUtils.clamp(1, 1, 10)).isEqualTo(1);
            assertThat(WebToolUtils.clamp(10, 1, 10)).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("truncate")
    class Truncate {

        @Test
        @DisplayName("Should return original string when within limit")
        void testWithinLimit() {
            assertThat(WebToolUtils.truncate("hello", 10)).isEqualTo("hello");
        }

        @Test
        @DisplayName("Should truncate string when exceeding limit")
        void testExceedingLimit() {
            assertThat(WebToolUtils.truncate("hello world", 5)).isEqualTo("hello");
        }

        @Test
        @DisplayName("Should return null for null input")
        void testNullInput() {
            assertThat(WebToolUtils.truncate(null, 10)).isNull();
        }

        @Test
        @DisplayName("Should return exact length string unchanged")
        void testExactLength() {
            assertThat(WebToolUtils.truncate("hello", 5)).isEqualTo("hello");
        }

        @Test
        @DisplayName("Should handle empty string")
        void testEmptyString() {
            assertThat(WebToolUtils.truncate("", 5)).isEmpty();
        }
    }

    @Nested
    @DisplayName("isValidHttpUrl")
    class IsValidHttpUrl {

        @Test
        @DisplayName("Should accept http URLs")
        void testHttpUrl() {
            assertThat(WebToolUtils.isValidHttpUrl("http://example.com")).isTrue();
        }

        @Test
        @DisplayName("Should accept https URLs")
        void testHttpsUrl() {
            assertThat(WebToolUtils.isValidHttpUrl("https://example.com")).isTrue();
        }

        @Test
        @DisplayName("Should reject ftp URLs")
        void testFtpUrl() {
            assertThat(WebToolUtils.isValidHttpUrl("ftp://example.com")).isFalse();
        }

        @Test
        @DisplayName("Should reject file URLs")
        void testFileUrl() {
            assertThat(WebToolUtils.isValidHttpUrl("file:///etc/passwd")).isFalse();
        }

        @Test
        @DisplayName("Should reject null")
        void testNull() {
            assertThat(WebToolUtils.isValidHttpUrl(null)).isFalse();
        }

        @Test
        @DisplayName("Should reject blank string")
        void testBlank() {
            assertThat(WebToolUtils.isValidHttpUrl("")).isFalse();
            assertThat(WebToolUtils.isValidHttpUrl("   ")).isFalse();
        }

        @Test
        @DisplayName("Should be case-insensitive")
        void testCaseInsensitive() {
            assertThat(WebToolUtils.isValidHttpUrl("HTTP://EXAMPLE.COM")).isTrue();
            assertThat(WebToolUtils.isValidHttpUrl("HTTPS://EXAMPLE.COM")).isTrue();
        }

        @Test
        @DisplayName("Should reject URLs without host")
        void testNoHost() {
            assertThat(WebToolUtils.isValidHttpUrl("http://")).isFalse();
        }

        @Test
        @DisplayName("Should reject malformed URLs")
        void testMalformedUrl() {
            assertThat(WebToolUtils.isValidHttpUrl("http://[invalid")).isFalse();
        }
    }

    @Nested
    @DisplayName("buildCacheKey")
    class BuildCacheKey {

        @Test
        @DisplayName("Should join parts with null character separator")
        void testJoinParts() {
            String key = WebToolUtils.buildCacheKey("a", "b", "c");
            assertThat(key).isEqualTo("a\0b\0c");
        }

        @Test
        @DisplayName("Should handle null parts as empty strings")
        void testNullParts() {
            String key = WebToolUtils.buildCacheKey("a", null, "c");
            assertThat(key).isEqualTo("a\0\0c");
        }

        @Test
        @DisplayName("Should handle single part")
        void testSinglePart() {
            String key = WebToolUtils.buildCacheKey("query");
            assertThat(key).isEqualTo("query");
        }

        @Test
        @DisplayName("Should handle empty array")
        void testEmptyArray() {
            String key = WebToolUtils.buildCacheKey();
            assertThat(key).isEmpty();
        }

        @Test
        @DisplayName("Should produce different keys for different parameter orderings")
        void testKeyUniqueness() {
            String key1 = WebToolUtils.buildCacheKey("a", "b");
            String key2 = WebToolUtils.buildCacheKey("b", "a");
            assertThat(key1).isNotEqualTo(key2);
        }
    }
}
