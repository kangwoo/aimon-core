package at.aimon.core.agent.compact;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUseResult;

/**
 * Cheap, LLM-free pre-pass that scrubs the body of stale {@code tool_result} messages so they no longer occupy token
 * budget while preserving message structure (role, tool-use IDs, error flags) for downstream provider serialisation.
 *
 * <p>
 * This is the L0 layer in the conversation compaction stack described in
 * {@code docs/design/agent-execution/compaction.md} §9.1. Unlike the L3
 * full {@link CompactionEngine}, no LLM call is made: tool result content older than {@code maxAge} is replaced
 * verbatim with the {@link #CLEARED_PLACEHOLDER} string. The original tool-use ID and error flag are kept so the
 * provider conversation contract remains valid.
 *
 * <h2>Idempotency</h2>
 *
 * <p>
 * Re-running the pass over the same memory is a no-op: a tool result whose content already equals the placeholder is
 * skipped. The associated message timestamp is preserved (see
 * {@link TranscriptBuffer#replaceMessageAt(int, Message)})
 * so a subsequent invocation still recognises the slot as aged out.
 *
 * <h2>Selection rule</h2>
 *
 * <p>
 * For each message {@code i} in {@link TranscriptBuffer#getMessages()} with role {@code TOOL} (i.e.
 * {@link Message#hasToolResults()} is true), the pass examines the parallel
 * {@link TranscriptBuffer#getMessageTimestamps()
 * timestamp} entry. If {@code clock.instant().minus(maxAge)} is at or after that timestamp, every contained
 * {@link ToolUseResult#getContent()} is replaced with {@link #CLEARED_PLACEHOLDER}. The most recent {@code keepRecent}
 * tool messages are always retained verbatim — they are typically still being reasoned about by the agent.
 *
 * <p>
 * Stateless after construction; thread-safe (the only mutable state lives in the {@link TranscriptBuffer} passed in,
 * whose own thread-safety contract is documented on that class).
 */
public final class TimeBasedMicrocompact {

    /** Replacement body used for cleared tool-result content. */
    public static final String CLEARED_PLACEHOLDER = "[Old tool result cleared]";

    /** Default number of most-recent tool-result messages exempt from clearing. */
    public static final int DEFAULT_KEEP_RECENT = 2;

    private static final Logger log = LoggerFactory.getLogger(TimeBasedMicrocompact.class);

    private final Duration maxAge;
    private final int keepRecent;
    private final Clock clock;

    /**
     * Creates a microcompact pass with {@link #DEFAULT_KEEP_RECENT} retained recent tool messages and the system UTC
     * clock.
     *
     * @param maxAge
     *            tool result age threshold; messages strictly older than this are eligible for clearing (must not be
     *            null and must be positive)
     * @throws NullPointerException
     *             if {@code maxAge} is null
     * @throws IllegalArgumentException
     *             if {@code maxAge} is zero or negative
     */
    public TimeBasedMicrocompact(Duration maxAge) {
        this(maxAge, DEFAULT_KEEP_RECENT, Clock.systemUTC());
    }

    /**
     * Creates a microcompact pass with an explicit {@code keepRecent} cap and the system UTC clock.
     *
     * @param maxAge
     *            tool result age threshold (must not be null and must be positive)
     * @param keepRecent
     *            number of most-recent tool-result messages always retained verbatim (must be {@code >= 0})
     * @throws NullPointerException
     *             if {@code maxAge} is null
     * @throws IllegalArgumentException
     *             if {@code maxAge} is non-positive or {@code keepRecent} is negative
     */
    public TimeBasedMicrocompact(Duration maxAge, int keepRecent) {
        this(maxAge, keepRecent, Clock.systemUTC());
    }

    /**
     * Creates a microcompact pass with a fully injected {@link Clock} for deterministic tests.
     *
     * @param maxAge
     *            tool result age threshold (must not be null and must be positive)
     * @param keepRecent
     *            number of most-recent tool-result messages always retained verbatim (must be {@code >= 0})
     * @param clock
     *            clock used to resolve "now" against the per-message timestamps (must not be null)
     * @throws NullPointerException
     *             if {@code maxAge} or {@code clock} is null
     * @throws IllegalArgumentException
     *             if {@code maxAge} is non-positive or {@code keepRecent} is negative
     */
    public TimeBasedMicrocompact(Duration maxAge, int keepRecent, Clock clock) {
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must be positive, got: " + maxAge);
        }
        if (keepRecent < 0) {
            throw new IllegalArgumentException("keepRecent must be >= 0, got: " + keepRecent);
        }
        this.keepRecent = keepRecent;
    }

    /**
     * Scrubs stale tool-result message bodies in {@code memory} according to the configured policy.
     *
     * <p>
     * The caller must serialise access to {@code memory} (typically the agent's main loop thread, optionally guarded by
     * {@link CompactionGuard} when running alongside L3 compaction). The pass mutates {@code memory} via
     * {@link TranscriptBuffer#replaceMessageAt(int, Message)} so timestamps and ordering are preserved.
     *
     * @param memory
     *            the transcript buffer to compact (must not be null)
     * @return the number of tool-result messages whose content was replaced; {@code 0} if nothing aged out
     * @throws NullPointerException
     *             if {@code memory} is null
     */
    public int compact(TranscriptBuffer memory) {
        Objects.requireNonNull(memory, "memory cannot be null");

        final List<Message> snapshot = memory.getMessages();
        final List<Instant> timestamps = memory.getMessageTimestamps();
        if (snapshot.size() != timestamps.size()) {
            // Defensive: should be impossible while TranscriptBuffer keeps the parallel arrays in lockstep.
            log.warn("Skipping microcompact: message/timestamp length mismatch ({} vs {})", snapshot.size(),
                    timestamps.size());
            return 0;
        }

        final Instant cutoff = clock.instant().minus(maxAge);
        final List<Integer> toolMessageIndices = collectToolMessageIndices(snapshot);
        final int eligibleUpperBound = Math.max(0, toolMessageIndices.size() - keepRecent);

        int cleared = 0;
        for (int rank = 0; rank < eligibleUpperBound; rank++) {
            final int index = toolMessageIndices.get(rank);
            if (!timestamps.get(index).isBefore(cutoff)) {
                continue;
            }
            final Message original = snapshot.get(index);
            if (allResultsAlreadyCleared(original)) {
                continue;
            }
            memory.replaceMessageAt(index, withClearedToolResults(original));
            cleared++;
        }

        if (cleared > 0) {
            log.debug("TimeBasedMicrocompact cleared {} tool-result message(s) older than {} (keepRecent={})", cleared,
                    maxAge, keepRecent);
        }
        return cleared;
    }

    private static List<Integer> collectToolMessageIndices(List<Message> messages) {
        final List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).hasToolResults()) {
                indices.add(i);
            }
        }
        return indices;
    }

    private static boolean allResultsAlreadyCleared(Message message) {
        for (ToolUseResult result : message.getToolUseResults()) {
            if (!CLEARED_PLACEHOLDER.equals(result.getContent())) {
                return false;
            }
        }
        return !message.getToolUseResults().isEmpty();
    }

    private static Message withClearedToolResults(Message original) {
        final List<ToolUseResult> rewritten = new ArrayList<>(original.getToolUseResults().size());
        for (ToolUseResult source : original.getToolUseResults()) {
            if (CLEARED_PLACEHOLDER.equals(source.getContent())) {
                rewritten.add(source);
                continue;
            }
            // Preserve the original error flag so the LLM still sees the result kind, only the body is scrubbed.
            final ToolUseResult cleared = source.isError()
                    ? ToolUseResult.error(source.getToolUseId(), CLEARED_PLACEHOLDER)
                    : ToolUseResult.success(source.getToolUseId(), CLEARED_PLACEHOLDER);
            rewritten.add(cleared);
        }
        return Message.toolUseResults(rewritten);
    }
}
