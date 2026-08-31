package at.aimon.core.llm.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;

class LlmStreamChunkTest {

    @Test
    void textDeltaCarriesIndexAndDelta() {
        LlmStreamChunk chunk = LlmStreamChunk.textDelta(3, "Hello");

        assertThat(chunk.getKind()).isEqualTo(LlmStreamChunk.Kind.TEXT_DELTA);
        assertThat(chunk.getIndex()).isEqualTo(3);
        assertThat(chunk.getTextDelta()).contains("Hello");
        assertThat(chunk.getTokenUsage()).isEmpty();
        assertThat(chunk.getFinishReason()).isEmpty();
        assertThat(chunk.getTimestamp()).isNotNull();
    }

    @Test
    void textDeltaRejectsNullDelta() {
        assertThatNullPointerException().isThrownBy(() -> LlmStreamChunk.textDelta(0, null));
    }

    @Test
    void textDeltaRejectsEmptyDelta() {
        assertThatIllegalArgumentException().isThrownBy(() -> LlmStreamChunk.textDelta(0, ""));
    }

    @Test
    void streamEndCarriesUsageAndFinishReason() {
        TokenUsage usage = TokenUsage.of(10, 5, 15);
        LlmStreamChunk chunk = LlmStreamChunk.streamEnd(7, usage, Optional.of("stop"));

        assertThat(chunk.getKind()).isEqualTo(LlmStreamChunk.Kind.STREAM_END);
        assertThat(chunk.getIndex()).isEqualTo(7);
        assertThat(chunk.getTextDelta()).isEmpty();
        assertThat(chunk.getTokenUsage()).contains(usage);
        assertThat(chunk.getFinishReason()).contains("stop");
    }

    @Test
    void streamEndAcceptsNullUsageAndEmptyFinishReason() {
        LlmStreamChunk chunk = LlmStreamChunk.streamEnd(0, null, Optional.empty());

        assertThat(chunk.getKind()).isEqualTo(LlmStreamChunk.Kind.STREAM_END);
        assertThat(chunk.getTokenUsage()).isEmpty();
        assertThat(chunk.getFinishReason()).isEmpty();
    }

    @Test
    void streamEndRejectsNullFinishReason() {
        assertThatNullPointerException().isThrownBy(() -> LlmStreamChunk.streamEnd(0, null, null));
    }

    @Test
    void builderRejectsNegativeIndex() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> LlmStreamChunk.builder().kind(LlmStreamChunk.Kind.TEXT_DELTA).index(-1).textDelta("x").build());
    }

    @Test
    void builderRejectsTextDeltaOnStreamEnd() {
        assertThatIllegalArgumentException().isThrownBy(() -> LlmStreamChunk.builder()
                .kind(LlmStreamChunk.Kind.STREAM_END).index(0).textDelta("not allowed").build());
    }

    @Test
    void builderRejectsMissingDeltaOnTextDeltaKind() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LlmStreamChunk.builder().kind(LlmStreamChunk.Kind.TEXT_DELTA).index(0).build());
    }

    @Test
    void builderRequiresKind() {
        assertThatThrownBy(() -> LlmStreamChunk.builder().index(0).textDelta("x").build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builderUsesProvidedTimestamp() {
        Instant fixed = Instant.parse("2024-01-01T00:00:00Z");
        LlmStreamChunk chunk = LlmStreamChunk.builder().kind(LlmStreamChunk.Kind.TEXT_DELTA).index(0).textDelta("x")
                .timestamp(fixed).build();

        assertThat(chunk.getTimestamp()).isEqualTo(fixed);
    }

    @Test
    void toStringContainsKindAndIndex() {
        LlmStreamChunk text = LlmStreamChunk.textDelta(2, "abc");
        LlmStreamChunk end = LlmStreamChunk.streamEnd(3, TokenUsage.of(1, 1, 2), Optional.of("length"));

        assertThat(text.toString()).contains("TEXT_DELTA").contains("index=2").contains("len=3");
        assertThat(end.toString()).contains("STREAM_END").contains("index=3").contains("length");
    }

    @Test
    void streamEndWithStopReasonPreservesStopReasonAndRawFinishReason() {
        TokenUsage usage = TokenUsage.of(10, 5, 15);
        LlmStreamChunk chunk = LlmStreamChunk.streamEnd(7, usage, Optional.of("max_tokens"), StopReason.MAX_TOKENS);

        assertThat(chunk.getStopReason()).contains(StopReason.MAX_TOKENS);
        assertThat(chunk.getFinishReason()).contains("max_tokens");
    }

    @Test
    void legacyStreamEndFactoryDefaultsStopReasonToEmpty() {
        LlmStreamChunk chunk = LlmStreamChunk.streamEnd(0, null, Optional.of("stop"));

        assertThat(chunk.getStopReason()).isEmpty();
    }

    @Test
    void getStopReasonCollapsesUnknownToEmpty() {
        LlmStreamChunk chunk = LlmStreamChunk.streamEnd(0, null, Optional.empty(), StopReason.UNKNOWN);

        assertThat(chunk.getStopReason()).isEmpty();
    }

    @Test
    void builderSetsStopReason() {
        LlmStreamChunk chunk = LlmStreamChunk.builder().kind(LlmStreamChunk.Kind.STREAM_END).index(0)
                .stopReason(StopReason.STOP_SEQUENCE).build();

        assertThat(chunk.getStopReason()).contains(StopReason.STOP_SEQUENCE);
    }

    // ---- TOOL_USE_READY (streaming-tool overlap) ------------------------

    @Test
    void toolUseReadyCarriesIndexAndToolUse() {
        ToolUse toolUse = ToolUse.of("tu_1", "Read", Map.of("file_path", "/a.txt"));
        LlmStreamChunk chunk = LlmStreamChunk.toolUseReady(2, toolUse);

        assertThat(chunk.getKind()).isEqualTo(LlmStreamChunk.Kind.TOOL_USE_READY);
        assertThat(chunk.getIndex()).isEqualTo(2);
        assertThat(chunk.getToolUse()).contains(toolUse);
        // A TOOL_USE_READY chunk carries neither text nor usage.
        assertThat(chunk.getTextDelta()).isEmpty();
        assertThat(chunk.getTokenUsage()).isEmpty();
        assertThat(chunk.getStopReason()).isEmpty();
    }

    @Test
    void toolUseReadyRejectsNullToolUse() {
        assertThatNullPointerException().isThrownBy(() -> LlmStreamChunk.toolUseReady(0, null));
    }

    @Test
    void getToolUseIsEmptyForOtherKinds() {
        assertThat(LlmStreamChunk.textDelta(0, "x").getToolUse()).isEmpty();
        assertThat(LlmStreamChunk.streamEnd(0, null, Optional.empty()).getToolUse()).isEmpty();
    }

    @Test
    void builderRejectsToolUseOnTextDelta() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LlmStreamChunk.builder().kind(LlmStreamChunk.Kind.TEXT_DELTA).index(0).textDelta("x")
                        .toolUse(ToolUse.of("id", "N", Map.of())).build());
    }

    @Test
    void builderRejectsTextDeltaOnToolUseReady() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LlmStreamChunk.builder().kind(LlmStreamChunk.Kind.TOOL_USE_READY).index(0)
                        .toolUse(ToolUse.of("id", "N", Map.of())).textDelta("x").build());
    }

    @Test
    void builderRejectsMissingToolUseOnToolUseReadyKind() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LlmStreamChunk.builder().kind(LlmStreamChunk.Kind.TOOL_USE_READY).index(0).build());
    }

    @Test
    void builderRejectsToolUseOnStreamEnd() {
        assertThatIllegalArgumentException().isThrownBy(() -> LlmStreamChunk.builder()
                .kind(LlmStreamChunk.Kind.STREAM_END).index(0).toolUse(ToolUse.of("id", "N", Map.of())).build());
    }

    @Test
    void toolUseReadyToStringContainsKindIndexToolNameAndId() {
        LlmStreamChunk chunk = LlmStreamChunk.toolUseReady(4, ToolUse.of("tu_9", "Grep", Map.of()));

        assertThat(chunk.toString()).contains("TOOL_USE_READY").contains("index=4").contains("Grep").contains("tu_9");
    }
}
