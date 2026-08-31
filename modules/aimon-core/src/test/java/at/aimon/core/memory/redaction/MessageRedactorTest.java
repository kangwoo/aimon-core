package at.aimon.core.memory.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

@DisplayName("MessageRedactor")
class MessageRedactorTest {

    private static final String AWS_KEY = "AKIA1234567890ABCDEF";
    private final MessageRedactor redactor = new MessageRedactor(new DefaultRedactionPolicy());

    @Test
    @DisplayName("redacts secrets in a TOOL-role message's tool results (the old gate skipped these)")
    void redactsToolResults() {
        Message tool = Message.toolUseResults(
                List.of(ToolUseResult.success("call-1", "kubectl output: AWS_KEY=" + AWS_KEY + " region=eu")));

        Message redacted = redactor.redact(tool);

        ToolUseResult result = redacted.getToolUseResults().get(0);
        assertThat(result.getContent()).doesNotContain(AWS_KEY).contains("[REDACTED:AWS_KEY]");
        assertThat(result.getToolUseId()).isEqualTo("call-1");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("redacts the text block of a multimodal message while preserving non-text blocks")
    void redactsMultimodalText() {
        Message multimodal = Message.user(List.of(TextContentBlock.of("aws " + AWS_KEY),
                ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png")));

        Message redacted = redactor.redact(multimodal);

        assertThat(redacted.getContent()).doesNotContain(AWS_KEY).contains("[REDACTED:AWS_KEY]");
        assertThat(redacted.hasNonTextContentBlocks()).isTrue();
        assertThat(redacted.getContentBlocks()).hasSize(2);
    }

    @Test
    @DisplayName("redacts USER text and preserves assistant tool uses")
    void redactsUserAndAssistant() {
        assertThat(redactor.redact(Message.user("key " + AWS_KEY)).getContent()).contains("[REDACTED:AWS_KEY]");

        Message assistant = Message.assistant("leaking " + AWS_KEY,
                List.of(ToolUse.of("call-9", "bash", java.util.Map.of("cmd", "ls"))));
        Message redacted = redactor.redact(assistant);
        assertThat(redacted.getContent()).doesNotContain(AWS_KEY);
        assertThat(redacted.getToolUses()).extracting(ToolUse::getId).containsExactly("call-9");
    }

    @Test
    @DisplayName("returns the same instance when nothing matches")
    void unchangedIsIdentity() {
        Message clean = Message.user("nothing sensitive here");
        assertThat(redactor.redact(clean)).isSameAs(clean);
    }

    @Test
    @DisplayName("redactAll redacts every message in order")
    void redactAll() {
        List<Message> out = redactor.redactAll(List.of(Message.user("a " + AWS_KEY),
                Message.toolUseResults(List.of(ToolUseResult.success("c", "b " + AWS_KEY)))));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getContent()).doesNotContain(AWS_KEY);
        assertThat(out.get(1).getToolUseResults().get(0).getContent()).doesNotContain(AWS_KEY);
    }
}
