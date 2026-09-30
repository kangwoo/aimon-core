package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.token.TokenEstimator;

class IngestChunksTest {

    /** Every message costs 10 tokens, so budgets read as message counts. */
    private static final TokenEstimator TEN_EACH = new TokenEstimator() {
        @Override
        public int estimate(String systemPrompt, List<Message> messages) {
            return messages.size() * 10;
        }

        @Override
        public int estimateMessage(Message message) {
            return 10;
        }

        @Override
        public int estimateText(String text) {
            return 0;
        }
    };

    private static List<Message> turn(String id) {
        return List.of(Message.user("q" + id), Message.assistant("", List.of(ToolUse.of(id, "Read", Map.of()))),
                Message.toolUseResults(List.of(ToolUseResult.success(id, "body"))), Message.assistant("a" + id));
    }

    @Test
    void fewMessagesAreOneChunk() {
        assertThat(IngestChunks.split(turn("1"), 1000, TEN_EACH)).hasSize(1);
        assertThat(IngestChunks.split(List.of(), 1000, TEN_EACH)).isEmpty();
    }

    @Test
    void chunksAreCutAtLegalCutsOnlyAndStayWithinTheBudget() {
        final List<Message> messages = new ArrayList<>(turn("1"));
        messages.addAll(turn("2"));
        messages.addAll(turn("3"));

        final List<List<Message>> chunks = IngestChunks.split(messages, 40, TEN_EACH);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).hasSize(4));
        assertThat(chunks.stream().flatMap(List::stream).toList()).isEqualTo(messages);
    }

    @Test
    void aRunLargerThanTheBudgetIsKeptWholeRatherThanSplittingAPair() {
        final List<List<Message>> chunks = IngestChunks.split(turn("1"), 15, TEN_EACH);

        // Legal cuts: 0, 1 (after the user message), 3 (after the result), 4. The call and its result stay together.
        assertThat(chunks).extracting(List::size).containsExactly(1, 2, 1);
    }

    @Test
    void noBudgetMeansOneChunk() {
        assertThat(IngestChunks.split(turn("1"), 0, TEN_EACH)).hasSize(1);
    }
}
