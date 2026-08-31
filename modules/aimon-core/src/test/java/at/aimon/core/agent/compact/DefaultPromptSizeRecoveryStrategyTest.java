package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.exception.LlmPromptTooLongException;

/**
 * Unit tests for {@link DefaultPromptSizeRecoveryStrategy}: the strategy must drop the oldest non-protected user
 * message, leave the most recent user message and the compaction marker pair untouched, and decline (NONE) when no safe
 * drop is available.
 */
class DefaultPromptSizeRecoveryStrategyTest {

    private final DefaultPromptSizeRecoveryStrategy strategy = new DefaultPromptSizeRecoveryStrategy();
    private final LlmPromptTooLongException error = new LlmPromptTooLongException("context exceeded", 10000, 8192);

    @Test
    void emptyMessagesReturnsNone() {
        PromptSizeRecoveryDecision decision = strategy.recover(List.of(), error);

        assertThat(decision.getAction()).isEqualTo(PromptSizeRecoveryDecision.Action.NONE);
        assertThat(decision.getRecoveredMessages()).isEmpty();
    }

    @Test
    void dropsOldestUserMessageAndKeepsTheRest() {
        Message older = Message.user("oldest");
        Message middle = Message.assistant("answer 1");
        Message latestUser = Message.user("latest");
        List<Message> messages = List.of(older, middle, latestUser);

        PromptSizeRecoveryDecision decision = strategy.recover(messages, error);

        assertThat(decision.getAction()).isEqualTo(PromptSizeRecoveryDecision.Action.RETRY);
        assertThat(decision.getRecoveredMessages()).hasValueSatisfying(list -> {
            assertThat(list).containsExactly(middle, latestUser);
        });
    }

    @Test
    void keepsMostRecentUserMessageEvenIfOldestIsProtected() {
        Message latestUser = Message.user("only user message");

        PromptSizeRecoveryDecision decision = strategy.recover(List.of(latestUser), error);

        assertThat(decision.getAction()).isEqualTo(PromptSizeRecoveryDecision.Action.NONE);
    }

    @Test
    void skipsCompactBoundaryMarker() {
        Message boundary = CompactBoundary.boundaryMessage("uuid-1", CompactionTrigger.AUTO, 1000, 5, List.of("Read"));
        Message summary = CompactBoundary.summaryMessage("uuid-1", "summary text");
        Message regularOlder = Message.user("regular older");
        Message latestUser = Message.user("latest");
        List<Message> messages = List.of(boundary, summary, regularOlder, latestUser);

        PromptSizeRecoveryDecision decision = strategy.recover(messages, error);

        assertThat(decision.getAction()).isEqualTo(PromptSizeRecoveryDecision.Action.RETRY);
        assertThat(decision.getRecoveredMessages()).hasValueSatisfying(list -> {
            assertThat(list).containsExactly(boundary, summary, latestUser);
        });
    }

    @Test
    void noneWhenOnlyMarkersAndLatestUserRemain() {
        Message boundary = CompactBoundary.boundaryMessage("uuid-2", CompactionTrigger.AUTO, 1000, 5, List.of());
        Message summary = CompactBoundary.summaryMessage("uuid-2", "summary text");
        Message latestUser = Message.user("latest");

        PromptSizeRecoveryDecision decision = strategy.recover(List.of(boundary, summary, latestUser), error);

        assertThat(decision.getAction()).isEqualTo(PromptSizeRecoveryDecision.Action.NONE);
    }

    @Test
    void doesNotDropAssistantOrToolMessages() {
        Message assistantWithToolUse = Message.assistant("calling tool",
                List.of(ToolUse.of("call_1", "Read", Map.of("file_path", "/etc/hosts"))));
        Message toolResult = Message.toolUseResults(List.of(ToolUseResult.success("call_1", "127.0.0.1 localhost")));
        Message latestUser = Message.user("ok thanks");
        List<Message> messages = List.of(assistantWithToolUse, toolResult, latestUser);

        PromptSizeRecoveryDecision decision = strategy.recover(messages, error);

        // No older user message exists; the strategy must NOT touch the assistant turn or its tool_result.
        assertThat(decision.getAction()).isEqualTo(PromptSizeRecoveryDecision.Action.NONE);
    }

    @Test
    void dropsFirstSafeUserAmongMultiple() {
        Message firstUser = Message.user("u1");
        Message secondUser = Message.user("u2");
        Message thirdUser = Message.user("u3 (latest)");

        PromptSizeRecoveryDecision decision = strategy.recover(List.of(firstUser, secondUser, thirdUser), error);

        assertThat(decision.getAction()).isEqualTo(PromptSizeRecoveryDecision.Action.RETRY);
        assertThat(decision.getRecoveredMessages()).hasValueSatisfying(list -> {
            assertThat(list).containsExactly(secondUser, thirdUser);
        });
    }
}
