package at.aimon.core.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Default {@link DocumentChunker} implementation that splits documents by markdown headers.
 *
 * <p>
 * Splitting strategy:
 * <ol>
 * <li>Split on markdown headers ({@code #}, {@code ##}, {@code ###}, etc.)
 * <li>If no headers are found (e.g., plain text), fall back to character-count-based splitting
 * <li>Sections exceeding {@code chunkSize} are further split by line boundaries
 * <li>Consecutive chunks overlap by {@code overlap} characters for context continuity
 * </ol>
 *
 * @see DocumentChunker
 * @see DocumentChunk
 */
public final class SimpleDocumentChunker implements DocumentChunker {

    /** Default chunk size in characters. */
    public static final int DEFAULT_CHUNK_SIZE = 500;

    /** Default overlap in characters. */
    public static final int DEFAULT_OVERLAP = 50;

    private static final Pattern MARKDOWN_HEADER_PATTERN = Pattern.compile("(?m)^#{1,6}\\s+");

    private final int chunkSize;
    private final int overlap;

    /**
     * Creates a chunker with default settings (500 chars, 50 overlap).
     */
    public SimpleDocumentChunker() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    /**
     * Creates a chunker with the specified settings.
     *
     * @param chunkSize
     *            the target chunk size in characters (must be > 0)
     * @param overlap
     *            the overlap between consecutive chunks in characters (must be &gt;= 0 and &lt; chunkSize)
     * @throws IllegalArgumentException
     *             if chunkSize &lt;= 0 or overlap is invalid
     */
    public SimpleDocumentChunker(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0, got: " + chunkSize);
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap must be >= 0, got: " + overlap);
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "overlap must be < chunkSize, got: overlap=" + overlap + ", chunkSize=" + chunkSize);
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<DocumentChunk> chunk(String documentPath, String content) {
        Objects.requireNonNull(documentPath, "documentPath must not be null");
        Objects.requireNonNull(content, "content must not be null");

        if (content.isEmpty()) {
            return Collections.emptyList();
        }

        final List<String> sections = splitBySections(content);
        final List<String> rawChunks = new ArrayList<>();

        for (String section : sections) {
            if (section.length() <= chunkSize) {
                rawChunks.add(section);
            } else {
                rawChunks.addAll(splitByLines(section));
            }
        }

        return applyOverlapAndBuild(documentPath, rawChunks);
    }

    /**
     * Returns the configured chunk size.
     *
     * @return the chunk size in characters
     */
    public int getChunkSize() {
        return chunkSize;
    }

    /**
     * Returns the configured overlap.
     *
     * @return the overlap in characters
     */
    public int getOverlap() {
        return overlap;
    }

    private List<String> splitBySections(String content) {
        final boolean hasHeaders = MARKDOWN_HEADER_PATTERN.matcher(content).find();
        if (!hasHeaders) {
            return splitBySize(content);
        }

        final List<String> sections = new ArrayList<>();
        final String[] lines = content.split("\n", -1);
        final StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (MARKDOWN_HEADER_PATTERN.matcher(line).find() && !current.isEmpty()) {
                final String sectionText = current.toString().trim();
                if (!sectionText.isEmpty()) {
                    sections.add(sectionText);
                }
                current.setLength(0);
            }
            current.append(line).append('\n');
        }

        if (!current.isEmpty()) {
            final String sectionText = current.toString().trim();
            if (!sectionText.isEmpty()) {
                sections.add(sectionText);
            }
        }

        return sections;
    }

    private List<String> splitBySize(String content) {
        final List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < content.length()) {
            final int end = Math.min(start + chunkSize, content.length());
            final String chunk = content.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            start += chunkSize;
        }

        return chunks;
    }

    private List<String> splitByLines(String section) {
        final List<String> chunks = new ArrayList<>();
        final String[] lines = section.split("\n", -1);
        final StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (current.length() + line.length() + 1 > chunkSize && !current.isEmpty()) {
                final String chunkText = current.toString().trim();
                if (!chunkText.isEmpty()) {
                    chunks.add(chunkText);
                }
                current.setLength(0);
            }
            current.append(line).append('\n');
        }

        if (!current.isEmpty()) {
            final String chunkText = current.toString().trim();
            if (!chunkText.isEmpty()) {
                chunks.add(chunkText);
            }
        }

        return chunks;
    }

    private List<DocumentChunk> applyOverlapAndBuild(String documentPath, List<String> rawChunks) {
        final List<DocumentChunk> result = new ArrayList<>(rawChunks.size());

        for (int i = 0; i < rawChunks.size(); i++) {
            String chunkContent = rawChunks.get(i);

            if (overlap > 0 && i > 0) {
                final String previous = rawChunks.get(i - 1);
                final int overlapStart = Math.max(0, previous.length() - overlap);
                final String overlapText = previous.substring(overlapStart).trim();
                if (!overlapText.isEmpty()) {
                    chunkContent = overlapText + "\n" + chunkContent;
                }
            }

            result.add(DocumentChunk.builder().documentPath(documentPath).content(chunkContent).chunkIndex(i).build());
        }

        return result;
    }
}
