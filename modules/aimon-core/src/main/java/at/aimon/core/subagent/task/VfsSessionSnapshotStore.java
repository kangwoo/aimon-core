package at.aimon.core.subagent.task;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.subagent.task.codec.JsonSessionSnapshotCodec;
import at.aimon.core.subagent.task.codec.SessionSnapshotCodec;

/**
 * {@link SessionSnapshotStore} backed by a {@link VirtualFileSystem}, isomorphic with {@link VfsTaskOutputStore}
 * (design §7).
 *
 * <p>
 * This is the shared/persistent alternative to {@link InMemorySessionSnapshotStore}: because a
 * {@link VirtualFileSystem} can be a GridFS or S3 backend, a subagent whose terminal transcript is saved on one node
 * can
 * be resumed by a {@code Task(resume=<taskId>)} on <em>another</em> node. It composes the
 * {@link SessionSnapshotCodec} prerequisite (which serializes the {@link SessionSnapshot} value graph) with a
 * thin owner-tag envelope so the persisted record carries the same {@link ResumableSession} pairing as the
 * in-memory store.
 *
 * <p>
 * <b>Layout.</b> Each task's snapshot is a single JSON object at {@code <baseDir>/<taskId>.json}:
 *
 * <pre>
 * { "v": 1, "subagentName": "code-reviewer", "contextId": "agent:reviewer", "snapshot": { ...codec output... } }
 * </pre>
 *
 * The {@code contextId} tag is optional: it is written when the transcript's owning agent runtime is known,
 * and its absence (an older snapshot, or a non-Orca save) leaves the transcript unscoped so a context-scoped resume
 * declines it.
 *
 * A single object per task means a save is one PUT (atomic on object stores) and a load is one GET — no listing or
 * segment reassembly is needed, unlike the append-only {@link VfsTaskOutputStore}.
 *
 * <p>
 * <b>Last-write-wins across backends.</b> {@link #save} deletes any existing object before writing, so the newest
 * snapshot replaces the previous one regardless of whether the backend's {@code write} overwrites, rejects, or versions
 * an existing path.
 *
 * <p>
 * <b>Best-effort.</b> Every backend and codec interaction is guarded: a failed save logs a warning and never throws (it
 * must not abort the subagent whose transcript it records), and a malformed, unsupported-version, or unreadable object
 * loads as {@link Optional#empty()} (resume reports "no resumable task found"), exactly as an evicted in-memory entry
 * would. Bounding is delegated to the backend / an external retention policy rather than an LRU cap.
 *
 * <p>
 * Thread-safe: saves, loads, and evicts are independent single-object operations with no shared mutable state, so a
 * subagent thread saving a terminal snapshot may safely race with a parent agent loading it to resume.
 */
public final class VfsSessionSnapshotStore implements SessionSnapshotStore {

    /** Default base directory for per-task session snapshot objects. */
    public static final String DEFAULT_BASE_DIR = ".aimon/task-snapshot";

    private static final Logger log = LoggerFactory.getLogger(VfsSessionSnapshotStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ENVELOPE_VERSION = 1;
    private static final String SNAPSHOT_SUFFIX = ".json";
    private static final String FIELD_VERSION = "v";
    private static final String FIELD_SUBAGENT_NAME = "subagentName";
    /**
     * FROZEN WIRE KEY — do not rename alongside the Java identifier. The agent-scope refactor renamed the type to
     * {@code AgentRuntime} but deliberately left persisted names alone (CHANGELOG, "Not changed (deliberately
     * frozen)"). Renaming this orphans the owner tag on every snapshot already on the backend, and because the writer
     * and reader share this one constant the change keeps every round-trip test green. Pinned in both directions by
     * {@code VfsSessionSnapshotStoreTest}.
     */
    private static final String FIELD_CONTEXT_ID = "contextId";
    private static final String FIELD_SNAPSHOT = "snapshot";

    private final VirtualFileSystem fileSystem;
    private final SessionSnapshotCodec codec;
    private final String baseDir;

    /**
     * Creates a store rooted at {@link #DEFAULT_BASE_DIR} using the default {@link JsonSessionSnapshotCodec}.
     *
     * @param fileSystem
     *            the backing virtual file system (must not be null)
     */
    public VfsSessionSnapshotStore(VirtualFileSystem fileSystem) {
        this(fileSystem, new JsonSessionSnapshotCodec(), DEFAULT_BASE_DIR);
    }

    /**
     * Creates a store with an explicit codec and base directory.
     *
     * @param fileSystem
     *            the backing virtual file system (must not be null)
     * @param codec
     *            the snapshot codec used to (de)serialize the {@link SessionSnapshot} value (must not be null)
     * @param baseDir
     *            the base directory under which per-task snapshot objects are stored (must not be null/blank)
     */
    public VfsSessionSnapshotStore(VirtualFileSystem fileSystem, SessionSnapshotCodec codec, String baseDir) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem cannot be null");
        this.codec = Objects.requireNonNull(codec, "codec cannot be null");
        Objects.requireNonNull(baseDir, "baseDir cannot be null");
        if (baseDir.isBlank()) {
            throw new IllegalArgumentException("baseDir cannot be blank");
        }
        this.baseDir = stripTrailingSlash(baseDir);
    }

    @Override
    public void save(String taskId, String subagentName, AgentRuntimeId agentRuntimeId, SessionSnapshot snapshot) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(subagentName, "subagentName cannot be null");
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        final String path = snapshotPath(taskId);
        try {
            final ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put(FIELD_VERSION, ENVELOPE_VERSION);
            envelope.put(FIELD_SUBAGENT_NAME, subagentName);
            if (agentRuntimeId != null) {
                envelope.put(FIELD_CONTEXT_ID, agentRuntimeId.value());
            }
            envelope.set(FIELD_SNAPSHOT, MAPPER.readTree(codec.encode(snapshot)));
            final String json = MAPPER.writeValueAsString(envelope);
            deleteQuietly(path);
            fileSystem.write(path, json);
        } catch (RuntimeException | IOException e) {
            log.warn("Failed to save session snapshot for task {}: {}", taskId, e.getMessage());
        }
    }

    @Override
    public Optional<ResumableSession> load(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        final String path = snapshotPath(taskId);
        try {
            if (!fileSystem.exists(path) || fileSystem.isDirectory(path)) {
                return Optional.empty();
            }
            final JsonNode envelope = MAPPER.readTree(readString(path));
            if (envelope == null || !envelope.isObject()) {
                log.warn("Malformed session snapshot for task {}: not a JSON object", taskId);
                return Optional.empty();
            }
            final int version = envelope.path(FIELD_VERSION).asInt(-1);
            if (version != ENVELOPE_VERSION) {
                log.warn("Unsupported session snapshot envelope version {} for task {}", version, taskId);
                return Optional.empty();
            }
            final JsonNode ownerNode = envelope.get(FIELD_SUBAGENT_NAME);
            final JsonNode snapshotNode = envelope.get(FIELD_SNAPSHOT);
            if (ownerNode == null || !ownerNode.isTextual() || snapshotNode == null || !snapshotNode.isObject()) {
                log.warn("Malformed session snapshot envelope for task {}", taskId);
                return Optional.empty();
            }
            final AgentRuntimeId agentRuntimeId = readRuntimeId(envelope, taskId);
            final SessionSnapshot snapshot = codec.decode(snapshotNode.toString());
            return Optional.of(ResumableSession.of(ownerNode.asText(), agentRuntimeId, snapshot));
        } catch (RuntimeException | IOException e) {
            log.warn("Failed to load session snapshot for task {}: {}", taskId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void evict(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        deleteQuietly(snapshotPath(taskId));
    }

    /**
     * Reads the optional owning-context tag from the envelope. Absent (older snapshot written before context tagging)
     * or malformed values yield {@code null} — a scoped resume then declines the transcript rather than trusting an
     * unverifiable owner. A structurally-invalid id is tolerated (logged, treated as absent) so one bad tag never makes
     * a snapshot unloadable.
     */
    private AgentRuntimeId readRuntimeId(JsonNode envelope, String taskId) {
        final JsonNode node = envelope.get(FIELD_CONTEXT_ID);
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return AgentRuntimeId.of(node.asText());
        } catch (RuntimeException e) {
            log.warn("Ignoring malformed context id in session snapshot for task {}: {}", taskId, e.getMessage());
            return null;
        }
    }

    private void deleteQuietly(String path) {
        try {
            if (fileSystem.exists(path)) {
                fileSystem.delete(path);
            }
        } catch (RuntimeException e) {
            log.debug("Failed to delete session snapshot {}: {}", path, e.getMessage());
        }
    }

    private String readString(String path) throws IOException {
        try (InputStream in = fileSystem.read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String snapshotPath(String taskId) {
        return baseDir + "/" + taskId + SNAPSHOT_SUFFIX;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
