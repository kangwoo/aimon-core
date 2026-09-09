package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.anthropic.models.messages.RawMessageStreamEvent;

import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * Drives {@link AnthropicStreamingMapper} over fixture event streams and asserts the traces that come out.
 *
 * <p>
 * The streaming path is where the OpenAI round trip was nearly lost, because it is a second implementation of an
 * ordering rule that has to agree with the first. Here both paths call {@link AnthropicOutputBlocks}, so what is
 * tested here is the part that genuinely differs: reassembling a block from its deltas, and doing it before the
 * aggregator closes.
 */
@DisplayName("AnthropicStreamingMapper - thinking and signature deltas become traces")
class AnthropicStreamingReasoningTest {

    private static final String PROVIDER = "Anthropic";

    private static final String SIGNATURE = "EqMBCkYICxIMabc/+DEF==";

    private final List<String> warnings = new ArrayList<>();

    private final AnthropicDivergenceReporter reporter = (signature, message, args) -> warnings.add(signature);

    // ── fixture events ──────────────────────────────────────────────────────

    private static RawMessageStreamEvent messageStart() {
        return AnthropicFixtures.event("""
                {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",
                 "model":"claude-sonnet-4-5","content":[],"usage":{"input_tokens":11,"output_tokens":0}}}""");
    }

    private static RawMessageStreamEvent thinkingStart(int index) {
        return AnthropicFixtures.event("""
                {"type":"content_block_start","index":%d,
                 "content_block":{"type":"thinking","thinking":"","signature":""}}""".formatted(index));
    }

    /** A thinking block whose start event carries a field this SDK version does not model. */
    private static RawMessageStreamEvent thinkingStartWithUnknownField(int index) {
        return AnthropicFixtures.event("""
                {"type":"content_block_start","index":%d,
                 "content_block":{"type":"thinking","thinking":"","signature":"",
                                  "future_field":{"nested":[1,2]}}}""".formatted(index));
    }

    /** A thinking block whose start event already carries some thinking text. */
    private static RawMessageStreamEvent thinkingStartWithText(int index, String text) {
        return AnthropicFixtures.event("""
                {"type":"content_block_start","index":%d,
                 "content_block":{"type":"thinking","thinking":"%s","signature":""}}""".formatted(index, text));
    }

    private static RawMessageStreamEvent redactedStart(int index, String data) {
        return AnthropicFixtures.event("""
                {"type":"content_block_start","index":%d,
                 "content_block":{"type":"redacted_thinking","data":"%s"}}""".formatted(index, data));
    }

    private static RawMessageStreamEvent textStart(int index) {
        return AnthropicFixtures.event("""
                {"type":"content_block_start","index":%d,"content_block":{"type":"text","text":""}}"""
                .formatted(index));
    }

    private static RawMessageStreamEvent toolUseStart(int index, String id) {
        return AnthropicFixtures.event("""
                {"type":"content_block_start","index":%d,
                 "content_block":{"type":"tool_use","id":"%s","name":"Ls","input":{}}}""".formatted(index, id));
    }

    private static RawMessageStreamEvent thinkingDelta(int index, String text) {
        return AnthropicFixtures.event("""
                {"type":"content_block_delta","index":%d,"delta":{"type":"thinking_delta","thinking":"%s"}}"""
                .formatted(index, text));
    }

    private static RawMessageStreamEvent signatureDelta(int index, String signature) {
        return AnthropicFixtures.event("""
                {"type":"content_block_delta","index":%d,"delta":{"type":"signature_delta","signature":"%s"}}"""
                .formatted(index, signature));
    }

    private static RawMessageStreamEvent textDelta(int index, String text) {
        return AnthropicFixtures.event("""
                {"type":"content_block_delta","index":%d,"delta":{"type":"text_delta","text":"%s"}}""".formatted(index,
                text));
    }

    private static RawMessageStreamEvent inputJsonDelta(int index, String json) {
        return AnthropicFixtures.event("""
                {"type":"content_block_delta","index":%d,"delta":{"type":"input_json_delta","partial_json":"%s"}}"""
                .formatted(index, json));
    }

    private static RawMessageStreamEvent blockStop(int index) {
        return AnthropicFixtures.event("""
                {"type":"content_block_stop","index":%d}""".formatted(index));
    }

    private static RawMessageStreamEvent messageDelta() {
        return AnthropicFixtures.event("""
                {"type":"message_delta","delta":{"stop_reason":"tool_use"},
                 "usage":{"output_tokens":42,"output_tokens_details":{"thinking_tokens":30}}}""");
    }

    private static RawMessageStreamEvent messageStop() {
        return AnthropicFixtures.event("""
                {"type":"message_stop"}""");
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private LlmResponse consume(RawMessageStreamEvent... events) {
        final ChunkAggregator aggregator = new ChunkAggregator();
        final AnthropicStreamingMapper mapper = new AnthropicStreamingMapper(LlmStreamSink.discarding(), aggregator,
                PROVIDER, reporter);
        mapper.consume(List.of(events).stream());
        return aggregator.toLlmResponse();
    }

    // ── cases ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("thinking deltas plus a signature delta yield one trace anchored to the following tool use")
    void thinkingBlockIsReassembledAndAnchored() {
        final LlmResponse response = consume(messageStart(), thinkingStart(0), thinkingDelta(0, "check "),
                thinkingDelta(0, "the path"), signatureDelta(0, SIGNATURE), blockStop(0), toolUseStart(1, "toolu_1"),
                inputJsonDelta(1, "{}"), blockStop(1), messageDelta(), messageStop());

        assertThat(response.getReasoningTraces()).hasSize(1);
        final ReasoningTrace trace = response.getReasoningTraces().get(0);
        assertThat(trace.getToolUseId()).contains("toolu_1");
        assertThat(trace.getProviderName()).isEqualTo(PROVIDER);

        // Both delta kinds land in the payload, and the signature is what the block carries verbatim.
        final var payload = AnthropicFixtures.treeOf(trace.getPayload());
        assertThat(payload.get("thinking").asText()).isEqualTo("check the path");
        assertThat(payload.get("signature").asText()).isEqualTo(SIGNATURE);
        assertThat(payload.get("type").asText()).isEqualTo("thinking");
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("a block with no thinking delta still yields a trace — the display:omitted case")
    void signatureWithoutThinkingTextStillYieldsATrace() {
        // On newer models thinking text is omitted by default, so a legitimate block can stream zero thinking deltas.
        // The signature is the load-bearing half; dropping this block would silently disable the feature there.
        final LlmResponse response = consume(messageStart(), thinkingStart(0), signatureDelta(0, SIGNATURE),
                blockStop(0), toolUseStart(1, "toolu_1"), blockStop(1), messageStop());

        assertThat(response.getReasoningTraces()).hasSize(1);
        assertThat(AnthropicFixtures.treeOf(response.getReasoningTraces().get(0).getPayload()).get("thinking").asText())
                .isEmpty();
    }

    @Test
    @DisplayName("a block with no signature delta yields no trace, and says so once")
    void unsignedBlockIsDroppedLoudly() {
        final LlmResponse response = consume(messageStart(), thinkingStart(0), thinkingDelta(0, "half a thought"),
                blockStop(0), messageStop());

        // Replaying an unsigned block is a guaranteed rejection; dropping it costs only re-derived reasoning.
        assertThat(response.getReasoningTraces()).isEmpty();
        assertThat(warnings).containsExactly("unsignedThinkingBlock@" + PROVIDER);
    }

    @Test
    @DisplayName("a redacted_thinking block arrives whole on content_block_start and needs no deltas")
    void redactedBlockYieldsATrace() {
        final LlmResponse response = consume(messageStart(), redactedStart(0, "EvgBCkYIARgC"), blockStop(0),
                toolUseStart(1, "toolu_1"), blockStop(1), messageStop());

        assertThat(response.getReasoningTraces()).hasSize(1);
        final var payload = AnthropicFixtures.treeOf(response.getReasoningTraces().get(0).getPayload());
        assertThat(payload.get("type").asText()).isEqualTo("redacted_thinking");
        assertThat(payload.get("data").asText()).isEqualTo("EvgBCkYIARgC");
        assertThat(response.getReasoningTraces().get(0).getToolUseId()).contains("toolu_1");
    }

    @Test
    @DisplayName("an intervening text block leaves the thinking block unanchored, as on the blocking path")
    void streamingAppliesTheSameAnchorRule() {
        final LlmResponse response = consume(messageStart(), thinkingStart(0), signatureDelta(0, SIGNATURE),
                blockStop(0), textStart(1), textDelta(1, "listing it"), blockStop(1), toolUseStart(2, "toolu_1"),
                blockStop(2), messageDelta(), messageStop());

        assertThat(response.getReasoningTraces()).hasSize(1);
        assertThat(response.getReasoningTraces().get(0).getToolUseId()).isEmpty();
    }

    @Test
    @DisplayName("traces are flushed before STREAM_END closes the aggregator")
    void tracesAreFlushedBeforeTheTerminalChunk() {
        // Flushing after the terminal chunk would throw IllegalStateException out of the mapper, from a place the
        // client's try-with-resources does not cover. The assertion is that nothing escapes and the traces arrive.
        final ChunkAggregator aggregator = new ChunkAggregator();
        final AnthropicStreamingMapper mapper = new AnthropicStreamingMapper(LlmStreamSink.discarding(), aggregator,
                PROVIDER, reporter);

        assertThatCode(
                () -> mapper
                        .consume(List
                                .of(messageStart(), thinkingStart(0), signatureDelta(0, SIGNATURE), blockStop(0),
                                        toolUseStart(1, "toolu_1"), blockStop(1), messageDelta(), messageStop())
                                .stream()))
                .doesNotThrowAnyException();

        assertThat(aggregator.isClosed()).isTrue();
        assertThat(aggregator.toLlmResponse().getReasoningTraces()).hasSize(1);
    }

    @Test
    @DisplayName("a stream that ends without message_stop still flushes its traces")
    void earlyCloseStillFlushes() {
        final LlmResponse response = consume(messageStart(), thinkingStart(0), signatureDelta(0, SIGNATURE),
                blockStop(0), toolUseStart(1, "toolu_1"), blockStop(1));

        assertThat(response.getReasoningTraces()).hasSize(1);
    }

    @Test
    @DisplayName("thinking text is not forwarded to the sink")
    void thinkingTextIsNotEmittedToTheSink() {
        final List<LlmStreamChunk> chunks = new ArrayList<>();
        final ChunkAggregator aggregator = new ChunkAggregator();
        new AnthropicStreamingMapper(chunks::add, aggregator, PROVIDER, reporter).consume(List.of(messageStart(),
                thinkingStart(0), thinkingDelta(0, "private reasoning"), signatureDelta(0, SIGNATURE), blockStop(0),
                textStart(1), textDelta(1, "visible"), blockStop(1), messageStop()).stream());

        // Carrying the block across turns and rendering it to a user are different features; the second would need a
        // chunk kind aimon-core does not have.
        assertThat(chunks)
                .noneMatch(chunk -> String.valueOf(chunk.getTextDelta().orElse("")).contains("private reasoning"));
        assertThat(aggregator.toLlmResponse().getTextContent()).isEqualTo("visible");
    }

    @Test
    @DisplayName("a stream with no thinking at all behaves exactly as before")
    void noThinkingMeansNoTraces() {
        final LlmResponse response = consume(messageStart(), textStart(0), textDelta(0, "hello"), blockStop(0),
                messageDelta(), messageStop());

        assertThat(response.getReasoningTraces()).isEmpty();
        assertThat(response.getTextContent()).isEqualTo("hello");
    }

    @Test
    @DisplayName("a field this SDK version does not model survives the streamed reassembly")
    void unmodelledFieldOnAStreamedBlockSurvives() {
        // This is the property that seeding the slot from the content_block_start block exists to buy, and it is the
        // streaming counterpart of AnthropicReasoningTracesTest.additionalPropertiesSurvive. Rebuilding the block
        // from the two streamed fields alone would pass every other case in this class and lose exactly this.
        final LlmResponse response = consume(messageStart(), thinkingStartWithUnknownField(0),
                thinkingDelta(0, "reasoning"), signatureDelta(0, SIGNATURE), blockStop(0), toolUseStart(1, "toolu_1"),
                blockStop(1), messageStop());

        assertThat(response.getReasoningTraces()).hasSize(1);
        assertThat(AnthropicFixtures.treeOf(response.getReasoningTraces().get(0).getPayload()))
                .isEqualTo(AnthropicFixtures.treeOf("""
                        {"type":"thinking","thinking":"reasoning","signature":"%s",\
                        "future_field":{"nested":[1,2]}}""".formatted(SIGNATURE)));
    }

    @Test
    @DisplayName("thinking text on the start block is appended to, not overwritten by, the deltas")
    void startBlockThinkingTextIsFilledInRatherThanReplaced() {
        // Inert against today's protocol — Anthropic sends "thinking":"" on content_block_start — but it is what the
        // slot's own comment claims, so it is pinned rather than assumed.
        final LlmResponse response = consume(messageStart(), thinkingStartWithText(0, "seeded "),
                thinkingDelta(0, "and streamed"), signatureDelta(0, SIGNATURE), blockStop(0), messageStop());

        assertThat(AnthropicFixtures.treeOf(response.getReasoningTraces().get(0).getPayload()).get("thinking").asText())
                .isEqualTo("seeded and streamed");
    }

    @Test
    @DisplayName("the provider name is the one the client resolved, not a constant")
    void providerNameComesFromTheClient() {
        final ChunkAggregator aggregator = new ChunkAggregator();
        new AnthropicStreamingMapper(LlmStreamSink.discarding(), aggregator, "MyAnthropic", reporter).consume(
                List.of(messageStart(), thinkingStart(0), signatureDelta(0, SIGNATURE), blockStop(0), messageStop())
                        .stream());

        assertThat(aggregator.toLlmResponse().getReasoningTraces().get(0).getProviderName()).isEqualTo("MyAnthropic");
    }
}
