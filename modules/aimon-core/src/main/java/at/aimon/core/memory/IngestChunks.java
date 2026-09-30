package at.aimon.core.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.session.transcript.LegalCuts;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * Splits what is ingested into chunks the deriver can take in one LLM call.
 *
 * <p>
 * Compaction used to bound an ingest payload for free: the log was rewritten down to the window, so neither an
 * execution's delta nor a whole session's transcript could outgrow it. With an append-only log both can be several
 * windows long, and a deriver puts what it receives into one call. So ingest is sent in chunks of at most
 * {@code maxIngestTokens} each (context-engine §7).
 *
 * <p>
 * Chunks end at {@linkplain LegalCuts legal cuts} only — a deriver handed a {@code tool_result} without its
 * {@code tool_use} makes a call the provider rejects. The list is first split into the runs between consecutive legal
 * cuts, which cannot be split further, and those runs are packed greedily. A run that is larger than the budget on its
 * own becomes a chunk of its own rather than being cut; a chunk over budget is still a chunk the provider can read,
 * where a split pair is not.
 *
 * <p>
 * Stateless.
 */
public final class IngestChunks {

    /** The default {@code maxIngestTokens}: well inside the window of any model the deriver is likely to run on. */
    public static final int DEFAULT_MAX_INGEST_TOKENS = 32_000;

    private IngestChunks() {
    }

    /**
     * Splits {@code messages} into chunks of at most {@code maxTokens} estimated tokens, cut at legal cuts only.
     *
     * @param messages
     *            what is to be ingested, in order (must not be null nor contain null)
     * @param maxTokens
     *            the budget per chunk; {@code 0} or less sends everything as one chunk
     * @param estimator
     *            sizes each message (must not be null)
     * @return the chunks in order, none empty; empty when {@code messages} is (never null)
     */
    public static List<List<Message>> split(List<Message> messages, int maxTokens, TokenEstimator estimator) {
        Objects.requireNonNull(messages, "messages cannot be null");
        Objects.requireNonNull(estimator, "estimator cannot be null");
        if (messages.isEmpty()) {
            return List.of();
        }
        if (maxTokens <= 0) {
            return List.of(List.copyOf(messages));
        }
        final boolean[] legal = LegalCuts.legalPositions(messages);
        final List<List<Message>> chunks = new ArrayList<>();
        int chunkStart = 0;
        int chunkTokens = 0;
        int runStart = 0;
        int runTokens = 0;
        for (int position = 1; position <= messages.size(); position++) {
            runTokens += estimator.estimateMessage(messages.get(position - 1));
            if (!legal[position] && position < messages.size()) {
                continue;
            }
            // [runStart, position) is a run that cannot be cut. Close the chunk before it if it does not fit.
            if (chunkTokens > 0 && chunkTokens + runTokens > maxTokens) {
                chunks.add(List.copyOf(messages.subList(chunkStart, runStart)));
                chunkStart = runStart;
                chunkTokens = 0;
            }
            chunkTokens += runTokens;
            runStart = position;
            runTokens = 0;
        }
        chunks.add(List.copyOf(messages.subList(chunkStart, messages.size())));
        return List.copyOf(chunks);
    }
}
