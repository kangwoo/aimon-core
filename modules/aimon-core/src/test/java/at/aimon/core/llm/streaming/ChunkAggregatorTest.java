package at.aimon.core.llm.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;

class ChunkAggregatorTest {

    @Test
    void textDeltasAccumulateIntoPeekText() {
        ChunkAggregator agg = new ChunkAggregator();

        agg.accept(LlmStreamChunk.textDelta(0, "Hel"));
        agg.accept(LlmStreamChunk.textDelta(1, "lo "));
        agg.accept(LlmStreamChunk.textDelta(2, "world"));

        assertThat(agg.peekText()).isEqualTo("Hello world");
        assertThat(agg.chunksAccepted()).isEqualTo(3);
        assertThat(agg.isClosed()).isFalse();
        assertThat(agg.finishReason()).isEmpty();
    }

    @Test
    void streamEndCapturesUsageAndFinishReasonAndCloses() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.accept(LlmStreamChunk.textDelta(0, "hi"));
        agg.accept(LlmStreamChunk.streamEnd(1, TokenUsage.of(3, 2, 5), Optional.of("stop")));

        assertThat(agg.isClosed()).isTrue();
        assertThat(agg.finishReason()).contains("stop");
        assertThat(agg.chunksAccepted()).isEqualTo(2);
    }

    @Test
    void acceptRejectsNullChunk() {
        ChunkAggregator agg = new ChunkAggregator();
        assertThatNullPointerException().isThrownBy(() -> agg.accept(null));
    }

    @Test
    void acceptAfterCloseThrows() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.accept(LlmStreamChunk.streamEnd(0, null, Optional.empty()));

        assertThatIllegalStateException().isThrownBy(() -> agg.accept(LlmStreamChunk.textDelta(1, "after end")));
    }

    @Test
    void appendToolCallDeltaRejectsNegativeIndex() {
        ChunkAggregator agg = new ChunkAggregator();
        assertThatIllegalArgumentException().isThrownBy(() -> agg.appendToolCallDelta(-1, "id", "name", "{}"));
    }

    @Test
    void appendToolCallDeltaAfterCloseThrows() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.accept(LlmStreamChunk.streamEnd(0, null, Optional.empty()));

        assertThatIllegalStateException().isThrownBy(() -> agg.appendToolCallDelta(0, "call_1", "tool", "{\"a\":1}"));
    }

    @Test
    void toLlmResponseBeforeCloseThrows() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.accept(LlmStreamChunk.textDelta(0, "incomplete"));

        assertThatIllegalStateException().isThrownBy(agg::toLlmResponse);
    }

    @Test
    void toLlmResponseEmitsAccumulatedTextAndUsage() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.accept(LlmStreamChunk.textDelta(0, "ok"));
        agg.accept(LlmStreamChunk.streamEnd(1, TokenUsage.of(1, 2, 3), Optional.of("stop")));

        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getTextContent()).isEqualTo("ok");
        assertThat(resp.getToolUses()).isEmpty();
        assertThat(resp.getTokenUsage().getTotalTokens()).isEqualTo(3);
    }

    @Test
    void toLlmResponseDefaultsToEmptyUsageWhenNotProvided() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.accept(LlmStreamChunk.streamEnd(0, null, Optional.empty()));

        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getTokenUsage().getTotalTokens()).isZero();
    }

    @Test
    void toolCallFragmentsAreCoalescedAcrossCallsAndIdNamePreservedAcrossContinuations() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.appendToolCallDelta(0, "call_1", "search", "{\"qu");
        agg.appendToolCallDelta(0, null, null, "ery\":\"hello\"}");
        agg.accept(LlmStreamChunk.streamEnd(0, null, Optional.empty()));

        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getToolUses()).hasSize(1);
        ToolUse use = resp.getToolUses().get(0);
        assertThat(use.getId()).isEqualTo("call_1");
        assertThat(use.getName()).isEqualTo("search");
        assertThat(use.getInput()).containsEntry("query", "hello");
    }

    @Test
    void toolCallFragmentsTreatNullOrEmptyArgumentsFragmentAsNoop() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.appendToolCallDelta(0, "call_1", "search", null);
        agg.appendToolCallDelta(0, null, null, "");
        agg.appendToolCallDelta(0, null, null, "{\"k\":1}");
        agg.accept(LlmStreamChunk.streamEnd(0, null, Optional.empty()));

        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getToolUses()).hasSize(1);
        assertThat(resp.getToolUses().get(0).getInput()).containsEntry("k", 1);
    }

    @Test
    void multipleToolCallSlotsArePreservedInInsertionOrder() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.appendToolCallDelta(0, "call_1", "first", "{}");
        agg.appendToolCallDelta(1, "call_2", "second", "{}");
        agg.accept(LlmStreamChunk.streamEnd(0, null, Optional.empty()));

        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getToolUses()).extracting(ToolUse::getName).containsExactly("first", "second");
    }

    @Test
    void toolCallSlotWithoutNameIsSkippedWithWarning() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.appendToolCallDelta(0, "call_1", null, "{}");
        agg.appendToolCallDelta(1, "call_2", "ok", "{}");
        agg.accept(LlmStreamChunk.streamEnd(0, null, Optional.empty()));

        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getToolUses()).hasSize(1);
        assertThat(resp.getToolUses().get(0).getName()).isEqualTo("ok");
    }

    @Test
    void toolCallSlotWithoutIdIsSkipped() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.appendToolCallDelta(0, null, "noid", "{}");
        agg.accept(LlmStreamChunk.streamEnd(0, null, Optional.empty()));

        assertThat(agg.toLlmResponse().getToolUses()).isEmpty();
    }

    @Test
    void malformedJsonArgumentsResultInEmptyInputMap() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.appendToolCallDelta(0, "call_1", "broken", "{ not valid json");
        agg.accept(LlmStreamChunk.streamEnd(0, null, Optional.empty()));

        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getToolUses()).hasSize(1);
        assertThat(resp.getToolUses().get(0).getInput()).isEmpty();
    }

    @Test
    void emptyArgumentsBufferProducesEmptyInputMap() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.appendToolCallDelta(0, "call_1", "noargs", null);
        agg.accept(LlmStreamChunk.streamEnd(0, null, Optional.empty()));

        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getToolUses()).hasSize(1);
        assertThat(resp.getToolUses().get(0).getInput()).isEmpty();
    }

    @Test
    void toLlmResponseCarriesMaxTokensStopReasonFromStreamEnd() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.accept(LlmStreamChunk.textDelta(0, "ok"));
        agg.accept(LlmStreamChunk.streamEnd(1, TokenUsage.of(1, 2, 3), Optional.of("length"), StopReason.MAX_TOKENS));

        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getStopReason()).contains(StopReason.MAX_TOKENS);
    }

    @Test
    void toLlmResponseCarriesEndTurnStopReasonFromStreamEnd() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.accept(LlmStreamChunk.textDelta(0, "ok"));
        agg.accept(LlmStreamChunk.streamEnd(1, TokenUsage.of(1, 2, 3), Optional.of("stop"), StopReason.END_TURN));

        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getStopReason()).contains(StopReason.END_TURN);
    }

    @Test
    void toLlmResponseHasEmptyStopReasonWhenLegacyStreamEndFactoryUsed() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.accept(LlmStreamChunk.textDelta(0, "ok"));
        agg.accept(LlmStreamChunk.streamEnd(1, TokenUsage.of(1, 2, 3), Optional.of("stop")));

        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getStopReason()).isEmpty();
    }

    // ---- finalizeToolCall / TOOL_USE_READY (streaming-tool overlap) -----

    @Test
    void finalizeToolCallReturnsParsedToolUseForCompletedSlot() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.appendToolCallDelta(0, "call_1", "search", "{\"query\":\"hello\"}");

        Optional<ToolUse> finalized = agg.finalizeToolCall(0);

        assertThat(finalized).isPresent();
        assertThat(finalized.get().getId()).isEqualTo("call_1");
        assertThat(finalized.get().getName()).isEqualTo("search");
        assertThat(finalized.get().getInput()).containsEntry("query", "hello");
    }

    @Test
    void finalizeToolCallIsNonMutating() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.appendToolCallDelta(0, "call_1", "search", "{\"query\":\"hello\"}");

        // Finalizing early must not close the aggregator nor consume the slot: toLlmResponse() re-parses the same slot.
        agg.finalizeToolCall(0);
        assertThat(agg.isClosed()).isFalse();

        agg.accept(LlmStreamChunk.streamEnd(1, null, Optional.empty()));
        LlmResponse resp = agg.toLlmResponse();
        assertThat(resp.getToolUses()).hasSize(1);
        assertThat(resp.getToolUses().get(0).getId()).isEqualTo("call_1");
        assertThat(resp.getToolUses().get(0).getInput()).containsEntry("query", "hello");
    }

    @Test
    void finalizeToolCallEmptyForUnknownSlot() {
        ChunkAggregator agg = new ChunkAggregator();
        assertThat(agg.finalizeToolCall(0)).isEmpty();
    }

    @Test
    void finalizeToolCallEmptyWhenIdOrNameMissing() {
        ChunkAggregator agg = new ChunkAggregator();
        // Same skip condition as toLlmResponse(): a slot missing id or name yields no ToolUse.
        agg.appendToolCallDelta(0, null, "noid", "{}");
        agg.appendToolCallDelta(1, "call_2", null, "{}");

        assertThat(agg.finalizeToolCall(0)).isEmpty();
        assertThat(agg.finalizeToolCall(1)).isEmpty();
    }

    @Test
    void finalizeToolCallReflectsOnlyFragmentsSeenSoFar() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.appendToolCallDelta(0, "call_1", "search", "{\"q\":");
        // Mid-stream: arguments are not yet valid JSON, so parsing yields an empty input map (never throws).
        assertThat(agg.finalizeToolCall(0)).isPresent();
        assertThat(agg.finalizeToolCall(0).get().getInput()).isEmpty();

        // Once the rest arrives, finalizing yields the full arguments.
        agg.appendToolCallDelta(0, null, null, "\"done\"}");
        assertThat(agg.finalizeToolCall(0).get().getInput()).containsEntry("q", "done");
    }

    @Test
    void acceptToolUseReadyIsANoopThatDoesNotCloseOrAlterText() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.accept(LlmStreamChunk.textDelta(0, "hi"));
        agg.appendToolCallDelta(1, "call_1", "search", "{\"query\":\"x\"}");

        // The executor-side aggregator receives TOOL_USE_READY too; it must be a pure no-op (design §10.7).
        agg.accept(LlmStreamChunk.toolUseReady(1, ToolUse.of("call_1", "search", java.util.Map.of("query", "x"))));

        assertThat(agg.isClosed()).isFalse();
        assertThat(agg.peekText()).isEqualTo("hi");
        assertThat(agg.chunksAccepted()).isEqualTo(2);

        // The tool is still built once, authoritatively, at stream end — no duplication from the early signal.
        agg.accept(LlmStreamChunk.streamEnd(2, null, Optional.empty()));
        assertThat(agg.toLlmResponse().getToolUses()).hasSize(1);
    }

    @Test
    void acceptToolUseReadyAfterCloseThrows() {
        ChunkAggregator agg = new ChunkAggregator();
        agg.accept(LlmStreamChunk.streamEnd(0, null, Optional.empty()));

        assertThatIllegalStateException().isThrownBy(
                () -> agg.accept(LlmStreamChunk.toolUseReady(1, ToolUse.of("call_1", "search", java.util.Map.of()))));
    }
}
