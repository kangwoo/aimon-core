package at.aimon.core.agent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.compact.CompactBoundary;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.session.transcript.SessionLogEntry;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionViewState;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUseResult;

/**
 * The LLM view of a session log: the log's entries with its {@link SessionViewState} applied.
 *
 * <p>
 * From the entries the log carries, in seq order:
 *
 * <ol>
 * <li>an entry in a dropped range is left out;
 * <li>the entries in the summary span are replaced, once, by the boundary / summary marker pair
 * {@link CompactBoundary} has always built, from the metadata the span stores — at the position of the span's first
 * surviving entry, or at the end when none survives;
 * <li>an elided tool result keeps its tool_use ids and error flags and shows the placeholder as its body.
 * </ol>
 *
 * <p>
 * <b>Deterministic.</b> The inputs are the entries and the view state and nothing else — no clock, no randomness, no
 * node-local state — so two nodes projecting the same record send the same view, and the view only changes when the
 * log or the view state does (context-engine §3.3). Entries shown as they are keep their {@link Message} instance,
 * which is what lets a recovery strategy's answer be mapped back onto seqs (context-engine §8.2).
 *
 * <p>
 * Immutable.
 */
public final class ViewProjection {

    /** {@link #sourceSeq(int)} for a message the view made up: a boundary or summary marker. */
    public static final long MADE_BY_VIEW = -1L;

    private final List<Message> messages;
    private final long[] sourceSeqs;
    private final boolean[] verbatim;

    private ViewProjection(List<Message> messages, long[] sourceSeqs, boolean[] verbatim) {
        this.messages = Collections.unmodifiableList(messages);
        this.sourceSeqs = sourceSeqs;
        this.verbatim = verbatim;
    }

    /**
     * Projects the view of {@code state}.
     *
     * @param state
     *            the log (must not be null)
     * @return the view (never null)
     */
    public static ViewProjection of(SessionLogState state) {
        Objects.requireNonNull(state, "state cannot be null");
        final SessionViewState viewState = state.getViewState();
        final SummarySpan span = viewState.getSummarySpan().orElse(null);
        final List<SessionLogEntry> entries = state.getEntries();
        final List<Message> messages = new ArrayList<>(entries.size() + 2);
        final List<Long> seqs = new ArrayList<>(entries.size() + 2);
        final List<Boolean> asIs = new ArrayList<>(entries.size() + 2);
        boolean markersPlaced = span == null;
        for (SessionLogEntry entry : entries) {
            final long seq = entry.getSeq();
            if (viewState.isDropped(seq)) {
                continue;
            }
            if (!markersPlaced && seq >= span.getFromSeq()) {
                placeMarkers(span, messages, seqs, asIs);
                markersPlaced = true;
            }
            if (span != null && span.getRange().contains(seq)) {
                continue;
            }
            final String placeholder = viewState.getElisions().get(seq);
            if (placeholder != null && entry.getMessage() != null) {
                messages.add(elide(entry.getMessage(), placeholder));
                asIs.add(Boolean.FALSE);
            } else {
                messages.add(entry.getMessage());
                asIs.add(Boolean.TRUE);
            }
            seqs.add(seq);
        }
        if (!markersPlaced) {
            placeMarkers(span, messages, seqs, asIs);
        }
        final long[] sourceSeqs = new long[seqs.size()];
        final boolean[] verbatim = new boolean[asIs.size()];
        for (int i = 0; i < sourceSeqs.length; i++) {
            sourceSeqs[i] = seqs.get(i);
            verbatim[i] = asIs.get(i);
        }
        return new ViewProjection(messages, sourceSeqs, verbatim);
    }

    /**
     * @return the messages to send, in order (never null, unmodifiable)
     */
    public List<Message> getMessages() {
        return messages;
    }

    /**
     * @return how many messages the view holds
     */
    public int size() {
        return messages.size();
    }

    /**
     * Returns the seq of the log entry shown at {@code position}.
     *
     * @param position
     *            a position in {@link #getMessages()}
     * @return the seq, or {@link #MADE_BY_VIEW} for a marker message
     */
    public long sourceSeq(int position) {
        return sourceSeqs[position];
    }

    /**
     * Returns whether the message at {@code position} is a log entry shown as it is — the same {@link Message}
     * instance the log holds — rather than a marker or an elided tool result the view built.
     *
     * @param position
     *            a position in {@link #getMessages()}
     * @return whether the message is the log's own
     */
    public boolean isVerbatim(int position) {
        return verbatim[position];
    }

    private static void placeMarkers(SummarySpan span, List<Message> messages, List<Long> seqs, List<Boolean> asIs) {
        messages.add(CompactBoundary.boundaryMessage(span.getBoundaryId(), triggerOf(span), span.getPreTokenCount(),
                span.getMessagesSummarized(), span.getDiscoveredToolNames()));
        messages.add(CompactBoundary.summaryMessage(span.getBoundaryId(), span.getSummaryText()));
        seqs.add(MADE_BY_VIEW);
        seqs.add(MADE_BY_VIEW);
        asIs.add(Boolean.FALSE);
        asIs.add(Boolean.FALSE);
    }

    /**
     * The span's trigger, read leniently: the projection runs at every {@code prepare}, so a persisted value this node
     * does not know — a future enum constant, a damaged document — must not make the session unable to run a turn.
     * The trigger only labels the boundary marker, so {@link CompactionTrigger#AUTO} is a safe, deterministic stand-in.
     */
    private static CompactionTrigger triggerOf(SummarySpan span) {
        for (CompactionTrigger trigger : CompactionTrigger.values()) {
            if (trigger.name().equals(span.getTrigger())) {
                return trigger;
            }
        }
        return CompactionTrigger.AUTO;
    }

    private static Message elide(Message original, String placeholder) {
        if (!original.hasToolResults()) {
            return original;
        }
        final List<ToolUseResult> results = new ArrayList<>(original.getToolUseResults().size());
        for (ToolUseResult result : original.getToolUseResults()) {
            results.add(result.isError()
                    ? ToolUseResult.error(result.getToolUseId(), placeholder)
                    : ToolUseResult.success(result.getToolUseId(), placeholder));
        }
        return Message.toolUseResults(results);
    }
}
