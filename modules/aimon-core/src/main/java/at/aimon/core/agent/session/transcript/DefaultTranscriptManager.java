package at.aimon.core.agent.session.transcript;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SessionCheckpointMailbox;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;

/**
 * Manages the transcript lifecycle: initialization, loading, and persistence.
 *
 * <p>
 * This class encapsulates transcript management, separating it from agent execution concerns. It handles:
 *
 * <ul>
 * <li>Loading a session's stored transcript from the repository
 * <li>Creating a fresh transcript with a system prompt
 * <li>Persisting the transcript safely
 * </ul>
 *
 * <p>
 * <b>Sealing.</b> Given a {@link SessionLogStorage}, the manager also keeps the record small on a version-2 log: the
 * turn-end save seals what the view no longer shows verbatim before it writes ({@link #seal(TranscriptBuffer)} does
 * the same mid-turn when the executor asks), and after a successful save it deletes the segments a {@code /clear}
 * cut loose and collects orphans (session-log §5.3, §5.4, §6.2). All of it runs on the thread that saves the turn.
 * Without one, the whole log stays in the record.
 *
 * <p>
 * Thread-safe if the provided SessionRecordStore is thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     TranscriptManager manager = new DefaultTranscriptManager(repository);
 *
 *     // Initialize the transcript (load existing or create new)
 *     TranscriptBuffer memory = manager.initialize(sessionId, "System prompt");
 *     memory.addUserMessage("User message");
 *
 *     // ... use the transcript buffer ...
 *
 *     // Save the transcript (logs errors but doesn't throw)
 *     manager.saveSilently(memory);
 * }
 * </pre>
 */
public class DefaultTranscriptManager implements TranscriptManager {
    private static final Logger log = LoggerFactory.getLogger(DefaultTranscriptManager.class);

    private final SessionRecordStore repository;
    private final SessionCheckpointMailbox mailbox;
    private final SessionLogFormat writeFormat;
    private final SessionLogSealer sealer;
    private final SessionLogGarbageCollector garbageCollector;
    private final SessionLogReader logReader;

    /**
     * Creates a new TranscriptManager without mid-turn checkpointing — the transcript is persisted only at the end of
     * each turn (via {@link #save} / {@link #saveSilently}).
     *
     * @param repository
     *            The session record store used to load and save the transcript (must not be null)
     * @throws NullPointerException
     *             if repository is null
     */
    public DefaultTranscriptManager(SessionRecordStore repository) {
        this(repository, SessionCheckpointMailbox.disabled());
    }

    /**
     * Creates a new TranscriptManager with an explicit {@link SessionCheckpointMailbox} for mid-turn
     * persistence. Each memory returned by {@link #initialize} checkpoints itself on every mutation, and both
     * end-of-turn write paths ({@link #save} / {@link #saveSilently}) drain the mailbox first.
     *
     * @param repository
     *            The session record store used to load and save the transcript (must not be null)
     * @param mailbox
     *            The checkpoint mailbox (must not be null; use {@link SessionCheckpointMailbox#disabled()} to
     *            persist only at end of turn)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultTranscriptManager(SessionRecordStore repository, SessionCheckpointMailbox mailbox) {
        this(repository, mailbox, SessionLogFormat.V1);
    }

    /**
     * Creates a new TranscriptManager that writes the transcripts it hands out in at least {@code writeFormat}.
     *
     * <p>
     * This is the node's write-format switch. It defaults to {@link SessionLogFormat#V1}, and a cluster turns it to
     * {@link SessionLogFormat#V2} only once every node reads version 2 (see {@link SessionLogFormat}). It can only
     * raise a buffer's format: a record read as version 2 is written as version 2 on a node still set to version 1.
     *
     * @param repository
     *            The session record store used to load and save the transcript (must not be null)
     * @param mailbox
     *            The checkpoint mailbox (must not be null; use {@link SessionCheckpointMailbox#disabled()} to
     *            persist only at end of turn)
     * @param writeFormat
     *            The format this node writes transcripts in (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultTranscriptManager(SessionRecordStore repository, SessionCheckpointMailbox mailbox,
            SessionLogFormat writeFormat) {
        this(repository, mailbox, writeFormat, null);
    }

    /**
     * Creates a new TranscriptManager that seals logs into {@code storage}'s segment store.
     *
     * @param repository
     *            The session record store used to load and save the transcript (must not be null)
     * @param mailbox
     *            The checkpoint mailbox (must not be null)
     * @param writeFormat
     *            The format this node writes transcripts in (must not be null)
     * @param storage
     *            where and how to seal, or null to keep the whole log in the record
     * @throws NullPointerException
     *             if any required parameter is null
     */
    public DefaultTranscriptManager(SessionRecordStore repository, SessionCheckpointMailbox mailbox,
            SessionLogFormat writeFormat, SessionLogStorage storage) {
        this.repository = Objects.requireNonNull(repository, "Repository cannot be null");
        this.mailbox = Objects.requireNonNull(mailbox, "Mailbox cannot be null");
        this.writeFormat = Objects.requireNonNull(writeFormat, "Write format cannot be null");
        this.sealer = storage == null ? null : new SessionLogSealer(storage);
        this.garbageCollector = storage == null ? null : new SessionLogGarbageCollector(storage);
        this.logReader = storage == null
                ? null
                : new SessionLogReader(repository, storage.getSegmentStore(), storage.getMaxReadTokens(),
                        storage.getTokenEstimator());
    }

    /**
     * Returns the format this manager writes transcripts in at least.
     *
     * @return the write format (never null)
     */
    public SessionLogFormat getWriteFormat() {
        return writeFormat;
    }

    /**
     * Initializes the transcript without appending any user message.
     *
     * <p>
     * Loads the stored transcript if one is recorded for {@code sessionId}; otherwise creates a fresh one
     * memory. In both cases, {@code systemPrompt} is applied to the returned memory. No user message is appended — the
     * caller decides what to add next (for example, a synthetic user-context message followed by the real user
     * message).
     *
     * @param sessionId
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt to use (can be null)
     * @return The initialized transcript buffer (never null)
     * @throws NullPointerException
     *             if sessionId is null
     */
    @Override
    public TranscriptBuffer initialize(SessionId sessionId, String systemPrompt) {
        Objects.requireNonNull(sessionId, "Session id cannot be null");

        final Optional<SessionRecordView> existingRecord = repository.load(sessionId);
        final TranscriptBuffer memory;

        if (existingRecord.isPresent()) {
            log.debug("Loading existing session: {}", sessionId.value());
            final SessionSnapshot snapshot = SessionSnapshot.from(existingRecord.get());
            memory = TranscriptBuffer.fromSnapshot(snapshot);
            memory.setSystemPrompt(systemPrompt);
        } else {
            log.debug("Creating new session: {}", sessionId.value());
            memory = new TranscriptBuffer(sessionId, systemPrompt);
        }
        memory.requireFormat(writeFormat);

        memory.setDirtyListener(m -> mailbox.checkpoint(m, this::persistSnapshotQuietly));
        return memory;
    }

    /**
     * Saves the transcript to the repository.
     *
     * <p>
     * Seals first, then drains any pending mid-turn checkpoint — including the one a landed seal raises — so this write
     * is the last one for the session. The predecessor
     * of {@link SessionCheckpointMailbox} only drained on the {@link #saveSilently} path, which left this one
     * racing a late background write.
     *
     * <p>
     * Preserves the persisted {@code compactionFailureCount} from any existing record so that the in-memory
     * {@link TranscriptBuffer} round-trip — which does not track the counter — does not silently clobber state
     * recorded by the compaction guard's failure store.
     *
     * @param memory
     *            The transcript buffer to save (must not be null)
     * @throws NullPointerException
     *             if memory is null
     * @throws Exception
     *             if save fails
     */
    @Override
    public void save(TranscriptBuffer memory) {
        Objects.requireNonNull(memory, "Transcript buffer cannot be null");
        // Seal BEFORE the flush: a seal that lands marks the buffer dirty, and the checkpoint that raises must be
        // drained by the barrier below — raised after it, it would be written after this save (session-log §5.3).
        seal(memory);
        mailbox.flush(memory.getSessionId());
        final SessionSnapshot saved = memory.toSnapshot();
        persistSnapshot(saved);
        afterSave(memory, saved);
    }

    @Override
    public void seal(TranscriptBuffer memory) {
        Objects.requireNonNull(memory, "Transcript buffer cannot be null");
        if (sealer != null) {
            sealer.seal(memory);
        }
    }

    @Override
    public Optional<SessionLogReader> getLogReader() {
        return Optional.ofNullable(logReader);
    }

    /**
     * Runs once the record is saved, on the saving thread: the segments a {@code /clear} cut loose go first — the saved
     * record no longer names them — then orphans past the grace period.
     */
    private void afterSave(TranscriptBuffer memory, SessionSnapshot saved) {
        if (garbageCollector == null) {
            return;
        }
        final List<SegmentId> cleared = memory.pendingSegmentDeletions();
        if (!cleared.isEmpty()) {
            memory.forgetSegmentDeletions(garbageCollector.deleteCleared(memory.getSessionId(), cleared));
        }
        garbageCollector.collect(memory.getSessionId(), saved.getLogState());
    }

    private void persistSnapshot(SessionSnapshot snapshot) {
        // Atomic read + merge + write at the repository layer — implementations that override mergeFromSnapshot
        // (e.g. InMemorySessionRecordStore#compute, future Postgres UPSERT in a transaction) keep the
        // compactionFailureCount / agentRef preservation race-free against concurrent side-field writers.
        repository.mergeFromSnapshot(snapshot);
    }

    private void persistSnapshotQuietly(SessionSnapshot snapshot) {
        try {
            persistSnapshot(snapshot);
        } catch (Exception e) {
            log.warn("Mid-turn checkpoint failed for {}: {}", snapshot.getSessionId().value(), e.getMessage());
        }
    }

    /**
     * Saves the transcript to the repository, logging errors without throwing.
     *
     * <p>
     * This method is useful for save operations where failure should not interrupt the main flow (e.g., in finally
     * blocks or error handling paths).
     *
     * @param memory
     *            The transcript buffer to save (must not be null)
     * @throws NullPointerException
     *             if memory is null
     */
    @Override
    public void saveSilently(TranscriptBuffer memory) {
        Objects.requireNonNull(memory, "Transcript buffer cannot be null");
        // Sealed before the save rather than after, so this very write already leaves the sealed ranges out — and
        // before the flush, because a seal that lands marks the buffer dirty: the checkpoint that raises has to be
        // behind the barrier below, not queued after it where it would overtake this save.
        seal(memory);
        // Drain the mailbox BEFORE the authoritative persist so an in-flight checkpoint (holding an older snapshot)
        // cannot land in the repository after our write returns.
        mailbox.flush(memory.getSessionId());
        final SessionSnapshot saved;
        try {
            saved = memory.toSnapshot();
            persistSnapshot(saved);
        } catch (Exception e) {
            // saveSilently is the no-throw end-of-turn path; a persistence failure here is an expected operational
            // error (disk full, network partition), so log at WARN to mirror the checkpoint failure level.
            log.warn("Failed to save session {}: {}", memory.getSessionId().value(), e.getMessage());
            // Do not throw to preserve the original execution flow
            return;
        }
        afterSave(memory, saved);
    }
}
