package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.subagent.SubagentResultTruncation;

class SubagentResultFormatterTest {

    @Test
    void nullContentBecomesEmptyString() {
        assertThat(SubagentResultFormatter.truncateTailKeep(null, "ptr")).isEmpty();
    }

    @Test
    void emptyContentStaysEmpty() {
        assertThat(SubagentResultFormatter.truncateTailKeep("", "ptr")).isEmpty();
    }

    @Test
    void contentShorterThanBudgetIsUnchanged() {
        String content = "a short answer";

        assertThat(SubagentResultFormatter.truncateTailKeep(content, 100, "ptr")).isEqualTo(content);
    }

    @Test
    void contentEqualToBudgetIsUnchanged() {
        String content = "abcde";

        assertThat(SubagentResultFormatter.truncateTailKeep(content, 5, "ptr")).isEqualTo(content);
    }

    @Test
    void nonPositiveBudgetDisablesTruncation() {
        String content = "abcdefghij";

        assertThat(SubagentResultFormatter.truncateTailKeep(content, 0, "ptr")).isEqualTo(content);
        assertThat(SubagentResultFormatter.truncateTailKeep(content, -1, "ptr")).isEqualTo(content);
    }

    @Test
    void keepsTailAndMarksOmittedCount() {
        String content = "0123456789"; // 10 chars, keep last 4

        String result = SubagentResultFormatter.truncateTailKeep(content, 4, null);

        assertThat(result).endsWith("6789");
        assertThat(result).contains("6 chars omitted");
        // The dropped head (0-5) must not survive verbatim beyond the marker.
        assertThat(result).doesNotContain("012345");
    }

    @Test
    void includesRetrievalPointerWhenProvided() {
        String content = "0123456789";

        String result = SubagentResultFormatter.truncateTailKeep(content, 4, "AgentOutput(taskId=\"t1\")");

        assertThat(result).contains("6 chars omitted").contains("AgentOutput(taskId=\"t1\")");
    }

    @Test
    void omitsPointerWhenNullOrBlank() {
        String content = "0123456789";

        String withNull = SubagentResultFormatter.truncateTailKeep(content, 4, null);
        String withBlank = SubagentResultFormatter.truncateTailKeep(content, 4, "   ");

        assertThat(withNull).contains("chars omitted]").doesNotContain(" — ");
        assertThat(withBlank).contains("chars omitted]").doesNotContain(" — ");
    }

    @Test
    void defaultBudgetOverloadUsesThirtyTwoThousand() {
        // Just under the default budget: unchanged.
        String underBudget = "x".repeat(SubagentResultFormatter.DEFAULT_MAX_CHARS);
        assertThat(SubagentResultFormatter.truncateTailKeep(underBudget, null)).isEqualTo(underBudget);

        // Over the default budget: truncated to the tail.
        String overBudget = "x".repeat(SubagentResultFormatter.DEFAULT_MAX_CHARS + 100);
        String result = SubagentResultFormatter.truncateTailKeep(overBudget, null);
        assertThat(result).contains("100 chars omitted");
        assertThat(result.length()).isLessThan(overBudget.length());
    }

    @Test
    void doesNotSplitSurrogatePairAtCutBoundary() {
        // A run of astral-plane code points (each 2 chars). Cutting mid-pair would corrupt output; the formatter must
        // shift the cut forward to a code-point boundary so the tail decodes cleanly.
        String emoji = "😀"; // GRINNING FACE
        String content = emoji.repeat(10); // 20 chars, 10 code points

        // Budget 5 chars would nominally cut at index 15 — the low surrogate of the 8th pair. The guard shifts to 16.
        String result = SubagentResultFormatter.truncateTailKeep(content, 5, null);

        String tail = result.substring(result.indexOf('\n') + 1);
        // Tail must be a whole number of code points (no orphaned surrogate).
        assertThat(tail.length() % 2).isZero();
        assertThat(tail.codePointCount(0, tail.length())).isEqualTo(tail.length() / 2);
        assertThat(Character.isLowSurrogate(tail.charAt(0))).isFalse();
    }

    @Test
    void aBlockedForkIsNamedWithoutTheIncompleteAnswerClause() {
        // A fork an onStart hook refused has no answer at all, so the line names the reason and nothing more.
        assertThat(SubagentResultFormatter.completionReasonLine(CompletionReason.BLOCKED, false))
                .contains("Completion reason: BLOCKED");
    }

    @Test
    void aQuotaStoppedForkTellsTheParentThatRetryingWillNotHelp() {
        // The quota is the whole execution tree's, so the parent model must not read the stop as "try another fork".
        assertThat(SubagentResultFormatter.completionReasonLine(CompletionReason.QUOTA_EXCEEDED, false))
                .hasValue("Completion reason: QUOTA_EXCEEDED (" + SubagentResultFormatter.QUOTA_EXCEEDED_HINT + ")");
    }

    @Test
    void headKeepsTheBeginningAndMarksWhatFollows() {
        final String content = "FINDINGS" + "x".repeat(100);

        final String bounded = SubagentResultFormatter.truncate(content, 10, SubagentResultTruncation.HEAD, null);

        assertThat(bounded).isEqualTo("FINDINGSxx\n…[98 chars omitted]…");
    }

    @Test
    void headAndTailKeepBothEndsAndMarkTheMiddle() {
        final String content = "HEAD" + "x".repeat(100) + "TAIL";

        final String bounded = SubagentResultFormatter.truncate(content, 8, SubagentResultTruncation.HEAD_AND_TAIL,
                "see log");

        assertThat(bounded).isEqualTo("HEAD\n…[100 chars omitted — see log]…\nTAIL");
    }

    @Test
    void tailIsTheTailKeepItAlwaysWas() {
        final String content = "x".repeat(50) + "END";

        assertThat(SubagentResultFormatter.truncate(content, 10, SubagentResultTruncation.TAIL, "p"))
                .isEqualTo(SubagentResultFormatter.truncateTailKeep(content, 10, "p"));
        assertThat(SubagentResultFormatter.truncate("short", 10, SubagentResultTruncation.HEAD, null))
                .isEqualTo("short");
    }

    @Test
    void headDoesNotSplitASurrogatePair() {
        // "a" then an emoji (two chars): a 2-char head would end between its halves.
        final String content = "a\uD83D\uDE00" + "x".repeat(20);

        final String bounded = SubagentResultFormatter.truncate(content, 2, SubagentResultTruncation.HEAD, null);

        assertThat(bounded).startsWith("a\n…[");
    }

    @Test
    void aGraceAnswerSaysItIsWhatTheSubagentHadAtItsLimit() {
        assertThat(SubagentResultFormatter.completionReasonLine(CompletionReason.MAX_ITERATIONS_GRACE, true)).contains(
                "Completion reason: MAX_ITERATIONS_GRACE (" + SubagentResultFormatter.MAX_ITERATIONS_GRACE_HINT + ")");
    }
}
