package at.aimon.core.tools.web.fetch;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JsoupContentExtractor Tests")
class JsoupContentExtractorTest {

    private JsoupContentExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new JsoupContentExtractor();
    }

    @Nested
    @DisplayName("extract - text mode")
    class TextMode {

        @Test
        @DisplayName("Should extract plain text from HTML")
        void testPlainTextExtraction() {
            String html = "<html><body><p>Hello World</p></body></html>";
            String result = extractor.extract(html, "http://example.com", "text");
            assertThat(result).contains("Hello World");
        }

        @Test
        @DisplayName("Should remove script and style elements")
        void testRemoveNoise() {
            String html = "<html><body>" + "<script>alert('xss')</script>" + "<style>.hidden{display:none}</style>"
                    + "<p>Visible content</p>" + "</body></html>";
            String result = extractor.extract(html, "http://example.com", "text");
            assertThat(result).contains("Visible content");
            assertThat(result).doesNotContain("alert");
            assertThat(result).doesNotContain("display:none");
        }

        @Test
        @DisplayName("Should remove navigation and footer")
        void testRemoveNavFooter() {
            String html = "<html><body>" + "<nav>Menu items</nav>" + "<p>Main content</p>"
                    + "<footer>Footer info</footer>" + "</body></html>";
            String result = extractor.extract(html, "http://example.com", "text");
            assertThat(result).contains("Main content");
            assertThat(result).doesNotContain("Menu items");
            assertThat(result).doesNotContain("Footer info");
        }
    }

    @Nested
    @DisplayName("extract - markdown mode")
    class MarkdownMode {

        @Test
        @DisplayName("Should convert headings")
        void testHeadings() {
            String html = "<html><body><article>" + "<h1>Title</h1>" + "<h2>Subtitle</h2>" + "<p>" + "x".repeat(250)
                    + "</p>" + "</article></body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).contains("# Title");
            assertThat(result).contains("## Subtitle");
        }

        @Test
        @DisplayName("Should convert links")
        void testLinks() {
            String html = "<html><body><article>" + "<a href=\"http://example.com\">Click here</a>" + "<p>"
                    + "x".repeat(250) + "</p>" + "</article></body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).contains("[Click here](http://example.com)");
        }

        @Test
        @DisplayName("Should convert bold and italic")
        void testBoldItalic() {
            String html = "<html><body><article>" + "<p><strong>bold</strong> and <em>italic</em></p>" + "<p>"
                    + "x".repeat(250) + "</p>" + "</article></body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).contains("**bold**");
            assertThat(result).contains("*italic*");
        }

        @Test
        @DisplayName("Should convert inline code")
        void testInlineCode() {
            String html = "<html><body><article>" + "<p>Use <code>System.out</code> for output</p>" + "<p>"
                    + "x".repeat(250) + "</p>" + "</article></body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).contains("`System.out`");
        }

        @Test
        @DisplayName("Should convert code blocks")
        void testCodeBlock() {
            String html = "<html><body><article>" + "<pre><code>int x = 1;\nint y = 2;</code></pre>" + "<p>"
                    + "x".repeat(250) + "</p>" + "</article></body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).contains("```\n");
            assertThat(result).contains("int x = 1;");
        }

        @Test
        @DisplayName("Should convert unordered lists")
        void testUnorderedList() {
            String html = "<html><body><article>" + "<ul><li>Item 1</li><li>Item 2</li></ul>" + "<p>" + "x".repeat(250)
                    + "</p>" + "</article></body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).contains("- Item 1");
            assertThat(result).contains("- Item 2");
        }

        @Test
        @DisplayName("Should convert ordered lists")
        void testOrderedList() {
            String html = "<html><body><article>" + "<ol><li>First</li><li>Second</li></ol>" + "<p>" + "x".repeat(250)
                    + "</p>" + "</article></body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).contains("1. First");
            assertThat(result).contains("2. Second");
        }

        @Test
        @DisplayName("Should convert blockquotes")
        void testBlockquote() {
            String html = "<html><body><article>" + "<blockquote>Quoted text</blockquote>" + "<p>" + "x".repeat(250)
                    + "</p>" + "</article></body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).contains("> Quoted text");
        }

        @Test
        @DisplayName("Should convert tables")
        void testTable() {
            String html = "<html><body><article>" + "<table><thead><tr><th>Name</th><th>Age</th></tr></thead>"
                    + "<tbody><tr><td>Alice</td><td>30</td></tr></tbody></table>" + "<p>" + "x".repeat(250) + "</p>"
                    + "</article></body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).contains("| Name | Age |");
            assertThat(result).contains("| --- |");
            assertThat(result).contains("| Alice | 30 |");
        }

        @Test
        @DisplayName("Should strip javascript: links and output text only")
        void testJavascriptLinks() {
            String html = "<html><body><article>" + "<a href=\"javascript:alert(1)\">Click me</a>" + "<p>"
                    + "x".repeat(250) + "</p>" + "</article></body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).contains("Click me");
            assertThat(result).doesNotContain("javascript:");
        }

        @Test
        @DisplayName("Should use article as main content when available")
        void testArticleMainContent() {
            String html = "<html><body>" + "<div>Sidebar content</div>" + "<article><p>" + "x".repeat(250)
                    + "</p></article>" + "</body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).doesNotContain("Sidebar content");
        }
    }

    @Nested
    @DisplayName("extract - edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Should return empty string for null HTML")
        void testNullHtml() {
            String result = extractor.extract(null, "http://example.com", "markdown");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty string for blank HTML")
        void testBlankHtml() {
            String result = extractor.extract("", "http://example.com", "markdown");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should handle null URL gracefully")
        void testNullUrl() {
            String html = "<html><body><p>Content</p></body></html>";
            String result = extractor.extract(html, null, "text");
            assertThat(result).contains("Content");
        }

        @Test
        @DisplayName("Should default to markdown mode")
        void testDefaultMode() {
            String html = "<html><body><article>" + "<h1>Title</h1>" + "<p>" + "x".repeat(250) + "</p>"
                    + "</article></body></html>";
            String result = extractor.extract(html, "http://example.com", "markdown");
            assertThat(result).contains("# Title");
        }

        @Test
        @DisplayName("Should fall back to body when no main content area found")
        void testFallbackToBody() {
            String html = "<html><body><p>Simple page content</p></body></html>";
            String result = extractor.extract(html, "http://example.com", "text");
            assertThat(result).contains("Simple page content");
        }
    }
}
