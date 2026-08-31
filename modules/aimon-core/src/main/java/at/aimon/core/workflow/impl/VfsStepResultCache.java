package at.aimon.core.workflow.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.workflow.StepKey;
import at.aimon.core.workflow.StepOutcome;
import at.aimon.core.workflow.StepResultCache;

/**
 * {@link StepResultCache} backed by a {@link VirtualFileSystem}, the shared/persistent alternative to
 * {@code InMemoryStepResultCache} (design §5.3, modelled on {@code VfsSessionSnapshotStore}).
 *
 * <p>
 * Because a {@link VirtualFileSystem} can be a GridFS or S3 backend, a step whose outcome is cached on one node can be
 * replayed by a resuming run on <em>another</em>. Each step is a single JSON object at
 * {@code <baseDir>/<sha256(key)>.json}: a {@link StepKey}'s canonical value contains {@code /} and {@code :}, so it is
 * hashed to a flat, path-safe file name. The stored envelope records the original key for a defensive check on load, so
 * a (astronomically unlikely) hash collision degrades to a miss rather than a wrong replay.
 *
 * <pre>
 * { "v": 1, "key": "run:audit/agent:reviewer/p0/0/a0", "outcome": { ...StepOutcomeCodec output... } }
 * </pre>
 *
 * <p>
 * <b>Best-effort.</b> Every backend/codec interaction is guarded: a failed {@code save} logs a warning and never
 * throws,
 * and a malformed, wrong-version, key-mismatched, or unreadable object {@code load}s as {@link Optional#empty()} —
 * exactly as an evicted in-memory entry would, so a cache problem degrades to re-execution. Bounding is delegated to
 * the
 * backend / an external retention policy rather than an LRU cap. Thread-safe: each operation is an independent
 * single-object read/write/delete.
 */
public final class VfsStepResultCache implements StepResultCache {

    /** Default base directory for per-step cache objects. */
    public static final String DEFAULT_BASE_DIR = ".aimon/step-cache";

    private static final Logger log = LoggerFactory.getLogger(VfsStepResultCache.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ENVELOPE_VERSION = 1;
    private static final String SUFFIX = ".json";
    private static final String FIELD_VERSION = "v";
    private static final String FIELD_KEY = "key";
    private static final String FIELD_OUTCOME = "outcome";

    private final VirtualFileSystem fileSystem;
    private final StepOutcomeCodec codec;
    private final String baseDir;

    /**
     * Creates a cache rooted at {@link #DEFAULT_BASE_DIR}.
     *
     * @param fileSystem
     *            the backing virtual file system (must not be null)
     */
    public VfsStepResultCache(VirtualFileSystem fileSystem) {
        this(fileSystem, DEFAULT_BASE_DIR);
    }

    /**
     * Creates a cache with an explicit base directory.
     *
     * @param fileSystem
     *            the backing virtual file system (must not be null)
     * @param baseDir
     *            the base directory under which per-step cache objects are stored (must not be null/blank)
     */
    public VfsStepResultCache(VirtualFileSystem fileSystem, String baseDir) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem cannot be null");
        Objects.requireNonNull(baseDir, "baseDir cannot be null");
        if (baseDir.isBlank()) {
            throw new IllegalArgumentException("baseDir cannot be blank");
        }
        this.codec = new StepOutcomeCodec();
        this.baseDir = baseDir.endsWith("/") ? baseDir.substring(0, baseDir.length() - 1) : baseDir;
    }

    @Override
    public void save(StepKey key, StepOutcome outcome) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(outcome, "outcome cannot be null");
        final String path = cachePath(key);
        try {
            final ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put(FIELD_VERSION, ENVELOPE_VERSION);
            envelope.put(FIELD_KEY, key.value());
            envelope.set(FIELD_OUTCOME, MAPPER.readTree(codec.encode(outcome)));
            final String json = MAPPER.writeValueAsString(envelope);
            deleteQuietly(path);
            fileSystem.write(path, json);
        } catch (RuntimeException | IOException e) {
            log.warn("Failed to save step outcome for key {}: {}", key.value(), e.getMessage());
        }
    }

    @Override
    public Optional<StepOutcome> load(StepKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        final String path = cachePath(key);
        try {
            if (!fileSystem.exists(path) || fileSystem.isDirectory(path)) {
                return Optional.empty();
            }
            final JsonNode envelope = MAPPER.readTree(readString(path));
            if (envelope == null || !envelope.isObject()) {
                log.warn("Malformed step-cache object for key {}: not a JSON object", key.value());
                return Optional.empty();
            }
            if (envelope.path(FIELD_VERSION).asInt(-1) != ENVELOPE_VERSION) {
                log.warn("Unsupported step-cache envelope version for key {}", key.value());
                return Optional.empty();
            }
            // Defensive: guard against a hash collision surfacing a different step's outcome.
            final JsonNode keyNode = envelope.get(FIELD_KEY);
            if (keyNode == null || !keyNode.isTextual() || !key.value().equals(keyNode.asText())) {
                log.warn("Step-cache key mismatch for {} (stored '{}')", key.value(),
                        keyNode == null ? null : keyNode.asText());
                return Optional.empty();
            }
            final JsonNode outcomeNode = envelope.get(FIELD_OUTCOME);
            if (outcomeNode == null || !outcomeNode.isObject()) {
                log.warn("Malformed step-cache object for key {}: missing outcome", key.value());
                return Optional.empty();
            }
            return Optional.of(codec.decode(outcomeNode.toString()));
        } catch (RuntimeException | IOException e) {
            log.warn("Failed to load step outcome for key {}: {}", key.value(), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void evict(StepKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        deleteQuietly(cachePath(key));
    }

    private void deleteQuietly(String path) {
        try {
            if (fileSystem.exists(path)) {
                fileSystem.delete(path);
            }
        } catch (RuntimeException e) {
            log.debug("Failed to delete step-cache object {}: {}", path, e.getMessage());
        }
    }

    private String readString(String path) throws IOException {
        try (InputStream in = fileSystem.read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String cachePath(StepKey key) {
        return baseDir + "/" + sha256Hex(key.value()) + SUFFIX;
    }

    private static String sha256Hex(String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JDK algorithm; its absence is a broken platform, not a recoverable condition.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
