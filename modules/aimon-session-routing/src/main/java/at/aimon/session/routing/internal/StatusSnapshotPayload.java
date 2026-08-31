package at.aimon.session.routing.internal;

import static at.aimon.session.routing.internal.PayloadValues.asBoolean;
import static at.aimon.session.routing.internal.PayloadValues.asInt;
import static at.aimon.session.routing.internal.PayloadValues.asLong;
import static at.aimon.session.routing.internal.PayloadValues.asMap;
import static at.aimon.session.routing.internal.PayloadValues.asString;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.LiveSessionStatus;
import at.aimon.core.agent.session.LiveSessionStatus.Phase;
import at.aimon.core.agent.session.LiveSessionStatus.TurnProgress;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.llm.TokenUsage;

/**
 * Flattens a {@link LiveSessionStatus} snapshot to/from a JSON-primitive {@code Map<String, Object>} so it can ride
 * the existing {@link at.aimon.core.agent.session.signal.SessionSignalBus} {@code STATUS} rail across nodes.
 *
 * <p>
 * <b>Why flat primitives, not the typed object.</b> The cross-node signal codecs ({@code aimon-session-redis} /
 * {@code aimon-session-mongodb}) decode a signal payload to a {@code LinkedHashMap} of JSON primitives (string /
 * boolean / int / long / double / nested map / list) and never reconstruct a typed object — see their
 * {@code SessionSignalCodec} ("Polymorphic round-trip of {@code AgentExecutionEvent} is out of scope"). Any design
 * that publishes a typed object and reconstructs it with {@code instanceof} works only on the in-process bus and is a
 * silent no-op over a real bus (this is exactly how cross-node {@code EVENT} relay currently misbehaves). This helper
 * therefore carries the snapshot as a flat primitive map and rebuilds the {@link LiveSessionStatus} from it via the
 * public {@code aimon-core} builders.
 *
 * <p>
 * <b>Numeric tolerance.</b> A JSON string round-trip narrows an in-{@code int}-range {@code long} back to
 * {@code Integer} and may widen integers to {@code Double}; {@link #fromPayload} reads every numeric field through
 * {@link Number} so it survives the codec's normalization regardless of the concrete boxed type that arrives.
 *
 * <p>
 * <b>Lossy fields.</b> {@link LiveSessionStatus#getOptions()} is intentionally not carried (it is not needed for
 * cluster observability and keeps the payload small); a rebuilt snapshot reports empty options. Durations are carried
 * at millisecond granularity.
 */
final class StatusSnapshotPayload {

    static final String KEY_PHASE = "phase";
    static final String KEY_INTERRUPTIBLE = "interruptible";
    static final String KEY_QUEUE_DEPTH = "queueDepth";
    static final String KEY_TOTALS = "totals";
    static final String KEY_TURN = "turn";
    static final String KEY_OBSERVED_AT = "observedAtMillis";
    static final String KEY_SEQ = "seq";

    static final String KEY_TURN_COUNT = "turnCount";
    static final String KEY_ITERATIONS = "iterations";
    static final String KEY_PROMPT_TOKENS = "promptTokens";
    static final String KEY_COMPLETION_TOKENS = "completionTokens";
    static final String KEY_TOTAL_TOKENS = "totalTokens";
    static final String KEY_ELAPSED_MILLIS = "elapsedMillis";
    static final String KEY_MAX_ITERATIONS = "maxIterations";
    static final String KEY_MAX_TOKENS = "maxTokens";
    static final String KEY_MAX_WALL_MILLIS = "maxWallMillis";

    private static final Logger log = LoggerFactory.getLogger(StatusSnapshotPayload.class);

    private StatusSnapshotPayload() {
    }

    /**
     * Flattens {@code status} plus its projection metadata into a JSON-primitive map suitable for a {@code STATUS}
     * signal payload. The session id is intentionally NOT included — the {@code SessionSignal} envelope
     * already carries it and supplies it back to {@link #fromPayload}.
     *
     * @param status
     *            the snapshot to flatten (must not be null)
     * @param seq
     *            monotonic sequence stamp used by the receiver to drop out-of-order snapshots
     * @param observedAt
     *            the instant the snapshot was taken on the holder (must not be null)
     * @return a fresh mutable map of JSON primitives and nested primitive maps (never null)
     */
    static Map<String, Object> toPayload(LiveSessionStatus status, long seq, Instant observedAt) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(KEY_PHASE, status.getPhase().name());
        payload.put(KEY_INTERRUPTIBLE, status.isInterruptible());
        payload.put(KEY_QUEUE_DEPTH, status.getQueueDepth());
        payload.put(KEY_TOTALS, totalsToMap(status.getSessionTotals()));
        status.getTurnProgress().ifPresent(tp -> payload.put(KEY_TURN, turnToMap(tp)));
        payload.put(KEY_OBSERVED_AT, observedAt.toEpochMilli());
        payload.put(KEY_SEQ, seq);
        return payload;
    }

    /**
     * Rebuilds a {@link LiveSessionStatus} (plus its {@code seq}/{@code observedAt} metadata) from a payload produced
     * by {@link #toPayload} and possibly normalized by a JSON codec on the way across the bus. Never throws: a null or
     * malformed payload yields {@link Optional#empty()} so a corrupt signal can never break the receive path.
     *
     * @param sessionId
     *            the session the signal envelope was addressed to (must not be null)
     * @param payload
     *            the decoded signal payload (may be null)
     * @return the decoded snapshot, or empty if the payload is absent/malformed
     */
    static Optional<Decoded> fromPayload(SessionId sessionId, Map<String, Object> payload) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (payload == null) {
            return Optional.empty();
        }
        try {
            final LiveSessionStatus.Builder builder = LiveSessionStatus.builder().sessionId(sessionId)
                    .phase(Phase.valueOf(asString(payload.get(KEY_PHASE))))
                    .interruptible(asBoolean(payload.get(KEY_INTERRUPTIBLE)))
                    .queueDepth(asInt(payload.get(KEY_QUEUE_DEPTH)))
                    .sessionTotals(totalsFromMap(asMap(payload.get(KEY_TOTALS))));
            final Object turn = payload.get(KEY_TURN);
            if (turn != null) {
                builder.turnProgress(turnFromMap(asMap(turn)));
            }
            final long seq = asLong(payload.get(KEY_SEQ));
            final Instant observedAt = Instant.ofEpochMilli(asLong(payload.get(KEY_OBSERVED_AT)));
            return Optional.of(new Decoded(builder.build(), seq, observedAt));
        } catch (RuntimeException e) {
            log.warn("Discarding malformed status snapshot payload for {}: {}", sessionId, e.toString());
            return Optional.empty();
        }
    }

    private static Map<String, Object> totalsToMap(SessionTotals totals) {
        final TokenUsage tokens = totals.getTokenUsage();
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_TURN_COUNT, totals.getTurnCount());
        map.put(KEY_ITERATIONS, totals.getIterations());
        map.put(KEY_PROMPT_TOKENS, tokens.getPromptTokens());
        map.put(KEY_COMPLETION_TOKENS, tokens.getCompletionTokens());
        map.put(KEY_TOTAL_TOKENS, tokens.getTotalTokens());
        return map;
    }

    private static SessionTotals totalsFromMap(Map<String, Object> map) {
        final TokenUsage tokens = TokenUsage.of(asInt(map.get(KEY_PROMPT_TOKENS)),
                asInt(map.get(KEY_COMPLETION_TOKENS)), asInt(map.get(KEY_TOTAL_TOKENS)));
        return SessionTotals.of(asInt(map.get(KEY_TURN_COUNT)), asInt(map.get(KEY_ITERATIONS)), tokens);
    }

    private static Map<String, Object> turnToMap(TurnProgress turn) {
        final TokenUsage tokens = turn.getTokenUsage();
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_ITERATIONS, turn.getIterations());
        map.put(KEY_PROMPT_TOKENS, tokens.getPromptTokens());
        map.put(KEY_COMPLETION_TOKENS, tokens.getCompletionTokens());
        map.put(KEY_TOTAL_TOKENS, tokens.getTotalTokens());
        map.put(KEY_ELAPSED_MILLIS, turn.getElapsed().toMillis());
        final ExecutionBudget budget = turn.getBudget();
        budget.getMaxIterations().ifPresent(v -> map.put(KEY_MAX_ITERATIONS, v));
        budget.getMaxTokens().ifPresent(v -> map.put(KEY_MAX_TOKENS, v));
        budget.getMaxWallClockDuration().ifPresent(d -> map.put(KEY_MAX_WALL_MILLIS, d.toMillis()));
        return map;
    }

    private static TurnProgress turnFromMap(Map<String, Object> map) {
        final TokenUsage tokens = TokenUsage.of(asInt(map.get(KEY_PROMPT_TOKENS)),
                asInt(map.get(KEY_COMPLETION_TOKENS)), asInt(map.get(KEY_TOTAL_TOKENS)));
        final Duration elapsed = Duration.ofMillis(asLong(map.get(KEY_ELAPSED_MILLIS)));
        final ExecutionBudget.Builder budget = ExecutionBudget.builder();
        if (map.containsKey(KEY_MAX_ITERATIONS)) {
            budget.maxIterations(asInt(map.get(KEY_MAX_ITERATIONS)));
        }
        if (map.containsKey(KEY_MAX_TOKENS)) {
            budget.maxTokens(asInt(map.get(KEY_MAX_TOKENS)));
        }
        if (map.containsKey(KEY_MAX_WALL_MILLIS)) {
            final long wallMillis = asLong(map.get(KEY_MAX_WALL_MILLIS));
            // The builder rejects a non-positive duration; sub-millisecond budgets (never used in practice) round to 0.
            if (wallMillis >= 1) {
                budget.maxWallClockDuration(Duration.ofMillis(wallMillis));
            }
        }
        return TurnProgress.of(asInt(map.get(KEY_ITERATIONS)), tokens, elapsed, budget.build());
    }

    /** Decoded carrier: the rebuilt snapshot plus the ordering/freshness metadata the projection needs. */
    static final class Decoded {
        private final LiveSessionStatus status;
        private final long seq;
        private final Instant observedAt;

        Decoded(LiveSessionStatus status, long seq, Instant observedAt) {
            this.status = status;
            this.seq = seq;
            this.observedAt = observedAt;
        }

        LiveSessionStatus status() {
            return status;
        }

        long seq() {
            return seq;
        }

        Instant observedAt() {
            return observedAt;
        }
    }
}
