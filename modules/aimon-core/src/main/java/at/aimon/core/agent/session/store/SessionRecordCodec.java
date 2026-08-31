package at.aimon.core.agent.session.store;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.subagent.task.codec.JsonSessionSnapshotCodec;
import at.aimon.core.subagent.task.codec.SessionSnapshotCodec;

/**
 * The wire encoding of a {@code SessionRecord}, shared by every distributed
 * {@link at.aimon.core.agent.session.store.SessionRecordStore} backend.
 *
 * <p>
 * It exists for the reason {@link StoredAgentExecutionResult} does: without it the Mongo, Postgres and Redis stores
 * would each carry their own copy of the same mapping, and a fleet running two of them would agree only by
 * coincidence. This class is the one place that decides what a persisted record looks like.
 *
 * <h2>Four values, not one blob</h2>
 *
 * <p>
 * A record is <b>not</b> encoded whole. {@code SessionRecordStore} has no full-record write precisely because its four
 * concerns have four owners, and a backend can only honour that if each concern is separately addressable in storage.
 * So this codec encodes each independently and the backend gives each its own column / field:
 *
 * <ul>
 * <li>{@code encodeTranscript} — system prompt + messages, the only large one
 * <li>{@link #encodeTotals(SessionTotals) encodeTotals} — the cumulative counters
 * <li>{@link #encodeBudgetOverride(ExecutionBudget) encodeBudgetOverride} — the runtime budget override, or
 * {@code null} when there is none
 * <li>{@code agentRef} and {@code compactionFailureCount} are not encoded at all — a string and an integer go to
 * storage natively, which is what lets the counter be a server-side {@code $inc} / {@code n = n + 1} instead of a
 * read-modify-write
 * </ul>
 *
 * <h2>The transcript is text, deliberately</h2>
 *
 * <p>
 * {@code encodeTranscript} delegates to {@link JsonSessionSnapshotCodec}, which already hand-maps the whole message
 * type graph losslessly, and hands back its output as an <b>opaque string</b>. Backends store that string; none of
 * them re-parses it into a native document, and there are two reasons neither of which is convenience.
 *
 * <ol>
 * <li><b>Mongo cannot hold it as a document.</b> A {@code ToolUse}'s input is an arbitrary {@code Map<String, Object>}
 * coming from a model, so its keys may contain {@code .} or start with {@code $} — both illegal in BSON field names.
 * Stored as a document, an ordinary tool call would fail to write.
 * <li><b>Postgres cannot hold it as {@code jsonb}.</b> {@code jsonb} rejects the NUL character (U+0000) inside
 * strings, and message text is model output that may contain it. Its escaped form survives fine in {@code text}.
 * </ol>
 *
 * <p>
 * The side fields have neither problem — their shapes are fixed and numeric — so those go to {@code jsonb} where the
 * backend has it.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class SessionRecordCodec {

    /** Format version of the side-field documents. The transcript carries its own, from its own codec. */
    public static final int FORMAT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SessionSnapshotCodec TRANSCRIPT = new JsonSessionSnapshotCodec();

    private static final String FIELD_VERSION = "version";
    private static final String FIELD_TURN_COUNT = "turnCount";
    private static final String FIELD_ITERATIONS = "iterations";
    private static final String FIELD_PROMPT_TOKENS = "promptTokens";
    private static final String FIELD_COMPLETION_TOKENS = "completionTokens";
    private static final String FIELD_TOTAL_TOKENS = "totalTokens";

    private static final String FIELD_MAX_ITERATIONS = "maxIterations";
    private static final String FIELD_MAX_TOKENS = "maxTokens";
    private static final String FIELD_MAX_WALL_CLOCK = "maxWallClockDuration";
    private static final String FIELD_COMPACTION_THRESHOLD = "compactionTokenThreshold";
    private static final String FIELD_MAX_COST_AMOUNT = "maxCostAmount";
    private static final String FIELD_MAX_COST_CURRENCY = "maxCostCurrency";

    private SessionRecordCodec() {
    }

    /**
     * Encodes the LLM-visible half of a record — system prompt plus messages — to its portable string form.
     *
     * @param sessionId
     *            the session the transcript belongs to (must not be null)
     * @param systemPrompt
     *            the system prompt (may be null)
     * @param messages
     *            the message history (must not be null, may be empty)
     * @return the encoded transcript, never null
     */
    public static String encodeTranscript(SessionId sessionId, String systemPrompt, List<Message> messages) {
        return TRANSCRIPT.encode(SessionSnapshot.of(sessionId, systemPrompt, messages));
    }

    /**
     * Encodes the LLM-visible half of {@code snapshot}.
     *
     * @param snapshot
     *            the snapshot to encode (must not be null)
     * @return the encoded transcript, never null
     */
    public static String encodeTranscript(SessionSnapshot snapshot) {
        return TRANSCRIPT.encode(snapshot);
    }

    /**
     * Encodes the LLM-visible half of {@code view}.
     *
     * @param view
     *            the record view to encode (must not be null)
     * @return the encoded transcript, never null
     */
    public static String encodeTranscript(SessionRecordView view) {
        return TRANSCRIPT.encode(SessionSnapshot.from(view));
    }

    /**
     * Decodes a transcript previously produced by one of the {@code encodeTranscript} overloads.
     *
     * <p>
     * A {@code null} or blank input decodes to an empty transcript for {@code sessionId} rather than failing: that is
     * what a record provisioned but never written to looks like in every backend, and it is a legitimate state — the
     * claim path establishes the record before the first turn has produced a single message.
     *
     * @param sessionId
     *            the session the record belongs to, used when there is nothing stored (must not be null)
     * @param encoded
     *            the stored transcript (may be null or blank)
     * @return the decoded snapshot, never null
     * @throws at.aimon.core.subagent.task.codec.SessionSnapshotCodecException
     *             if a non-blank input cannot be decoded
     */
    public static SessionSnapshot decodeTranscript(SessionId sessionId, String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return SessionSnapshot.of(sessionId);
        }
        return TRANSCRIPT.decode(encoded);
    }

    /**
     * Encodes the cumulative session totals.
     *
     * @param totals
     *            the totals to encode (must not be null)
     * @return the encoded totals, never null
     */
    public static String encodeTotals(SessionTotals totals) {
        final TokenUsage usage = totals.getTokenUsage();
        final ObjectNode node = MAPPER.createObjectNode();
        node.put(FIELD_VERSION, FORMAT_VERSION);
        node.put(FIELD_TURN_COUNT, totals.getTurnCount());
        node.put(FIELD_ITERATIONS, totals.getIterations());
        node.put(FIELD_PROMPT_TOKENS, usage.getPromptTokens());
        node.put(FIELD_COMPLETION_TOKENS, usage.getCompletionTokens());
        node.put(FIELD_TOTAL_TOKENS, usage.getTotalTokens());
        return node.toString();
    }

    /**
     * Decodes totals previously produced by {@link #encodeTotals(SessionTotals)}.
     *
     * <p>
     * A {@code null} or blank input decodes to {@link SessionTotals#empty()} — the value a record carries before its
     * first turn completes, which is also what {@code SessionRecordView} defaults to.
     *
     * @param encoded
     *            the stored totals (may be null or blank)
     * @return the decoded totals, never null
     * @throws IllegalArgumentException
     *             if a non-blank input is malformed
     */
    public static SessionTotals decodeTotals(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return SessionTotals.empty();
        }
        final JsonNode node = read(encoded, "session totals");
        final TokenUsage usage = TokenUsage.of(node.path(FIELD_PROMPT_TOKENS).asInt(),
                node.path(FIELD_COMPLETION_TOKENS).asInt(), node.path(FIELD_TOTAL_TOKENS).asInt());
        return SessionTotals.of(node.path(FIELD_TURN_COUNT).asInt(), node.path(FIELD_ITERATIONS).asInt(), usage);
    }

    /**
     * Encodes the runtime budget override.
     *
     * <p>
     * Returns {@code null} for a {@code null} override, and every backend stores that as SQL {@code NULL} / an absent
     * field. The distinction is load-bearing: {@link ExecutionBudget#unlimited()} is a <em>recorded decision</em> to
     * lift every limit and encodes as a document with no limit fields, whereas {@code null} means no override was ever
     * recorded and the opener's default still wins on re-open.
     *
     * @param budgetOverride
     *            the override to encode (may be null)
     * @return the encoded override, or null when there is none
     */
    public static String encodeBudgetOverride(ExecutionBudget budgetOverride) {
        if (budgetOverride == null) {
            return null;
        }
        final ObjectNode node = MAPPER.createObjectNode();
        node.put(FIELD_VERSION, FORMAT_VERSION);
        budgetOverride.getMaxIterations().ifPresent(v -> node.put(FIELD_MAX_ITERATIONS, v));
        budgetOverride.getMaxTokens().ifPresent(v -> node.put(FIELD_MAX_TOKENS, v));
        // ISO-8601 rather than millis: Duration keeps nanosecond resolution and toString/parse is exact both ways.
        budgetOverride.getMaxWallClockDuration().ifPresent(v -> node.put(FIELD_MAX_WALL_CLOCK, v.toString()));
        budgetOverride.getCompactionTokenThreshold().ifPresent(v -> node.put(FIELD_COMPACTION_THRESHOLD, v));
        budgetOverride.getMaxCostUsd().ifPresent(v -> {
            // The amount goes as a string: it is a BigDecimal with a scale of 10, and a JSON number would invite a
            // reader to take it as a double.
            node.put(FIELD_MAX_COST_AMOUNT, v.getAmount().toPlainString());
            node.put(FIELD_MAX_COST_CURRENCY, v.getCurrency());
        });
        return node.toString();
    }

    /**
     * Decodes a budget override previously produced by {@link #encodeBudgetOverride(ExecutionBudget)}.
     *
     * @param encoded
     *            the stored override (may be null or blank)
     * @return the decoded override, or null when none is recorded
     * @throws IllegalArgumentException
     *             if a non-blank input is malformed
     */
    public static ExecutionBudget decodeBudgetOverride(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        final JsonNode node = read(encoded, "budget override");
        final ExecutionBudget.Builder builder = ExecutionBudget.builder();
        if (node.hasNonNull(FIELD_MAX_ITERATIONS)) {
            builder.maxIterations(node.get(FIELD_MAX_ITERATIONS).asInt());
        }
        if (node.hasNonNull(FIELD_MAX_TOKENS)) {
            builder.maxTokens(node.get(FIELD_MAX_TOKENS).asInt());
        }
        if (node.hasNonNull(FIELD_MAX_WALL_CLOCK)) {
            builder.maxWallClockDuration(Duration.parse(node.get(FIELD_MAX_WALL_CLOCK).asText()));
        }
        if (node.hasNonNull(FIELD_COMPACTION_THRESHOLD)) {
            builder.compactionTokenThreshold(node.get(FIELD_COMPACTION_THRESHOLD).asInt());
        }
        if (node.hasNonNull(FIELD_MAX_COST_AMOUNT)) {
            final String currency = node.path(FIELD_MAX_COST_CURRENCY).asText(Money.USD);
            builder.maxCostUsd(Money.of(new BigDecimal(node.get(FIELD_MAX_COST_AMOUNT).asText()), currency));
        }
        return builder.build();
    }

    private static JsonNode read(String encoded, String what) {
        try {
            return MAPPER.readTree(encoded);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed stored " + what + ": " + e.getMessage(), e);
        }
    }
}
