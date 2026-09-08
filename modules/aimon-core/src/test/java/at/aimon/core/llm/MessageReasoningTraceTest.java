package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

/**
 * Tests {@link Message}'s eighth field.
 *
 * <p>
 * The {@code mapText} case is the load-bearing one and it asserts the opposite of what the rest of that method does:
 * a reasoning payload must come out <em>byte-identical</em> while every other text fragment is rewritten. That is not
 * an oversight being pinned — it is the only behaviour that lets a provider replay the payload at all, since OpenAI's
 * {@code encrypted_content} is ciphertext and Anthropic's {@code signature} is a signature.
 */
@DisplayName("Message - reasoning traces")
class MessageReasoningTraceTest {

    private static final ReasoningTrace TRACE = ReasoningTrace.builder().providerName("OpenAI")
            .payload("{\"type\":\"reasoning\",\"encrypted_content\":\"SECRET\"}").toolUseId("call_1").build();

    @Test
    @DisplayName("a message carries no traces unless somebody attached them")
    void defaultsToEmpty() {
        final Message message = Message.assistant("hi");

        assertThat(message.getReasoningTraces()).isEmpty();
        assertThat(message.hasReasoningTraces()).isFalse();
    }

    @Test
    @DisplayName("withReasoningTraces returns a new message and preserves every other field")
    void witherPreservesEverythingElse() {
        final MessageArtifact artifact = MessageArtifact.builder().path("/out/report.csv").fileName("report.csv")
                .size(12).toolUseId("call_1").build();
        final Message original = Message.assistant("thinking",
                List.of(ToolUse.of("call_1", "Bash", Map.of("command", "ls"))), List.of(artifact));

        final Message withTraces = original.withReasoningTraces(List.of(TRACE));

        assertThat(withTraces).isNotSameAs(original);
        assertThat(original.hasReasoningTraces()).isFalse();
        assertThat(withTraces.getReasoningTraces()).containsExactly(TRACE);
        assertThat(withTraces.getRole()).isEqualTo(Role.ASSISTANT);
        assertThat(withTraces.getContent()).isEqualTo("thinking");
        assertThat(withTraces.getToolUses()).isEqualTo(original.getToolUses());
        assertThat(withTraces.getArtifacts()).isEqualTo(original.getArtifacts());
    }

    @Test
    @DisplayName("traces participate in equals, hashCode and toString")
    void tracesAreValueState() {
        final Message without = Message.assistant("hi");
        final Message with = without.withReasoningTraces(List.of(TRACE));

        assertThat(with).isNotEqualTo(without);
        assertThat(with).isEqualTo(Message.assistant("hi").withReasoningTraces(List.of(TRACE)));
        assertThat(with).hasSameHashCodeAs(Message.assistant("hi").withReasoningTraces(List.of(TRACE)));
        // A count, in the artifacts=N idiom -- never the payload text.
        assertThat(with.toString()).contains("reasoningTraces=1").doesNotContain("SECRET");
    }

    @Test
    @DisplayName("the five-argument restore still compiles and yields no traces")
    void fiveArgRestoreStillWorks() {
        final Message restored = Message.restore(Role.ASSISTANT, List.of(TextContentBlock.of("hi")), List.of(),
                List.of(), List.of());

        assertThat(restored.getReasoningTraces()).isEmpty();
        assertThat(restored).isEqualTo(Message.assistant("hi"));
    }

    @Test
    @DisplayName("the six-argument restore rebuilds a message equal to the one it was decomposed from")
    void sixArgRestoreRoundTrips() {
        final Message original = Message.assistant("hi", List.of(ToolUse.of("call_1", "Bash", Map.of())))
                .withReasoningTraces(List.of(TRACE));

        final Message restored = Message.restore(original.getRole(), original.getContentBlocks(),
                original.getToolUses(), original.getToolUseResults(), original.getArtifacts(),
                original.getReasoningTraces());

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("mapText rewrites every text fragment and leaves the reasoning payload byte-identical")
    void mapTextDoesNotReachThePayload() {
        final List<ContentBlock> blocks = List.of(TextContentBlock.of("SECRET in the text"),
                ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png"));
        final Message message = Message.restore(Role.ASSISTANT, blocks,
                List.of(ToolUse.of("call_1", "Bash", Map.of("command", "echo SECRET"))), List.of(), List.of(),
                List.of(TRACE));

        final Message redacted = message.mapText(text -> text.replace("SECRET", "[REDACTED]"));

        assertThat(redacted.getContent()).isEqualTo("[REDACTED] in the text");
        assertThat(redacted.getToolUses().get(0).getInput()).containsEntry("command", "echo [REDACTED]");
        // The payload says SECRET and still says SECRET. Rewriting one byte of it would make the provider reject the
        // replay, which is why it is outside the gate -- and why that is stated rather than left to be discovered.
        assertThat(redacted.getReasoningTraces()).containsExactly(TRACE);
        assertThat(redacted.getReasoningTraces().get(0).getPayload()).contains("SECRET");
    }
}
