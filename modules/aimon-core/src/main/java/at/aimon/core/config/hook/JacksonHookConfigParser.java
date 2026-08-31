package at.aimon.core.config.hook;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson-backed parser that turns Claude Code-compatible {@code hooks.json} bytes into a {@link HookConfigDocument}.
 *
 * <p>
 * Pure DTO marshalling &mdash; no IO retries, no merging, no event-name resolution. Higher-level concerns belong to
 * {@link HookConfigLoader}.
 *
 * <p>
 * Thread-safe; the underlying {@link ObjectMapper} is configured once at construction.
 */
public final class JacksonHookConfigParser {

    private final ObjectMapper objectMapper;

    /**
     * Creates a parser that owns a fresh {@link ObjectMapper}.
     */
    public JacksonHookConfigParser() {
        this(new ObjectMapper());
    }

    /**
     * Creates a parser sharing the supplied {@link ObjectMapper}.
     *
     * @param objectMapper
     *            JSON mapper (must not be null)
     */
    public JacksonHookConfigParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    /**
     * Parses a JSON document from a string.
     *
     * @param json
     *            JSON text (must not be null)
     * @return parsed document (never null; empty when {@code json} is blank or {@code "null"}})
     * @throws HookConfigParseException
     *             when the bytes are not valid JSON or the structure does not match the DTO
     */
    public HookConfigDocument parse(String json) {
        Objects.requireNonNull(json, "json cannot be null");
        if (json.isBlank() || "null".equals(json.strip())) {
            return HookConfigDocument.empty();
        }
        try {
            final HookConfigDocument doc = objectMapper.readValue(json, HookConfigDocument.class);
            return doc == null ? HookConfigDocument.empty() : doc;
        } catch (JsonProcessingException e) {
            throw new HookConfigParseException("Failed to parse hooks JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Parses a JSON document from an {@link InputStream}. The stream is consumed but not closed.
     *
     * @param input
     *            stream of UTF-8 JSON bytes (must not be null)
     * @return parsed document (never null)
     * @throws HookConfigParseException
     *             when the bytes are not valid JSON or the structure does not match the DTO
     */
    public HookConfigDocument parse(InputStream input) {
        Objects.requireNonNull(input, "input cannot be null");
        try {
            final HookConfigDocument doc = objectMapper.readValue(input, HookConfigDocument.class);
            return doc == null ? HookConfigDocument.empty() : doc;
        } catch (IOException e) {
            throw new HookConfigParseException("Failed to parse hooks JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a JSON document from a {@link Reader}. The reader is consumed but not closed.
     *
     * @param reader
     *            character reader (must not be null)
     * @return parsed document (never null)
     * @throws HookConfigParseException
     *             when the chars are not valid JSON or the structure does not match the DTO
     */
    public HookConfigDocument parse(Reader reader) {
        Objects.requireNonNull(reader, "reader cannot be null");
        try {
            final HookConfigDocument doc = objectMapper.readValue(reader, HookConfigDocument.class);
            return doc == null ? HookConfigDocument.empty() : doc;
        } catch (IOException e) {
            throw new HookConfigParseException("Failed to parse hooks JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a JSON document from a filesystem path.
     *
     * @param path
     *            path to a UTF-8 JSON file (must not be null)
     * @return parsed document (never null)
     * @throws HookConfigParseException
     *             on parse failure
     * @throws java.io.UncheckedIOException
     *             on IO failure (file unreadable, etc.)
     */
    public HookConfigDocument parseFile(Path path) {
        Objects.requireNonNull(path, "path cannot be null");
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to read hooks JSON file: " + path, e);
        }
    }
}
