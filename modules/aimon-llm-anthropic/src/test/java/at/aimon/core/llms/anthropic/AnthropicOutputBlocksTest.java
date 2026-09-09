package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ReasoningTrace;

/**
 * Pins the capture rule, which is the half of the round trip that differs from OpenAI's.
 *
 * <p>
 * These cases are the table in {@link AnthropicOutputBlocks}'s javadoc, one test each. The one that matters most is
 * {@code [thinking, text, tool_use]}: applying OpenAI's rule there — anchor to the first following tool call,
 * unconditionally — anchors the thinking block to the call, and the emit rule then replays the turn as
 * {@code [text, thinking, tool_use]}, which the provider rejects.
 */
@DisplayName("AnthropicOutputBlocks - the thinking-block anchor rule")
class AnthropicOutputBlocksTest {

    private static final String PROVIDER = "Anthropic";

    private static List<ReasoningTrace> resolve(AnthropicOutputBlocks.Block... blocks) {
        return AnthropicOutputBlocks.resolve(List.of(blocks), PROVIDER);
    }

    private static AnthropicOutputBlocks.Block think(String payload) {
        return AnthropicOutputBlocks.Block.thinking(payload);
    }

    @Test
    @DisplayName("an intervening text block leaves the thinking block unanchored")
    void textIntervenesSoTheThinkingBlockIsUnanchored() {
        final List<ReasoningTrace> traces = resolve(think("t1"), AnthropicOutputBlocks.Block.text(),
                AnthropicOutputBlocks.Block.toolUse("toolu_1"));

        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getToolUseId()).isEmpty();
        assertThat(traces.get(0).getPayload()).isEqualTo("t1");
        assertThat(traces.get(0).getProviderName()).isEqualTo(PROVIDER);
    }

    @Test
    @DisplayName("a thinking block immediately before a tool use anchors to it")
    void thinkingAnchorsToTheFollowingToolUse() {
        final List<ReasoningTrace> traces = resolve(think("t1"), AnthropicOutputBlocks.Block.toolUse("toolu_1"));

        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getToolUseId()).contains("toolu_1");
    }

    @Test
    @DisplayName("consecutive thinking blocks all anchor to the same tool use, in order")
    void consecutiveThinkingBlocksShareAnAnchor() {
        final List<ReasoningTrace> traces = resolve(think("t1"), think("t2"),
                AnthropicOutputBlocks.Block.toolUse("toolu_1"));

        assertThat(traces).extracting(ReasoningTrace::getPayload).containsExactly("t1", "t2");
        assertThat(traces).allSatisfy(trace -> assertThat(trace.getToolUseId()).contains("toolu_1"));
    }

    @Test
    @DisplayName("interleaved thinking anchors to its own tool use, not the first one")
    void interleavedThinkingAnchorsToItsOwnCall() {
        final List<ReasoningTrace> traces = resolve(think("t1"), AnthropicOutputBlocks.Block.toolUse("toolu_1"),
                think("t2"), AnthropicOutputBlocks.Block.toolUse("toolu_2"));

        assertThat(traces).extracting(ReasoningTrace::getPayload).containsExactly("t1", "t2");
        assertThat(traces.get(0).getToolUseId()).contains("toolu_1");
        assertThat(traces.get(1).getToolUseId()).contains("toolu_2");
    }

    @Test
    @DisplayName("the mixed shape: leading thinking unanchored, later thinking anchored")
    void mixedShapeKeepsBothKindsOfAnchor() {
        final List<ReasoningTrace> traces = resolve(think("t1"), AnthropicOutputBlocks.Block.text(),
                AnthropicOutputBlocks.Block.toolUse("toolu_1"), think("t2"),
                AnthropicOutputBlocks.Block.toolUse("toolu_2"));

        assertThat(traces).extracting(ReasoningTrace::getPayload).containsExactly("t1", "t2");
        assertThat(traces.get(0).getToolUseId()).isEmpty();
        assertThat(traces.get(1).getToolUseId()).contains("toolu_2");
    }

    @Test
    @DisplayName("thinking followed by text and no tool use is unanchored")
    void thinkingThenTextWithNoToolsIsUnanchored() {
        final List<ReasoningTrace> traces = resolve(think("t1"), AnthropicOutputBlocks.Block.text());

        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getToolUseId()).isEmpty();
    }

    @Test
    @DisplayName("a trailing thinking block with nothing after it is unanchored")
    void trailingThinkingIsUnanchored() {
        final List<ReasoningTrace> traces = resolve(think("t1"));

        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getToolUseId()).isEmpty();
    }

    @Test
    @DisplayName("no blocks yields no traces")
    void emptyInputYieldsNoTraces() {
        assertThat(AnthropicOutputBlocks.resolve(List.of(), PROVIDER)).isEmpty();
    }

    @Test
    @DisplayName("a turn with no thinking at all yields no traces")
    void textAndToolsWithoutThinkingYieldNothing() {
        assertThat(resolve(AnthropicOutputBlocks.Block.text(), AnthropicOutputBlocks.Block.toolUse("toolu_1")))
                .isEmpty();
    }

    @Test
    @DisplayName("the provider name is stamped from the argument, not from a constant")
    void providerNameComesFromTheArgument() {
        final List<ReasoningTrace> traces = AnthropicOutputBlocks.resolve(List.of(think("t1")), "MyAnthropic");

        assertThat(traces.get(0).getProviderName()).isEqualTo("MyAnthropic");
    }

    @Test
    @DisplayName("the result is immutable")
    void resultIsImmutable() {
        final List<ReasoningTrace> traces = resolve(think("t1"));

        assertThatThrownBy(() -> traces.add(ReasoningTrace.builder().providerName(PROVIDER).payload("x").build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null arguments are rejected")
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> AnthropicOutputBlocks.resolve(null, PROVIDER))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AnthropicOutputBlocks.resolve(List.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AnthropicOutputBlocks.Block.thinking(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AnthropicOutputBlocks.Block.toolUse(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("an unanchored trace reports its anchor as empty, not as a blank id")
    void unanchoredMeansEmptyOptional() {
        final Optional<String> anchor = resolve(think("t1")).get(0).getToolUseId();

        assertThat(anchor).isEmpty();
    }
}
