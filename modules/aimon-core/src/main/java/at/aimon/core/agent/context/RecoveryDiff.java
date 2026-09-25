package at.aimon.core.agent.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.session.transcript.SeqRange;
import at.aimon.core.llm.Message;

/**
 * Maps a {@link at.aimon.core.agent.compact.PromptSizeRecoveryStrategy}'s answer back onto the log: which seq ranges
 * the strategy left out of the view it was shown.
 *
 * <p>
 * The strategy's contract is list in, list out, and it knows nothing of seqs. So the two lists are compared by
 * <b>position</b>: the answer must be a subsequence of the view, matched by instance, left to right — the same message
 * instance may appear twice, so matching by instance alone would be ambiguous, and matching positions in order is
 * not. The positions left unmatched are merged into runs before they become seq ranges, so an assistant
 * {@code tool_use} dropped together with its {@code TOOL} result is one range and is not refused as two illegal cuts
 * (context-engine §8.2).
 *
 * <p>
 * Refused, with the reason reported: an answer holding a message the view did not (a new one, or one the strategy
 * rewrote — a strategy that truncates a long tool result included); an answer that leaves out a message the view made
 * (a compaction marker, an elided placeholder), which has no seq of its own to drop; an answer that leaves out a
 * message the model has not answered yet ({@link ViewProjection#firstUnreadPosition()}) — the next call asks the
 * model to respond to it; an answer that leaves nothing out. Whether each range is a legal cut is checked by the view
 * state operation itself.
 */
final class RecoveryDiff {

    private final List<SeqRange> ranges;
    private final String refusal;

    private RecoveryDiff(List<SeqRange> ranges, String refusal) {
        this.ranges = ranges;
        this.refusal = refusal;
    }

    static RecoveryDiff between(ViewProjection view, List<Message> answer) {
        Objects.requireNonNull(view, "view cannot be null");
        Objects.requireNonNull(answer, "answer cannot be null");
        final List<Message> shown = view.getMessages();
        final List<Integer> left = new ArrayList<>();
        int next = 0;
        for (int position = 0; position < shown.size(); position++) {
            if (next < answer.size() && answer.get(next) == shown.get(position)) {
                next++;
            } else {
                left.add(position);
            }
        }
        if (next < answer.size()) {
            return refused(
                    "the answer holds a message the view did not send (new or rewritten), at answer position " + next);
        }
        if (left.isEmpty()) {
            return refused("the answer leaves nothing out");
        }
        final int unread = view.firstUnreadPosition();
        if (left.get(left.size() - 1) >= unread) {
            return refused("the answer leaves out a message the model has not answered yet (what follows the last"
                    + " assistant message) at view position " + left.get(left.size() - 1));
        }
        final List<SeqRange> ranges = new ArrayList<>();
        int runStart = -1;
        int previous = -2;
        for (int position : left) {
            if (!view.isVerbatim(position)) {
                return refused("the answer leaves out a message the view made (a compaction marker or an elided tool"
                        + " result) at view position " + position);
            }
            if (position != previous + 1) {
                if (runStart >= 0) {
                    ranges.add(SeqRange.of(view.sourceSeq(runStart), view.sourceSeq(previous) + 1));
                }
                runStart = position;
            }
            previous = position;
        }
        ranges.add(SeqRange.of(view.sourceSeq(runStart), view.sourceSeq(previous) + 1));
        return new RecoveryDiff(List.copyOf(ranges), null);
    }

    private static RecoveryDiff refused(String reason) {
        return new RecoveryDiff(List.of(), reason);
    }

    boolean isRefused() {
        return refusal != null;
    }

    String getRefusal() {
        return refusal;
    }

    /** The seq ranges to drop, merged runs of left-out view positions, ascending. Empty when refused. */
    List<SeqRange> getRanges() {
        return ranges;
    }
}
