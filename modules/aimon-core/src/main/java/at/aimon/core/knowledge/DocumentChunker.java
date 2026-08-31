package at.aimon.core.knowledge;

import java.util.List;

/**
 * Splits document text into searchable chunks.
 *
 * <p>
 * Implementations define how documents are divided into meaningful segments for indexing and search. The chunking
 * strategy affects search quality: semantic boundaries (e.g., markdown sections) produce better results than arbitrary
 * splits.
 *
 * @see SimpleDocumentChunker
 * @see DocumentChunk
 */
public interface DocumentChunker {

    /**
     * Splits a document into chunks.
     *
     * @param documentPath
     *            the VFS path of the source document (must not be null)
     * @param content
     *            the full text content of the document (must not be null)
     * @return a list of chunks in document order; empty list if the content is empty
     * @throws NullPointerException
     *             if documentPath or content is null
     */
    List<DocumentChunk> chunk(String documentPath, String content);
}
