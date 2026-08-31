package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

/**
 * Unit tests for {@link MessageStripper}: covers (a) existing image/document placeholder substitution behavior
 * (no-op redactor preserves backward compatibility) and (b) optional redaction of TextContentBlock content,
 * assistant text, and tool result content via a {@link SensitivePatternRedactor} (design §9.3 mitigation).
 */
class MessageStripperTest {

    private static final byte[] PNG = new byte[]{1, 2, 3};
    private static final byte[] PDF = new byte[]{4, 5, 6};

    private static SensitivePatternRedactor secretRedactor() {
        return SensitivePatternRedactor.of(List.of(RedactionPattern.of("SECRET", Pattern.compile("hunter2"))));
    }

    @Test
    void noOpStripperIsBackwardCompatibleForTextOnlyMessages() {
        final MessageStripper stripper = new MessageStripper();
        final List<Message> input = List.of(Message.user("hello"), Message.assistant("hi back"));

        final List<Message> result = stripper.stripNonTextBlocks(input);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isSameAs(input.get(0));
        assertThat(result.get(1)).isSameAs(input.get(1));
    }

    @Test
    void imageBlockReplacedByImagePlaceholder() {
        final MessageStripper stripper = new MessageStripper();
        final Message original = Message
                .user(List.of(TextContentBlock.of("see this:"), ImageContentBlock.ofBase64(PNG, "image/png")));

        final Message stripped = stripper.stripNonTextBlocks(List.of(original)).get(0);

        assertThat(stripped.hasNonTextContentBlocks()).isFalse();
        assertThat(stripped.getContent()).isEqualTo("see this:" + MessageStripper.IMAGE_PLACEHOLDER);
    }

    @Test
    void documentBlockReplacedByDocumentPlaceholder() {
        final MessageStripper stripper = new MessageStripper();
        final Message original = Message.user(
                List.of(TextContentBlock.of("attached:"), DocumentContentBlock.of(PDF, "application/pdf", "x.pdf")));

        final Message stripped = stripper.stripNonTextBlocks(List.of(original)).get(0);

        assertThat(stripped.hasNonTextContentBlocks()).isFalse();
        assertThat(stripped.getContent()).isEqualTo("attached:" + MessageStripper.DOCUMENT_PLACEHOLDER);
    }

    @Test
    void assistantToolUsesAndArtifactsArePreservedAcrossPlaceholderRewrite() {
        final MessageStripper stripper = new MessageStripper();
        final List<ToolUse> toolUses = List.of(ToolUse.of("tool_1", "Bash", Map.of("cmd", "ls")));
        final Message original = Message.assistant("running:", toolUses);

        final Message stripped = stripper.stripNonTextBlocks(List.of(original)).get(0);

        // No non-text blocks and no-op redactor → message returns unchanged (same instance).
        assertThat(stripped).isSameAs(original);
        assertThat(stripped.getToolUses()).isEqualTo(toolUses);
    }

    @Test
    void redactorRewritesUserTextContent() {
        final MessageStripper stripper = new MessageStripper(secretRedactor());
        final Message original = Message.user("password is hunter2 do not share");

        final Message stripped = stripper.stripNonTextBlocks(List.of(original)).get(0);

        assertThat(stripped.getContent()).isEqualTo("password is [REDACTED:SECRET] do not share");
    }

    @Test
    void redactorRewritesAssistantTextContent() {
        final MessageStripper stripper = new MessageStripper(secretRedactor());
        final Message original = Message.assistant("ack: hunter2 received");

        final Message stripped = stripper.stripNonTextBlocks(List.of(original)).get(0);

        assertThat(stripped.getContent()).isEqualTo("ack: [REDACTED:SECRET] received");
    }

    @Test
    void redactorRewritesToolResultSuccessContent() {
        final MessageStripper stripper = new MessageStripper(secretRedactor());
        final Message original = Message.toolUseResults(List.of(ToolUseResult.success("tool_1", "echo hunter2 done")));

        final Message stripped = stripper.stripNonTextBlocks(List.of(original)).get(0);

        assertThat(stripped.getRole()).isEqualTo(original.getRole());
        assertThat(stripped.getToolUseResults()).hasSize(1);
        final ToolUseResult result = stripped.getToolUseResults().get(0);
        assertThat(result.getToolUseId()).isEqualTo("tool_1");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("echo [REDACTED:SECRET] done");
    }

    @Test
    void redactorRewritesToolResultErrorContentAndPreservesErrorFlag() {
        final MessageStripper stripper = new MessageStripper(secretRedactor());
        final Message original = Message
                .toolUseResults(List.of(ToolUseResult.error("tool_e", "leaked hunter2 in error")));

        final ToolUseResult result = stripper.stripNonTextBlocks(List.of(original)).get(0).getToolUseResults().get(0);

        assertThat(result.isError()).isTrue();
        assertThat(result.getToolUseId()).isEqualTo("tool_e");
        assertThat(result.getContent()).isEqualTo("leaked [REDACTED:SECRET] in error");
    }

    @Test
    void toolResultUntouchedWhenContentHasNoMatch() {
        final MessageStripper stripper = new MessageStripper(secretRedactor());
        final ToolUseResult inner = ToolUseResult.success("tool_x", "all clear, nothing sensitive");
        final Message original = Message.toolUseResults(List.of(inner));

        final ToolUseResult result = stripper.stripNonTextBlocks(List.of(original)).get(0).getToolUseResults().get(0);

        // Same instance — redactor returned the source string unchanged so we keep the original ToolUseResult.
        assertThat(result).isSameAs(inner);
    }

    @Test
    void redactorAppliedToTextContentBlocksMixedWithImage() {
        final MessageStripper stripper = new MessageStripper(secretRedactor());
        final Message original = Message.user(
                List.of(TextContentBlock.of("contains hunter2 inline"), ImageContentBlock.ofBase64(PNG, "image/png")));

        final Message stripped = stripper.stripNonTextBlocks(List.of(original)).get(0);

        assertThat(stripped.hasNonTextContentBlocks()).isFalse();
        assertThat(stripped.getContent())
                .isEqualTo("contains [REDACTED:SECRET] inline" + MessageStripper.IMAGE_PLACEHOLDER);
    }

    @Test
    void noOpRedactorLeavesTextOnlyToolMessageStructurallyUnchanged() {
        final MessageStripper stripper = new MessageStripper();
        final Message original = Message
                .toolUseResults(List.of(ToolUseResult.success("tool_1", "hunter2 leaked but no redactor")));

        final Message stripped = stripper.stripNonTextBlocks(List.of(original)).get(0);

        assertThat(stripped).isSameAs(original);
    }

    @Test
    void multipleMessagesProcessedIndependently() {
        final MessageStripper stripper = new MessageStripper(secretRedactor());
        final List<Message> input = List.of(Message.user("safe"), Message.assistant("hunter2 here"),
                Message.toolUseResults(List.of(ToolUseResult.success("t", "also hunter2"))));

        final List<Message> result = stripper.stripNonTextBlocks(input);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getContent()).isEqualTo("safe");
        assertThat(result.get(1).getContent()).isEqualTo("[REDACTED:SECRET] here");
        assertThat(result.get(2).getToolUseResults().get(0).getContent()).isEqualTo("also [REDACTED:SECRET]");
    }

    @Test
    void constructorRejectsNullRedactor() {
        assertThatThrownBy(() -> new MessageStripper(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void stripNonTextBlocksRejectsNullInput() {
        final MessageStripper stripper = new MessageStripper();

        assertThatThrownBy(() -> stripper.stripNonTextBlocks(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void blockListsContentBlockTypeWhenNeitherTextNorImageNorDocument() {
        // Any custom ContentBlock subclass falls through to the unknown branch.
        final ContentBlock unknown = new ContentBlock() {
            @Override
            public String getType() {
                return "custom";
            }

            @Override
            public String asText() {
                return "custom-hunter2-data";
            }
        };
        final MessageStripper stripper = new MessageStripper(secretRedactor());
        final Message original = Message.user(List.of(unknown));

        final Message stripped = stripper.stripNonTextBlocks(List.of(original)).get(0);

        assertThat(stripped.getContent()).isEqualTo("custom-[REDACTED:SECRET]-data");
    }
}
