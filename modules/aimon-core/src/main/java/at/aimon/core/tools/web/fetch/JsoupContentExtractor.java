package at.aimon.core.tools.web.fetch;

import java.util.List;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Jsoup-based HTML content extractor.
 *
 * <p>
 * Extracts main content from HTML documents and converts to markdown or plain text.
 * Uses DOM traversal for HTML-to-Markdown conversion.
 *
 * <h3>Extraction Strategy</h3>
 * <ol>
 * <li>Parse HTML with Jsoup
 * <li>Remove noise elements (script, style, nav, footer, header, ads)
 * <li>Find main content area (article, main, [role=main], .content)
 * <li>Convert to markdown or plain text based on extractMode
 * </ol>
 *
 * <h3>Markdown Conversion</h3>
 * <ul>
 * <li>h1-h6 to # headings
 * <li>p to paragraphs with double newline
 * <li>a to [text](url)
 * <li>strong/b to **bold**
 * <li>em/i to *italic*
 * <li>code to `backtick`
 * <li>pre to fenced code blocks
 * <li>ul/ol to - / 1. lists
 * <li>blockquote to &gt; prefix
 * <li>table to pipe tables
 * </ul>
 */
public class JsoupContentExtractor implements ContentExtractor {

    private static final List<String> MAIN_CONTENT_SELECTORS = List.of("article", "main", "[role=main]", ".content",
            ".post-content");

    private static final String NOISE_SELECTOR = "script, style, nav, footer, header, aside, "
            + ".ad, .advertisement, .sidebar, .menu, .cookie-banner";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern MULTIPLE_BLANK_LINES = Pattern.compile("\n{3,}");
    private static final int MIN_CONTENT_LENGTH = 200;

    @Override
    public String extract(String html, String url, String extractMode) {
        if (html == null || html.isBlank()) {
            return "";
        }

        Document doc = Jsoup.parse(html, url != null ? url : "");

        // Remove noise elements
        doc.select(NOISE_SELECTOR).remove();

        // Find main content
        Element mainContent = findMainContent(doc);
        if (mainContent == null) {
            mainContent = doc.body();
        }

        if (mainContent == null) {
            return "";
        }

        if ("text".equals(extractMode)) {
            return mainContent.text();
        }

        // Markdown mode (default)
        String markdown = convertToMarkdown(mainContent);
        return normalizeWhitespace(markdown);
    }

    private Element findMainContent(Document doc) {
        for (String selector : MAIN_CONTENT_SELECTORS) {
            Element el = doc.selectFirst(selector);
            if (el != null && el.text().length() > MIN_CONTENT_LENGTH) {
                return el;
            }
        }
        return null;
    }

    private String convertToMarkdown(Element element) {
        StringBuilder sb = new StringBuilder();
        convertNode(element, sb, 0);
        return sb.toString().trim();
    }

    private void convertNode(Node node, StringBuilder sb, int listDepth) {
        if (node instanceof TextNode textNode) {
            String text = textNode.getWholeText();
            if (!text.isBlank()) {
                sb.append(collapseWhitespace(text));
            }
            return;
        }

        if (!(node instanceof Element el)) {
            return;
        }
        String tag = el.tagName().toLowerCase();

        switch (tag) {
            case "h1" :
            case "h2" :
            case "h3" :
            case "h4" :
            case "h5" :
            case "h6" :
                convertHeading(el, sb, tag);
                break;
            case "p" :
                convertParagraph(el, sb, listDepth);
                break;
            case "a" :
                convertLink(el, sb, listDepth);
                break;
            case "strong" :
            case "b" :
                sb.append("**");
                convertChildren(el, sb, listDepth);
                sb.append("**");
                break;
            case "em" :
            case "i" :
                sb.append("*");
                convertChildren(el, sb, listDepth);
                sb.append("*");
                break;
            case "code" :
                if (!isInsidePre(el)) {
                    sb.append('`');
                    sb.append(el.text());
                    sb.append('`');
                } else {
                    sb.append(el.wholeText());
                }
                break;
            case "pre" :
                convertPreBlock(el, sb);
                break;
            case "ul" :
                convertUnorderedList(el, sb, listDepth);
                break;
            case "ol" :
                convertOrderedList(el, sb, listDepth);
                break;
            case "li" :
                convertChildren(el, sb, listDepth);
                break;
            case "blockquote" :
                convertBlockquote(el, sb);
                break;
            case "table" :
                convertTable(el, sb);
                break;
            case "br" :
                sb.append('\n');
                break;
            case "hr" :
                sb.append("\n---\n\n");
                break;
            case "img" :
                convertImage(el, sb);
                break;
            case "div" :
            case "section" :
            case "span" :
            default :
                convertChildren(el, sb, listDepth);
                break;
        }
    }

    private void convertHeading(Element el, StringBuilder sb, String tag) {
        int level = tag.charAt(1) - '0';
        sb.append("\n\n");
        sb.append("#".repeat(level));
        sb.append(' ');
        sb.append(el.text());
        sb.append("\n\n");
    }

    private void convertParagraph(Element el, StringBuilder sb, int listDepth) {
        sb.append("\n\n");
        convertChildren(el, sb, listDepth);
        sb.append("\n\n");
    }

    private void convertLink(Element el, StringBuilder sb, int listDepth) {
        String href = el.absUrl("href");
        String text = el.text();
        if (href.isEmpty() || text.isBlank() || href.toLowerCase().startsWith("javascript:")) {
            sb.append(text);
        } else {
            sb.append('[').append(text).append("](").append(href).append(')');
        }
    }

    private void convertPreBlock(Element el, StringBuilder sb) {
        Element codeEl = el.selectFirst("code");
        String code = (codeEl != null) ? codeEl.wholeText() : el.wholeText();
        sb.append("\n\n```\n");
        sb.append(code);
        if (!code.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("```\n\n");
    }

    private void convertUnorderedList(Element el, StringBuilder sb, int listDepth) {
        sb.append('\n');
        for (Element li : el.children()) {
            if ("li".equals(li.tagName().toLowerCase())) {
                sb.append("  ".repeat(listDepth));
                sb.append("- ");
                convertChildren(li, sb, listDepth + 1);
                sb.append('\n');
            }
        }
        sb.append('\n');
    }

    private void convertOrderedList(Element el, StringBuilder sb, int listDepth) {
        sb.append('\n');
        int index = 1;
        for (Element li : el.children()) {
            if ("li".equals(li.tagName().toLowerCase())) {
                sb.append("  ".repeat(listDepth));
                sb.append(index).append(". ");
                convertChildren(li, sb, listDepth + 1);
                sb.append('\n');
                index++;
            }
        }
        sb.append('\n');
    }

    private void convertBlockquote(Element el, StringBuilder sb) {
        String text = el.text();
        sb.append("\n\n");
        for (String line : text.split("\n")) {
            sb.append("> ").append(line).append('\n');
        }
        sb.append('\n');
    }

    private void convertTable(Element el, StringBuilder sb) {
        sb.append("\n\n");

        // Header row
        Element thead = el.selectFirst("thead");
        if (thead != null) {
            Element headerRow = thead.selectFirst("tr");
            if (headerRow != null) {
                appendTableRow(headerRow, sb, "th");
                // Separator
                int cols = headerRow.select("th").size();
                if (cols == 0) {
                    cols = headerRow.select("td").size();
                }
                sb.append('|');
                for (int i = 0; i < cols; i++) {
                    sb.append(" --- |");
                }
                sb.append('\n');
            }
        }

        // Body rows
        Element tbody = el.selectFirst("tbody");
        Element bodySource = tbody != null ? tbody : el;
        for (Element row : bodySource.select("tr")) {
            // Skip header rows if already handled
            if (thead != null && row.parent() == thead) {
                continue;
            }
            appendTableRow(row, sb, "td");
        }
        sb.append('\n');
    }

    private void appendTableRow(Element row, StringBuilder sb, String cellTag) {
        sb.append('|');
        for (Element cell : row.select(cellTag)) {
            sb.append(' ').append(cell.text()).append(" |");
        }
        // Fallback: if cellTag didn't match, try the other
        if (row.select(cellTag).isEmpty()) {
            String fallback = "th".equals(cellTag) ? "td" : "th";
            for (Element cell : row.select(fallback)) {
                sb.append(' ').append(cell.text()).append(" |");
            }
        }
        sb.append('\n');
    }

    private void convertImage(Element el, StringBuilder sb) {
        String src = el.absUrl("src");
        String alt = el.attr("alt");
        if (!src.isEmpty()) {
            sb.append("![").append(alt != null ? alt : "").append("](").append(src).append(')');
        }
    }

    private void convertChildren(Element parent, StringBuilder sb, int listDepth) {
        for (Node child : parent.childNodes()) {
            convertNode(child, sb, listDepth);
        }
    }

    private boolean isInsidePre(Element el) {
        Element parent = el.parent();
        while (parent != null) {
            if ("pre".equals(parent.tagName().toLowerCase())) {
                return true;
            }
            parent = parent.parent();
        }
        return false;
    }

    private String collapseWhitespace(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ");
    }

    private String normalizeWhitespace(String text) {
        return MULTIPLE_BLANK_LINES.matcher(text).replaceAll("\n\n").trim();
    }
}
