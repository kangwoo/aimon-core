package at.aimon.session.routing.internal;

import static at.aimon.session.routing.internal.PayloadValues.asBoolean;
import static at.aimon.session.routing.internal.PayloadValues.asString;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.store.StoredAgentExecutionResult;

/**
 * Flattens the terminal outcome of one turn to/from a JSON-primitive {@code Map<String, Object>} so the holder node can
 * announce it on the {@link at.aimon.core.agent.session.signal.SessionSignal.SignalKind#TURN_RESULT} rail and the node
 * that
 * accepted the submission can complete the future it handed its caller (design §7.1 F6/F7).
 *
 * <p>
 * <b>Why flat primitives.</b> Same reason as {@link AgentExecutionEventPayload} and {@code StatusSnapshotPayload}: the
 * real bus codecs decode a payload to a {@code LinkedHashMap} of JSON primitives and never rebuild a typed object, so a
 * design that publishes the {@link AgentExecutionResult} itself works only on the in-process bus and is a silent no-op
 * over Redis/Mongo/Postgres.
 *
 * <p>
 * <b>Two addresses, deliberately.</b> The payload carries the {@link TurnId} and, when the submission had one, its
 * idempotency key. The turn id resolves the normal case. The key exists for the submission that never got a turn of its
 * own: a client retry that arrived while the first attempt was still in flight is collapsed to
 * {@code alreadyInFlight}, so it waits on a turn whose id it was never told. Without the key on this rail that caller
 * would only ever be resolved by the polling fallback.
 *
 * <p>
 * <b>Either address may be the only one.</b> A failure can also be announced by a node that knows the key but not the
 * turn — holder-loss recovery is the case that exists today. The sweeper works from an
 * {@link at.aimon.core.agent.session.idempotency.IdempotencyEntry}, which records the reservation and its holder but
 * not the turn the
 * dead holder was running: that id lived in the inbox envelope the holder consumed before it died. So
 * {@link #toFailurePayload} accepts a null turn id and this decoder tolerates a payload without one, requiring only
 * that <em>some</em> address is present — a payload with neither reaches nobody, and publishing it would only put
 * undecodable noise on the rail.
 *
 * <p>
 * <b>Artifacts are dropped</b> — see {@link StoredAgentExecutionResult}. A forwarded turn's result reports no
 * artifacts;
 * that is the contract, not a bug to work around (design §9.4).
 *
 * <p>
 * <b>Failure codes are forward-tolerant.</b> An unrecognized {@link Failure.Code} name (a newer node's code an older
 * node does not know) decodes to {@link Failure.Code#FAILED} with the message preserved, so a rolling deployment
 * degrades to a less precise reason rather than a dropped signal — the opposite of {@code SignalKind.valueOf}, which
 * throws and takes the whole signal with it.
 */
final class TurnResultPayload {

    static final String KEY_TURN = "turn";
    static final String KEY_IDEMPOTENCY_KEY = "idem";
    static final String KEY_OUTCOME = "outcome";
    static final String KEY_MESSAGE = "message";
    static final String KEY_SUCCESS = "success";
    static final String KEY_ANSWER = "answer";
    static final String KEY_RESULT_ERROR = "resultError";
    static final String KEY_COMPLETION = "completion";
    static final String KEY_STREAMED = "streamed";

    static final String OUTCOME_RESULT = "RESULT";

    private static final Logger log = LoggerFactory.getLogger(TurnResultPayload.class);

    private TurnResultPayload() {
    }

    /**
     * Flattens a turn that produced a result. The session id is intentionally not included — the
     * {@code SessionSignal} envelope already carries it.
     *
     * @param turnId
     *            the turn that produced {@code result} (must not be null)
     * @param idempotencyKey
     *            the submission's idempotency key, or {@code null} when it had none
     * @param result
     *            the terminal result (must not be null)
     * @return a fresh mutable map of JSON primitives (never null)
     */
    static Map<String, Object> toPayload(TurnId turnId, String idempotencyKey, AgentExecutionResult result) {
        Objects.requireNonNull(turnId, "turnId must not be null");
        Objects.requireNonNull(result, "result must not be null");
        final Map<String, Object> payload = envelope(turnId, idempotencyKey);
        payload.put(KEY_OUTCOME, OUTCOME_RESULT);
        payload.put(KEY_SUCCESS, result.isSuccess());
        // Null-valued entries are omitted rather than written: SessionSignal defensively copies the payload with
        // Map.copyOf, which rejects nulls outright.
        if (result.getFinalAnswer() != null) {
            payload.put(KEY_ANSWER, result.getFinalAnswer());
        }
        if (result.getErrorMessage() != null) {
            payload.put(KEY_RESULT_ERROR, result.getErrorMessage());
        }
        payload.put(KEY_COMPLETION, result.getCompletionReason().name());
        payload.put(KEY_STREAMED, result.wasStreamed());
        return payload;
    }

    /**
     * Flattens a turn that will never produce a result, so the waiting caller fails now instead of at its deadline.
     *
     * @param turnId
     *            the turn that failed, or {@code null} when the announcer cannot name it (holder-loss recovery)
     * @param idempotencyKey
     *            the submission's idempotency key, or {@code null} when it had none
     * @param code
     *            why no result is coming (must not be null)
     * @param message
     *            human-readable detail (must not be null)
     * @return a fresh mutable map of JSON primitives (never null)
     * @throws IllegalArgumentException
     *             when both addresses are null, which would announce to nobody
     */
    static Map<String, Object> toFailurePayload(TurnId turnId, String idempotencyKey, Failure.Code code,
            String message) {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        if (turnId == null && idempotencyKey == null) {
            throw new IllegalArgumentException("a turn-result failure needs a turn id or an idempotency key");
        }
        final Map<String, Object> payload = envelope(turnId, idempotencyKey);
        payload.put(KEY_OUTCOME, code.name());
        payload.put(KEY_MESSAGE, message);
        return payload;
    }

    private static Map<String, Object> envelope(TurnId turnId, String idempotencyKey) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        if (turnId != null) {
            payload.put(KEY_TURN, turnId.value());
        }
        if (idempotencyKey != null) {
            payload.put(KEY_IDEMPOTENCY_KEY, idempotencyKey);
        }
        return payload;
    }

    /**
     * Rebuilds the outcome from a payload produced by {@link #toPayload} / {@link #toFailurePayload} and possibly
     * normalized by a JSON codec on the way across the bus. Never throws: a null or malformed payload yields
     * {@link Optional#empty()} so a corrupt signal cannot break the receive path.
     *
     * @param payload
     *            the decoded signal payload (may be null)
     * @return the decoded outcome, or empty if the payload is absent/malformed
     */
    static Optional<Decoded> fromPayload(Map<String, Object> payload) {
        if (payload == null) {
            return Optional.empty();
        }
        try {
            final Object rawTurn = payload.get(KEY_TURN);
            final TurnId turnId = rawTurn == null ? null : TurnId.of(asString(rawTurn));
            final Object rawKey = payload.get(KEY_IDEMPOTENCY_KEY);
            final String idempotencyKey = rawKey == null ? null : asString(rawKey);
            if (turnId == null && idempotencyKey == null) {
                log.warn("Discarding turn-result payload with no address (neither turn id nor idempotency key)");
                return Optional.empty();
            }
            final String outcome = asString(payload.get(KEY_OUTCOME));
            if (OUTCOME_RESULT.equals(outcome)) {
                return Optional.of(new Decoded(turnId, idempotencyKey, decodeResult(payload), null));
            }
            final Failure failure = new Failure(Failure.Code.parse(outcome), failureMessage(payload, outcome));
            return Optional.of(new Decoded(turnId, idempotencyKey, null, failure));
        } catch (RuntimeException e) {
            log.warn("Discarding malformed turn-result payload: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * A failure's detail message, falling back to the raw outcome name when the payload carries none. Our own encoder
     * always writes one, so this only matters for a newer node's outcome whose payload is shaped differently — and
     * there
     * the same reasoning as {@link Failure.Code#parse} applies: failing the waiting caller with a terse reason beats
     * dropping the signal and leaving it to time out on the polling fallback.
     */
    private static String failureMessage(Map<String, Object> payload, String outcome) {
        final Object raw = payload.get(KEY_MESSAGE);
        return raw == null ? outcome : asString(raw);
    }

    private static AgentExecutionResult decodeResult(Map<String, Object> payload) {
        final StoredAgentExecutionResult.Builder builder = StoredAgentExecutionResult.builder()
                .success(asBoolean(payload.get(KEY_SUCCESS)))
                .completionReason(CompletionReason.valueOf(asString(payload.get(KEY_COMPLETION))))
                .wasStreamed(asBoolean(payload.get(KEY_STREAMED)));
        if (payload.containsKey(KEY_ANSWER)) {
            builder.finalAnswer(asString(payload.get(KEY_ANSWER)));
        }
        if (payload.containsKey(KEY_RESULT_ERROR)) {
            builder.errorMessage(asString(payload.get(KEY_RESULT_ERROR)));
        }
        return builder.build();
    }

    /** Decoded carrier: exactly one of {@link #result()} / {@link #failure()} is present. */
    static final class Decoded {
        private final TurnId turnId;
        private final String idempotencyKey;
        private final AgentExecutionResult result;
        private final Failure failure;

        Decoded(TurnId turnId, String idempotencyKey, AgentExecutionResult result, Failure failure) {
            this.turnId = turnId;
            this.idempotencyKey = idempotencyKey;
            this.result = result;
            this.failure = failure;
        }

        /** @return the turn this outcome belongs to, or empty when the announcer could not name it */
        Optional<TurnId> turnId() {
            return Optional.ofNullable(turnId);
        }

        Optional<String> idempotencyKey() {
            return Optional.ofNullable(idempotencyKey);
        }

        Optional<AgentExecutionResult> result() {
            return Optional.ofNullable(result);
        }

        Optional<Failure> failure() {
            return Optional.ofNullable(failure);
        }
    }

    /** Why a forwarded turn will never produce a result. */
    static final class Failure {

        /**
         * Terminal non-result outcomes a holder can announce.
         *
         * <p>
         * Only outcomes some node actually produces are listed. Later stages add more (a cancelled queued turn);
         * {@link #parse} maps any name it does not know to {@link #FAILED} so those arrive as a less precise reason on
         * an older node instead of being dropped.
         */
        enum Code {
            /** The holder attempted the turn and the attempt threw. */
            FAILED,
            /** The holder refused the turn: the session is bound to a different agent. */
            REJECTED,
            /**
             * The node that reserved this turn stopped renewing and was declared lost by {@link HolderLossSweeper}. Not
             * announced by the holder — by whichever surviving node won the reset — so it is the one code that arrives
             * addressed by the idempotency key alone.
             */
            HOLDER_LOST,
            /**
             * The holder's shutdown outlasted its grace window before this queued turn could start, so it stopped being
             * the holder rather than failing the work. The distinction is the caller's only way to tell "resubmit this,
             * it never ran" from {@link #FAILED}'s "this input was attempted and threw". Reserved for messages the
             * departing node had already collected: those are out of the at-most-once inbox, so no successor will ever
             * run them and telling their submitters is the only way they find out. A message still <em>in</em> the
             * inbox
             * is handed over instead of failed — announcing this for one would invite a retry alongside the turn a peer
             * then runs.
             */
            NOT_HOLDER;

            static Code parse(String raw) {
                for (Code code : values()) {
                    if (code.name().equals(raw)) {
                        return code;
                    }
                }
                log.debug("Unknown turn-result outcome '{}' — reporting as FAILED", raw);
                return FAILED;
            }
        }

        private final Code code;
        private final String message;

        Failure(Code code, String message) {
            this.code = code;
            this.message = message;
        }

        Code code() {
            return code;
        }

        String message() {
            return message;
        }
    }
}
