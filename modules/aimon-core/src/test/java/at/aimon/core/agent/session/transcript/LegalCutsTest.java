package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

class LegalCutsTest {

    private static final List<Message> MESSAGES = List.of(Message.user("q"),
            Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()), ToolUse.of("t2", "Read", Map.of()))),
            Message.toolUseResults(List.of(ToolUseResult.success("t1", "a"))),
            Message.toolUseResults(List.of(ToolUseResult.success("t2", "b"))), Message.assistant("done"),
            Message.assistant("", List.of(ToolUse.of("t3", "Read", Map.of()))));

    @Test
    void aCutIsLegalOnlyWhereNoPairIsSplit() {
        assertThat(LegalCuts.isLegal(MESSAGES, 0)).isTrue();
        assertThat(LegalCuts.isLegal(MESSAGES, 1)).isTrue();
        assertThat(LegalCuts.isLegal(MESSAGES, 2)).as("a result would start the part after").isFalse();
        assertThat(LegalCuts.isLegal(MESSAGES, 3)).as("t2 is still unanswered before it").isFalse();
        assertThat(LegalCuts.isLegal(MESSAGES, 4)).isTrue();
        assertThat(LegalCuts.isLegal(MESSAGES, 5)).isTrue();
        assertThat(LegalCuts.isLegal(MESSAGES, 6)).as("t3 is unanswered at the end").isFalse();
    }

    @Test
    void theOnePassAnswerAgreesWithThePointwiseOne() {
        final boolean[] legal = LegalCuts.legalPositions(MESSAGES);

        assertThat(legal).hasSize(MESSAGES.size() + 1);
        for (int position = 0; position <= MESSAGES.size(); position++) {
            assertThat(legal[position]).as("position " + position).isEqualTo(LegalCuts.isLegal(MESSAGES, position));
        }
    }
}
