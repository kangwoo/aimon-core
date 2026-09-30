package at.aimon.core.agent.session.transcript;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentCodec;

/**
 * Seals the ranges of a session log the view no longer shows verbatim into segments (session-log §5.1, §5.3).
 *
 * <p>
 * <b>What is sealable.</b> A run of carried entries each hidden by the view state — inside the summary span or a
 * dropped range — with nothing visible and no sealed range between them. A run is cut at the rewind point, so a rewind
 * drops whole manifest lines instead of splitting a segment; both of its ends must be legal cuts; and it is sealed only
 * when its estimated size reaches {@code minSealTokens}. A log that never compacted has nothing hidden and is never
 * sealed.
 *
 * <p>
 * <b>Order.</b> For each run: write the segment under a fresh id, then record it in the buffer's manifest. A failed
 * write leaves the run carried and is retried at the next sealing point; a record that is never saved after a
 * successful write leaves an orphan for garbage collection. Neither loses an entry. There is no need to know whether
 * the save succeeded (session-log §5.3).
 *
 * <p>
 * <b>Threading.</b> Called by the thread running the turn, on that turn's buffer, synchronously — never from another
 * thread: buffers are created per turn, and a late change to an old buffer could hand its stale state to the
 * checkpoint writer.
 *
 * <p>
 * Stateless apart from its configuration; thread-safe.
 */
public final class SessionLogSealer {

    private static final Logger log = LoggerFactory.getLogger(SessionLogSealer.class);

    private final SessionLogStorage storage;

    /**
     * @param storage
     *            where and how to seal (must not be null)
     */
    public SessionLogSealer(SessionLogStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
    }

    /**
     * Seals every sealable run of {@code buffer}'s log. Never throws for a storage failure: the run stays carried and
     * the failure is logged at WARN.
     *
     * @param buffer
     *            the turn's buffer (must not be null)
     * @return how many ranges were sealed
     */
    public int seal(TranscriptBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer cannot be null");
        final SessionLogState state = buffer.getLogState();
        int sealed = 0;
        for (List<SessionLogEntry> run : sealableRuns(state)) {
            if (sealRun(buffer, run)) {
                sealed++;
            }
        }
        return sealed;
    }

    /**
     * Returns the runs of {@code state} that may be sealed now — see the class javadoc.
     *
     * @param state
     *            the log (must not be null)
     * @return the runs, each a non-empty list of consecutive carried entries (never null)
     */
    List<List<SessionLogEntry>> sealableRuns(SessionLogState state) {
        if (state.getFormat() != SessionLogFormat.V2 || state.getViewState().isEmpty()) {
            return List.of();
        }
        final SessionViewState view = state.getViewState();
        final long rewindAt = state.getRewindPoint().map(SessionRewindPoint::getSeq).orElse(-1L);
        final List<List<SessionLogEntry>> candidates = new ArrayList<>();
        List<SessionLogEntry> run = new ArrayList<>();
        for (SessionLogEntry entry : state.getEntries()) {
            final long seq = entry.getSeq();
            if (!view.hidesOriginal(seq)) {
                run = closeRun(candidates, run);
                continue;
            }
            if (!run.isEmpty()) {
                final long last = run.get(run.size() - 1).getSeq();
                if ((rewindAt > last && rewindAt <= seq) || sealedBetween(state, last, seq)
                        || !view.hidesRange(last + 1, seq)) {
                    run = closeRun(candidates, run);
                }
            }
            run.add(entry);
        }
        closeRun(candidates, run);

        final List<List<SessionLogEntry>> runs = new ArrayList<>(candidates.size());
        for (List<SessionLogEntry> candidate : candidates) {
            final long from = candidate.get(0).getSeq();
            final long to = candidate.get(candidate.size() - 1).getSeq() + 1;
            if (!state.isLegalCut(from) || !state.isLegalCut(to)) {
                log.debug("Not sealing [{}, {}): a cut would split a tool pair", from, to);
                continue;
            }
            if (estimate(candidate) < storage.getMinSealTokens()) {
                continue;
            }
            runs.add(candidate);
        }
        return runs;
    }

    private boolean sealRun(TranscriptBuffer buffer, List<SessionLogEntry> run) {
        final long from = run.get(0).getSeq();
        final long to = run.get(run.size() - 1).getSeq() + 1;
        final SegmentId id = SegmentId.generate();
        final SessionLogManifestEntry line;
        try {
            final String payload = SessionLogSegmentCodec.encode(run);
            storage.getSegmentStore()
                    .put(SessionLogSegment.builder().sessionId(buffer.getSessionId()).id(id).fromSeq(from).toSeq(to)
                            .entryCount(run.size()).payload(payload).createdAt(storage.getClock().instant()).build());
            line = SessionLogManifestEntry.builder().fromSeq(from).toSeq(to).segmentId(id)
                    .contentHash(SessionLogSegmentCodec.contentHash(payload)).entryCount(run.size()).build();
        } catch (RuntimeException e) {
            log.warn("Sealing [{}, {}) of session {} failed; the range stays in the record: {}", from, to,
                    buffer.getSessionId().value(), e.toString());
            return false;
        }
        try {
            buffer.seal(line);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // The segment is written but not named by any manifest: an orphan, which garbage collection deletes.
            log.warn("Sealed segment {} of session {} could not be recorded; it is an orphan: {}", id,
                    buffer.getSessionId().value(), e.getMessage());
            return false;
        }
        log.debug("Sealed [{}, {}) of session {} into segment {} ({} entries)", from, to, buffer.getSessionId().value(),
                id, run.size());
        return true;
    }

    private int estimate(List<SessionLogEntry> run) {
        int tokens = 0;
        for (SessionLogEntry entry : run) {
            if (entry.getMessage() != null) {
                tokens += storage.getTokenEstimator().estimateMessage(entry.getMessage());
            }
        }
        return tokens;
    }

    private static boolean sealedBetween(SessionLogState state, long afterSeq, long beforeSeq) {
        for (SessionLogManifestEntry line : state.getManifest()) {
            if (line.getFromSeq() > afterSeq && line.getFromSeq() < beforeSeq) {
                return true;
            }
        }
        return false;
    }

    private static List<SessionLogEntry> closeRun(List<List<SessionLogEntry>> candidates, List<SessionLogEntry> run) {
        if (!run.isEmpty()) {
            candidates.add(run);
        }
        return new ArrayList<>();
    }
}
