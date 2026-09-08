package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.cost.Money;

/**
 * The side-field half of {@link SessionRecordCodec}, plus the two absence encodings every backend leans on.
 *
 * <p>
 * The transcript half is deliberately barely touched: it delegates to {@code JsonSessionSnapshotCodec}, whose own test
 * already round-trips every message shape there is, and repeating that here would only re-test it through one more
 * call. What is tested here is what this class adds — the numeric encodings, and the fact that "nothing stored yet"
 * decodes to the same defaults an unprovisioned in-memory record reports.
 */
@DisplayName("SessionRecordCodec")
class SessionRecordCodecTest {

    private static final SessionId ID = SessionId.of("conv-1");

    @Test
    @DisplayName("totals survive the round trip, token counts included")
    void totalsRoundTrip() {
        final SessionTotals totals = SessionTotals.of(7, 23, TokenUsage.of(1_200, 340, 1_540));

        final SessionTotals decoded = SessionRecordCodec.decodeTotals(SessionRecordCodec.encodeTotals(totals));

        assertThat(decoded.getTurnCount()).isEqualTo(7);
        assertThat(decoded.getIterations()).isEqualTo(23);
        assertThat(decoded.getTokenUsage().getPromptTokens()).isEqualTo(1_200);
        assertThat(decoded.getTokenUsage().getCompletionTokens()).isEqualTo(340);
        assertThat(decoded.getTokenUsage().getTotalTokens()).isEqualTo(1_540);
    }

    @Test
    @DisplayName("a fully populated budget override survives, cost and duration included")
    void budgetOverrideRoundTrip() {
        final ExecutionBudget budget = ExecutionBudget.builder().maxIterations(12).maxTokens(50_000)
                .maxWallClockDuration(Duration.ofMinutes(3).plusNanos(500)).compactionTokenThreshold(30_000)
                .maxCostUsd(Money.of(new BigDecimal("1.2345678901"), "USD")).build();

        final ExecutionBudget decoded = SessionRecordCodec
                .decodeBudgetOverride(SessionRecordCodec.encodeBudgetOverride(budget));

        assertThat(decoded.getMaxIterations()).contains(12);
        assertThat(decoded.getMaxTokens()).contains(50_000);
        // Nanosecond precision, not milliseconds: the encoding is ISO-8601, so a sub-millisecond limit is not rounded
        // into a different limit on the way back.
        assertThat(decoded.getMaxWallClockDuration()).contains(Duration.ofMinutes(3).plusNanos(500));
        assertThat(decoded.getCompactionTokenThreshold()).contains(30_000);
        assertThat(decoded.getMaxCostUsd()).isPresent();
        assertThat(decoded.getMaxCostUsd().get().getAmount()).isEqualByComparingTo(new BigDecimal("1.2345678901"));
        assertThat(decoded.getMaxCostUsd().get().getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("unlimited is a recorded decision and stays distinct from no override at all")
    void unlimitedIsNotTheSameAsAbsent() {
        // The one that would be silently wrong: if unlimited() encoded to null, a session that had its limits lifted
        // would quietly get the opener's default budget back on the next open, on another node, with no error.
        final String encoded = SessionRecordCodec.encodeBudgetOverride(ExecutionBudget.unlimited());

        assertThat(encoded).isNotNull();
        assertThat(SessionRecordCodec.decodeBudgetOverride(encoded)).isNotNull();
        assertThat(SessionRecordCodec.encodeBudgetOverride(null)).isNull();
        assertThat(SessionRecordCodec.decodeBudgetOverride(null)).isNull();
    }

    @Test
    @DisplayName("a partial budget stays partial — an unset limit does not come back as zero")
    void unsetLimitsStayUnset() {
        final ExecutionBudget budget = ExecutionBudget.builder().maxIterations(5).build();

        final ExecutionBudget decoded = SessionRecordCodec
                .decodeBudgetOverride(SessionRecordCodec.encodeBudgetOverride(budget));

        assertThat(decoded.getMaxIterations()).contains(5);
        assertThat(decoded.getMaxTokens()).isEmpty();
        assertThat(decoded.getMaxWallClockDuration()).isEmpty();
        assertThat(decoded.getCompactionTokenThreshold()).isEmpty();
        assertThat(decoded.getMaxCostUsd()).isEmpty();
    }

    @Test
    @DisplayName("nothing stored decodes to the defaults a just-provisioned record reports")
    void absenceDecodesToDefaults() {
        // Backends hit this on every session's first turn: the claim path provisions the row before any turn has
        // written a transcript or totals into it, and the row's columns are genuinely null at that point.
        assertThat(SessionRecordCodec.decodeTotals(null)).isEqualTo(SessionTotals.empty());
        assertThat(SessionRecordCodec.decodeTotals("  ")).isEqualTo(SessionTotals.empty());
        assertThat(SessionRecordCodec.decodeTranscript(ID, null)).isEqualTo(SessionSnapshot.of(ID));
        assertThat(SessionRecordCodec.decodeTranscript(ID, "")).isEqualTo(SessionSnapshot.of(ID));
    }

    @Test
    @DisplayName("the transcript half reaches the shared codec")
    void transcriptDelegates() {
        final SessionSnapshot snapshot = SessionSnapshot.of(ID, "You are a helper.",
                List.of(Message.user("hi"), Message.assistant("hello")));

        final String encoded = SessionRecordCodec.encodeTranscript(ID, snapshot.getSystemPrompt(),
                snapshot.getConversationHistory());

        assertThat(SessionRecordCodec.decodeTranscript(ID, encoded)).isEqualTo(snapshot);
        assertThat(SessionRecordCodec.encodeTranscript(snapshot)).isEqualTo(encoded);
        assertThat(SessionRecordCodec
                .encodeTranscript(StoredSessionRecord.builder(ID).transcript(snapshot).agentRef("a").build()))
                .isEqualTo(encoded);
    }

    @Test
    @DisplayName("a stored record reports view defaults for what a backend did not fill in")
    void storedRecordDefaults() {
        final StoredSessionRecord record = StoredSessionRecord.empty(ID, null);

        assertThat(record.getId()).isEqualTo(ID);
        assertThat(record.getSystemPrompt()).isNull();
        assertThat(record.getMessages()).isEmpty();
        assertThat(record.getAgentRef()).isEmpty();
        assertThat(record.getCompactionFailureCount()).isZero();
        assertThat(record.getSessionTotals()).isEqualTo(SessionTotals.empty());
        assertThat(record.getBudgetOverride()).isEmpty();
    }

    @Test
    @DisplayName("reasoning tokens survive the totals round trip")
    void reasoningTokensRoundTrip() {
        final SessionTotals totals = SessionTotals.of(7, 23, TokenUsage.of(1_200, 340, 1_540, 210));

        final SessionTotals decoded = SessionRecordCodec.decodeTotals(SessionRecordCodec.encodeTotals(totals));

        assertThat(decoded).isEqualTo(totals);
        assertThat(decoded.getTokenUsage().getReasoningTokens()).isEqualTo(210);
    }

    @Test
    @DisplayName("a totals document written before reasoning tokens existed decodes them as zero")
    void totalsWithoutReasoningTokensDecodeToZero() {
        // Hand-written in the shape a build before this field produced. node.path(...).asInt() is 0 on absence, which
        // is why the decode needs no special case -- and this is the test that says so rather than leaving it to be
        // rediscovered.
        final String oldFormat = "{\"version\":1,\"turnCount\":2,\"iterations\":5,\"promptTokens\":100,"
                + "\"completionTokens\":50,\"totalTokens\":150}";

        final SessionTotals decoded = SessionRecordCodec.decodeTotals(oldFormat);

        assertThat(decoded).isEqualTo(SessionTotals.of(2, 5, TokenUsage.of(100, 50, 150)));
        assertThat(decoded.getTokenUsage().getReasoningTokens()).isZero();
    }

    @Test
    @DisplayName("a transcript carrying a reasoning trace survives the encode/decode this codec wraps")
    void transcriptCarriesReasoningTraces() {
        // The backends store this string in a text column and never look inside it, so this is where the encoding is
        // actually decided. What a backend can still break -- non-ASCII and NUL inside the string -- is covered by
        // their own integration tests.
        final ReasoningTrace trace = ReasoningTrace.builder().providerName("OpenAI")
                .payload("{\"type\":\"reasoning\",\"encrypted_content\":\"ZZZ\"}").toolUseId("call_1").build();
        final SessionSnapshot snapshot = SessionSnapshot.of(ID, "sys",
                List.of(Message.assistant("thinking", List.of(ToolUse.of("call_1", "Bash", java.util.Map.of())))
                        .withReasoningTraces(List.of(trace))));

        final SessionSnapshot decoded = SessionRecordCodec.decodeTranscript(ID,
                SessionRecordCodec.encodeTranscript(snapshot));

        assertThat(decoded).isEqualTo(snapshot);
        assertThat(decoded.getConversationHistory().get(0).getReasoningTraces()).containsExactly(trace);
    }

}
