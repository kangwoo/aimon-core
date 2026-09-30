package at.aimon.core.agent.session.transcript;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * Where a message list may be cut without splitting a {@code tool_use} from its {@code tool_result}.
 *
 * <p>
 * A cut at position {@code p} — between {@code messages[p - 1]} and {@code messages[p]} — is <b>legal</b> when
 *
 * <ul>
 * <li>{@code messages[p]} (if there is one) is not a {@link Role#TOOL} message, so the part after the cut does not
 * start
 * with an orphaned tool result; and
 * <li>every {@code tool_use} in {@code messages[0..p)} is answered by a tool result in {@code messages[0..p)}, so the
 * part before the cut does not end with an unanswered call.
 * </ul>
 *
 * <p>
 * The start of the list is always legal; its end is legal once every call is answered. This is the condition the
 * compaction engine has always checked at a range's
 * cut points, extended by the second half; the view state operations ({@code summarize}, {@code drop}) and ingest
 * chunking all cut by it, because a provider rejects a request whose pairs are split and so does the deriver's LLM.
 * Design: {@code docs/design/agent-execution/context-engine.md} §3.4.
 *
 * <p>
 * Stateless.
 */
public final class LegalCuts {

    private LegalCuts() {
    }

    /**
     * Returns whether a cut at {@code position} is legal.
     *
     * @param messages
     *            the list (must not be null nor contain null)
     * @param position
     *            the cut, in {@code [0, messages.size()]}
     * @return whether the cut splits no tool pair
     * @throws IndexOutOfBoundsException
     *             if {@code position} is outside {@code [0, messages.size()]}
     */
    public static boolean isLegal(List<Message> messages, int position) {
        Objects.requireNonNull(messages, "messages cannot be null");
        if (position < 0 || position > messages.size()) {
            throw new IndexOutOfBoundsException("position " + position + " outside [0, " + messages.size() + "]");
        }
        if (position == 0) {
            return true;
        }
        if (position < messages.size() && isTool(messages.get(position))) {
            return false;
        }
        return allAnswered(messages, position);
    }

    /**
     * Returns, for every cut position {@code 0..messages.size()}, whether it is legal — in one pass, for callers that
     * look for cuts along the whole list.
     *
     * @param messages
     *            the list (must not be null nor contain null)
     * @return an array of {@code messages.size() + 1} flags, element {@code p} telling whether a cut at {@code p} is
     *         legal (never null)
     */
    public static boolean[] legalPositions(List<Message> messages) {
        Objects.requireNonNull(messages, "messages cannot be null");
        final boolean[] legal = new boolean[messages.size() + 1];
        final Set<String> pending = new HashSet<>();
        for (int position = 0; position <= messages.size(); position++) {
            final boolean startsWithTool = position < messages.size() && isTool(messages.get(position));
            legal[position] = position == 0 || (pending.isEmpty() && !startsWithTool);
            if (position < messages.size()) {
                track(pending, messages.get(position));
            }
        }
        return legal;
    }

    private static boolean allAnswered(List<Message> messages, int position) {
        final Set<String> pending = new HashSet<>();
        for (int i = 0; i < position; i++) {
            track(pending, messages.get(i));
        }
        return pending.isEmpty();
    }

    private static boolean isTool(Message message) {
        return message != null && message.getRole() == Role.TOOL;
    }

    private static void track(Set<String> pending, Message message) {
        if (message == null) {
            return;
        }
        for (ToolUse toolUse : message.getToolUses()) {
            pending.add(toolUse.getId());
        }
        for (ToolUseResult result : message.getToolUseResults()) {
            pending.remove(result.getToolUseId());
        }
    }
}
