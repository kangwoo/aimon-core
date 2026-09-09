package at.aimon.core.llms.anthropic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import at.aimon.core.llm.ReasoningTrace;

/**
 * The capture rule: given one assistant turn's output blocks in the order the provider emitted them, decides which
 * tool use each thinking block anchors to.
 *
 * <p>
 * <strong>The rule is not OpenAI's, and the difference is the point.</strong> OpenAI's is "each reasoning item anchors
 * to the first tool call that follows it"; transplanted here it is wrong, because Anthropic puts text <em>between</em>
 * the thinking block and the tool call on the ordinary shape {@code [thinking, text, tool_use]}. Anchoring that
 * thinking block to the tool call and then running the emit rule — unanchored first, then text, then each call
 * preceded by its own traces — replays it as {@code [text, thinking, tool_use]}: the turn no longer begins with a
 * thinking block, which extended mode requires, and the vendor's "the sequence of consecutive thinking blocks must
 * match what the model generated" check sees a rearrangement.
 *
 * <p>
 * So: <em>a thinking block anchors to the first {@code tool_use} that follows it, unless a {@code text} block
 * intervenes first, in which case it is unanchored.</em> Paired with the emit rule in
 * {@link AnthropicMessageConverter}, that reproduces the provider's own order for every single-text-block shape:
 *
 * <table border="1">
 * <caption>capture and emit, round-tripped</caption>
 * <tr>
 * <th>response content</th>
 * <th>anchors</th>
 * <th>emitted</th>
 * </tr>
 * <tr>
 * <td>{@code [think, text, tool_use]}</td>
 * <td>think → none</td>
 * <td>{@code [think, text, tool_use]}</td>
 * </tr>
 * <tr>
 * <td>{@code [think, tool_use]}</td>
 * <td>think → tu</td>
 * <td>{@code [think, tool_use]}</td>
 * </tr>
 * <tr>
 * <td>{@code [think1, think2, tool_use]}</td>
 * <td>both → tu</td>
 * <td>{@code [think1, think2, tool_use]}</td>
 * </tr>
 * <tr>
 * <td>{@code [think1, tu1, think2, tu2]}</td>
 * <td>t1→tu1, t2→tu2</td>
 * <td>{@code [think1, tu1, think2, tu2]}</td>
 * </tr>
 * <tr>
 * <td>{@code [think, text, tu1, think2, tu2]}</td>
 * <td>t1→none, t2→tu2</td>
 * <td>unchanged</td>
 * </tr>
 * <tr>
 * <td>{@code [think, text]}</td>
 * <td>think → none</td>
 * <td>{@code [think, text]}</td>
 * </tr>
 * </table>
 *
 * <p>
 * Two shapes are reordered, and both are losses {@code Message} makes unavoidable rather than ones this rule
 * introduces: thinking interleaved with <em>more than one</em> text block (adaptive-mode progress updates can produce
 * it), and text <em>after</em> a tool use. {@code Message} concatenates all text into one {@code getContent()} string,
 * so no emit rule could reproduce a multi-text turn.
 *
 * <p>
 * <strong>{@link Kind} is a projection of an output turn, not an inventory of it, and it has a known blind
 * spot.</strong>
 * Anthropic can also return {@code server_tool_use}, {@code web_search_tool_result} and {@code mcp_tool_use} blocks;
 * neither caller records those, so they are not barriers for the rule and a turn shaped
 * {@code [thinking, server_tool_use, tool_use]} would anchor the thinking block to the trailing {@code tool_use}
 * rather than leaving it unanchored. That is unreachable in AIMON today — no server-side tool is ever requested — and
 * it is recorded here rather than guarded against, because the fix belongs at the two call sites that decide what to
 * record. The {@code default -> throw} below is exhaustiveness over <em>this</em> enum, not over the protocol.
 *
 * <p>
 * <strong>Both paths run this function.</strong> The blocking converter feeds it {@code Message.content()} and the
 * streaming mapper feeds it the block sequence it observed. Two implementations of an ordering rule that must agree is
 * how the OpenAI round trip was nearly lost on its streaming path.
 *
 * <p>
 * Pure, stateless and free of SDK types, so the rule can be pinned by a unit test rather than only by a round trip.
 */
final class AnthropicOutputBlocks {

    private AnthropicOutputBlocks() {
    }

    /**
     * Applies the capture rule.
     *
     * @param blocks
     *            one assistant turn's output blocks, in provider order (must not be null)
     * @param providerName
     *            this client's provider name, stamped onto every trace (must not be null)
     * @return the traces in stored order, each with its anchor set
     */
    static List<ReasoningTrace> resolve(List<Block> blocks, String providerName) {
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(providerName, "providerName");

        final List<ReasoningTrace> traces = new ArrayList<>();
        // Thinking blocks seen since the last text or tool use, still waiting to learn their anchor.
        final List<String> pending = new ArrayList<>();

        for (Block block : blocks) {
            switch (block.kind) {
                case THINKING -> pending.add(block.payload);
                // Text intervening is what makes the preceding thinking blocks unanchored: they lead the message,
                // ahead of the text, which is where the provider put them.
                case TEXT -> flush(pending, null, providerName, traces);
                case TOOL_USE -> flush(pending, block.toolUseId, providerName, traces);
                default -> throw new IllegalStateException("Unhandled block kind: " + block.kind);
            }
        }
        // Trailing thinking with no tool use after it precedes the end of the turn.
        flush(pending, null, providerName, traces);
        return List.copyOf(traces);
    }

    private static void flush(List<String> pending, String toolUseId, String providerName,
            List<ReasoningTrace> traces) {
        for (String payload : pending) {
            traces.add(
                    ReasoningTrace.builder().providerName(providerName).payload(payload).toolUseId(toolUseId).build());
        }
        pending.clear();
    }

    /** What an output block is, as far as the anchor rule is concerned. */
    enum Kind {
        /** A {@code thinking} or {@code redacted_thinking} block. */
        THINKING,
        /** A {@code text} block. Only its position matters. */
        TEXT,
        /** A {@code tool_use} block. Only its id matters. */
        TOOL_USE
    }

    /**
     * One output block, reduced to what the anchor rule reads.
     *
     * <p>
     * Named factories rather than a builder: this is a three-variant discriminated union, and a builder would let a
     * caller assemble a {@code THINKING} carrying a tool use id — a state the rule has no meaning for. Immutability is
     * unchanged.
     */
    static final class Block {

        private final Kind kind;
        private final String payload;
        private final String toolUseId;

        private Block(Kind kind, String payload, String toolUseId) {
            this.kind = kind;
            this.payload = payload;
            this.toolUseId = toolUseId;
        }

        /**
         * @param payload
         *            the serialised block, as {@link AnthropicReasoningTraces} produced it (must not be null)
         * @return a thinking block
         */
        static Block thinking(String payload) {
            return new Block(Kind.THINKING, Objects.requireNonNull(payload, "payload"), null);
        }

        /** @return a text block */
        static Block text() {
            return new Block(Kind.TEXT, null, null);
        }

        /**
         * @param toolUseId
         *            the {@code toolu_…} id (must not be null)
         * @return a tool use block
         */
        static Block toolUse(String toolUseId) {
            return new Block(Kind.TOOL_USE, null, Objects.requireNonNull(toolUseId, "toolUseId"));
        }

        @Override
        public String toString() {
            return "Block{" + kind + (toolUseId == null ? "" : ", toolUseId=" + toolUseId) + '}';
        }
    }
}
