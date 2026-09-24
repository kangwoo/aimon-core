package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * Pins the log value itself: seq addressing, the invariants the constructor checks, and the two operations that cut the
 * log — {@link SessionLogState#truncateFrom(long)} for a rewind and {@link SessionLogState#clear()} for {@code /clear}.
 */
class SessionLogStateTest {

    private static final Message ASK = Message.user("ask");
    private static final Message ANSWER = Message.assistant("answer");
    private static final Message REMINDER = Message.user("<system-reminder>ctx</system-reminder>");

    @Test
    void aVersionOneMessageListBecomesSeqsZeroToNMinusOneAllConversation() {
        final SessionLogState state = SessionLogState.ofMessages(List.of(ASK, ANSWER));

        assertThat(state.getEntries()).extracting(SessionLogEntry::getSeq).containsExactly(0L, 1L);
        assertThat(state.getEntries()).extracting(SessionLogEntry::getOrigin).containsOnly(LogOrigin.CONVERSATION);
        assertThat(state.getNextSeq()).isEqualTo(2);
        assertThat(state.getFloorSeq()).isZero();
        assertThat(state.getFormat()).isEqualTo(SessionLogFormat.V1);
        assertThat(state.getMessages()).containsExactly(ASK, ANSWER);
    }

    @Test
    void anEmptyMessageListIsTheSharedEmptyState() {
        assertThat(SessionLogState.ofMessages(List.of())).isSameAs(SessionLogState.empty());
    }

    @Test
    void entrySeqsMustStrictlyIncreaseWithinFloorAndNext() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SessionLogState.builder()
                        .entries(List.of(SessionLogEntry.of(1, ASK, LogOrigin.CONVERSATION),
                                SessionLogEntry.of(1, ANSWER, LogOrigin.CONVERSATION)))
                        .nextSeq(5).build())
                .withMessageContaining("strictly increase");
        assertThatIllegalArgumentException().isThrownBy(() -> SessionLogState.builder()
                .entries(List.of(SessionLogEntry.of(2, ASK, LogOrigin.CONVERSATION))).floorSeq(3).nextSeq(5).build())
                .withMessageContaining("outside");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SessionLogState.builder()
                        .entries(List.of(SessionLogEntry.of(5, ASK, LogOrigin.CONVERSATION))).nextSeq(5).build())
                .withMessageContaining("outside");
        assertThatIllegalArgumentException().isThrownBy(() -> SessionLogState.builder().floorSeq(6).nextSeq(5).build())
                .withMessageContaining("floorSeq");
    }

    @Test
    void aRewindPointMustLieBetweenFloorAndNext() {
        final SessionLogState.Builder builder = SessionLogState.builder().floorSeq(2).nextSeq(4);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.rewindPoint(SessionRewindPoint.of(1, TextInput.of("x"))).build());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.rewindPoint(SessionRewindPoint.of(5, TextInput.of("x"))).build());
        assertThat(builder.rewindPoint(SessionRewindPoint.of(4, TextInput.of("x"))).build().getRewindPoint())
                .isPresent();
    }

    @Test
    void appendingTakesTheNextSeqAndKeepsTheOrigin() {
        final SessionLogState state = SessionLogState.empty().append(REMINDER, LogOrigin.SYNTHETIC).append(ASK,
                LogOrigin.CONVERSATION);

        assertThat(state.getEntries()).containsExactly(SessionLogEntry.of(0, REMINDER, LogOrigin.SYNTHETIC),
                SessionLogEntry.of(1, ASK, LogOrigin.CONVERSATION));
        assertThat(state.getNextSeq()).isEqualTo(2);
    }

    /**
     * Seqs are never reused: whatever pointed at a dropped entry must not come to mean the next one appended.
     */
    @Test
    void truncatingKeepsNextSeqSoTheCutSeqsAreNeverHandedOutAgain() {
        final SessionLogState cut = SessionLogState.ofMessages(List.of(ASK, ANSWER, ASK)).truncateFrom(1);

        assertThat(cut.getMessages()).containsExactly(ASK);
        assertThat(cut.getNextSeq()).isEqualTo(3);
        assertThat(cut.append(ANSWER, LogOrigin.CONVERSATION).getEntries()).extracting(SessionLogEntry::getSeq)
                .containsExactly(0L, 3L);
    }

    @Test
    void truncatingDropsARewindPointAtOrAfterTheCutAndKeepsOneBefore() {
        final SessionLogState base = SessionLogState.ofMessages(List.of(ASK, ANSWER, ASK, ANSWER));
        final SessionRewindPoint early = SessionRewindPoint.of(1, TextInput.of("x"));
        final SessionRewindPoint late = SessionRewindPoint.of(2, TextInput.of("x"));

        assertThat(base.withRewindPoint(late).truncateFrom(2).getRewindPoint()).isEmpty();
        assertThat(base.withRewindPoint(early).truncateFrom(2).getRewindPoint()).contains(early);
    }

    @Test
    void truncatingPastTheEndIsANoOp() {
        final SessionLogState state = SessionLogState.ofMessages(List.of(ASK));

        assertThat(state.truncateFrom(5)).isSameAs(state);
    }

    @Test
    void rewindingCutsAtThePointAndDropsIt() {
        final SessionLogState state = SessionLogState.ofMessages(List.of(ASK, ANSWER, ASK, ANSWER),
                SessionRewindPoint.of(2, TextInput.of("x")));

        final SessionLogState rewound = state.rewind();

        assertThat(rewound.getMessages()).containsExactly(ASK, ANSWER);
        assertThat(rewound.getRewindPoint()).isEmpty();
        assertThat(rewound.rewind()).isSameAs(rewound);
    }

    @Test
    void clearingRaisesTheFloorToNextSeqAndKeepsCounting() {
        final SessionLogState cleared = SessionLogState
                .ofMessages(List.of(ASK, ANSWER), SessionRewindPoint.of(1, TextInput.of("x"))).clear();

        assertThat(cleared.getEntries()).isEmpty();
        assertThat(cleared.getRewindPoint()).isEmpty();
        assertThat(cleared.getFloorSeq()).isEqualTo(2);
        assertThat(cleared.getNextSeq()).isEqualTo(2);
        assertThat(cleared.append(ASK, LogOrigin.CONVERSATION).getEntries().get(0).getSeq()).isEqualTo(2);
    }

    @Test
    void onlyALiveConversationUserMessageCountsAsConversation() {
        assertThat(SessionLogState.empty().append(REMINDER, LogOrigin.SYNTHETIC).hasConversation()).isFalse();
        assertThat(SessionLogState.empty().append(ANSWER, LogOrigin.CONVERSATION).hasConversation()).isFalse();
        assertThat(SessionLogState.empty().append(ASK, LogOrigin.CONVERSATION).hasConversation()).isTrue();
    }

    /**
     * A new session whose first turn was interrupted and rewound has handed out seqs but holds nothing. Judging by
     * {@code nextSeq > floorSeq} would treat it as resumed and deny the retry its user-context block.
     */
    @Test
    void aRewoundFirstTurnLeavesNoConversationEvenThoughSeqsWereUsed() {
        final SessionLogState rewound = SessionLogState.empty()
                .withRewindPoint(SessionRewindPoint.of(0, TextInput.of("x"))).append(REMINDER, LogOrigin.SYNTHETIC)
                .append(ASK, LogOrigin.CONVERSATION).rewind();

        assertThat(rewound.getNextSeq()).isEqualTo(2);
        assertThat(rewound.hasConversation()).isFalse();
    }

    @Test
    void theLiveEntryCountIgnoresSeqsCutByARewind() {
        final SessionLogState state = SessionLogState.ofMessages(List.of(ASK, ANSWER, ASK)).truncateFrom(1);

        assertThat(state.liveEntryCount()).isEqualTo(1);
        assertThat(state.getNextSeq() - state.getFloorSeq()).isEqualTo(3);
    }

    @Test
    void countBeforeMapsASeqToItsPositionAmongTheCarriedEntries() {
        final SessionLogState state = SessionLogState.builder()
                .entries(List.of(SessionLogEntry.of(3, ASK, LogOrigin.CONVERSATION),
                        SessionLogEntry.of(7, ANSWER, LogOrigin.CONVERSATION)))
                .floorSeq(3).nextSeq(9).build();

        assertThat(state.countBefore(3)).isZero();
        assertThat(state.countBefore(5)).isEqualTo(1);
        assertThat(state.countBefore(9)).isEqualTo(2);
    }

    @Test
    void theFormatCanOnlyBeRaised() {
        final SessionLogState v2 = SessionLogState.empty().withFormatAtLeast(SessionLogFormat.V2);

        assertThat(v2.getFormat()).isEqualTo(SessionLogFormat.V2);
        assertThat(v2.withFormatAtLeast(SessionLogFormat.V1)).isSameAs(v2);
        assertThat(v2.clear().getFormat()).isEqualTo(SessionLogFormat.V2);
        assertThat(v2).isNotEqualTo(SessionLogState.empty());
    }

    @Test
    void theMessageViewToleratesContainsNull() {
        assertThat(SessionLogState.ofMessages(List.of(ASK)).getMessages().contains(null)).isFalse();
    }

    @Test
    void legalCutsAnswersExactlyAsIsLegalCutDoes() {
        // seq 0 q, 1 call t1, 2 result t1, 3 a, 4 q, 5 call t2+t3, 6 result t2, 7 result t3, 8 a, 9 q, 10 call t4
        // (never answered); [0, 4) summarized and sealed, and a rewind point at 9.
        final SessionLogState log = SessionLogState
                .ofMessages(List.of(Message.user("q1"),
                        Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))),
                        Message.toolUseResults(List.of(ToolUseResult.success("t1", "r1"))), Message.assistant("a1"),
                        Message.user("q2"),
                        Message.assistant("",
                                List.of(ToolUse.of("t2", "Read", Map.of()), ToolUse.of("t3", "Read", Map.of()))),
                        Message.toolUseResults(List.of(ToolUseResult.success("t2", "r2"))),
                        Message.toolUseResults(List.of(ToolUseResult.success("t3", "r3"))), Message.assistant("a2"),
                        Message.user("q3"), Message.assistant("", List.of(ToolUse.of("t4", "Read", Map.of())))))
                .withFormatAtLeast(SessionLogFormat.V2).withRewindPoint(SessionRewindPoint.of(9, TextInput.of("q3")))
                .summarize(SummarySpan.builder().fromSeq(0).toSeq(4).summaryText("s").boundaryId("b").trigger("AUTO")
                        .build());
        final SessionLogState sealed = log.seal(SessionLogManifestEntry.builder().fromSeq(0).toSeq(4)
                .segmentId(SegmentId.generate()).contentHash("sha256:x").entryCount(4).build());

        for (SessionLogState state : List.of(log, sealed)) {
            final LongPredicate legal = state.legalCuts();
            for (long seq = -1; seq <= state.getNextSeq() + 1; seq++) {
                assertThat(legal.test(seq)).as("seq %d", seq).isEqualTo(state.isLegalCut(seq));
            }
        }
        assertThat(sealed.legalCuts().test(2)).as("inside a sealed range").isFalse();
        assertThat(sealed.legalCuts().test(4)).isTrue();
        assertThat(sealed.legalCuts().test(6)).as("splits t2/t3").isFalse();
        assertThat(sealed.legalCuts().test(11)).as("the end, with t4 unanswered").isFalse();
    }

    @Test
    void countBeforeFindsThePositionOfAnySeq() {
        final SessionLogState log = SessionLogState.ofMessages(List.of(ASK, ASK, ASK, ASK)).truncateFrom(2).append(ASK,
                LogOrigin.CONVERSATION);
        // entries at seqs 0, 1, 4

        assertThat(log.countBefore(0)).isZero();
        assertThat(log.countBefore(1)).isEqualTo(1);
        assertThat(log.countBefore(3)).isEqualTo(2);
        assertThat(log.countBefore(4)).isEqualTo(2);
        assertThat(log.countBefore(5)).isEqualTo(3);
        assertThat(log.countBefore(Long.MAX_VALUE)).isEqualTo(3);
    }
}
