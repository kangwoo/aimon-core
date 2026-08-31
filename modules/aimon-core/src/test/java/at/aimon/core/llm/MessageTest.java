package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

@DisplayName("Message Tests")
class MessageTest {

    @Nested
    @DisplayName("Backward Compatibility")
    class BackwardCompatibility {

        @Test
        @DisplayName("Should create user message")
        void shouldCreateUserMessage() {
            Message message = Message.user("Hello");

            assertThat(message.getRole()).isEqualTo(Role.USER);
            assertThat(message.getContent()).isEqualTo("Hello");
        }

        @Test
        @DisplayName("Should create assistant message")
        void shouldCreateAssistantMessage() {
            Message message = Message.assistant("Hi there");

            assertThat(message.getRole()).isEqualTo(Role.ASSISTANT);
            assertThat(message.getContent()).isEqualTo("Hi there");
        }

        @Test
        @DisplayName("Should reject null content in user message")
        void shouldRejectNullContentInUserMessage() {
            assertThatThrownBy(() -> Message.user((String) null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null content in assistant message")
        void shouldRejectNullContentInAssistantMessage() {
            assertThatThrownBy(() -> Message.assistant(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null toolUses in assistant message with tool uses")
        void shouldRejectNullToolUsesInAssistantMessage() {
            assertThatThrownBy(() -> Message.assistant("text", null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Tool uses cannot be null");
        }

        @Test
        @DisplayName("Should implement equals and hashCode correctly")
        void shouldImplementEqualsAndHashCode() {
            Message msg1 = Message.user("Hello");
            Message msg2 = Message.user("Hello");
            Message msg3 = Message.user("Different");
            Message msg4 = Message.assistant("Hello");

            assertThat(msg1).isEqualTo(msg2);
            assertThat(msg1.hashCode()).isEqualTo(msg2.hashCode());

            assertThat(msg1).isNotEqualTo(msg3);
            assertThat(msg1).isNotEqualTo(msg4);
            assertThat(msg1).isNotEqualTo(null);
            assertThat(msg1).isNotEqualTo("string");
        }

        @Test
        @DisplayName("Should have meaningful toString with content preview")
        void shouldHaveMeaningfulToString() {
            Message message = Message.user("Hello");

            String toString = message.toString();

            assertThat(toString).contains("Message");
            assertThat(toString).contains("USER");
            assertThat(toString).contains("Hello");
        }

        @Test
        @DisplayName("Should truncate long content in toString")
        void shouldTruncateLongContentInToString() {
            String longContent = "A".repeat(100);
            Message message = Message.user(longContent);

            String toString = message.toString();

            assertThat(toString).contains("A".repeat(50) + "...");
            assertThat(toString).doesNotContain("A".repeat(51));
        }

        @Test
        @DisplayName("Should handle empty content")
        void shouldHandleEmptyContent() {
            Message message = Message.user("");

            assertThat(message.getContent()).isEmpty();
        }

        @Test
        @DisplayName("Should handle multiline content")
        void shouldHandleMultilineContent() {
            String multiline = "Line 1\nLine 2\nLine 3";
            Message message = Message.user(multiline);

            assertThat(message.getContent()).isEqualTo(multiline);
            assertThat(message.getContent()).contains("Line 1", "Line 2", "Line 3");
        }
    }

    @Nested
    @DisplayName("Content Blocks")
    class ContentBlocks {

        @Test
        @DisplayName("Should create user message with content blocks")
        void shouldCreateUserMessageWithContentBlocks() {
            List<ContentBlock> blocks = List.of(TextContentBlock.of("Describe this image"),
                    ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png"));

            Message message = Message.user(blocks);

            assertThat(message.getRole()).isEqualTo(Role.USER);
            assertThat(message.getContentBlocks()).hasSize(2);
            assertThat(message.getContent()).isEqualTo("Describe this image");
            assertThat(message.hasNonTextContentBlocks()).isTrue();
        }

        @Test
        @DisplayName("Should reject null content blocks list")
        void shouldRejectNullContentBlocksList() {
            assertThatThrownBy(() -> Message.user((List<ContentBlock>) null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject empty content blocks list")
        void shouldRejectEmptyContentBlocksList() {
            assertThatThrownBy(() -> Message.user(List.of())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Content blocks cannot be empty");
        }

        @Test
        @DisplayName("Should reject null element in content blocks list")
        void shouldRejectNullElementInContentBlocksList() {
            List<ContentBlock> blocks = Arrays.asList(TextContentBlock.of("text"), null);
            assertThatThrownBy(() -> Message.user(blocks)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Content blocks must not contain null elements");
        }

        @Test
        @DisplayName("Should reject null element in attachments list")
        void shouldRejectNullElementInAttachmentsList() {
            List<ContentBlock> attachments = Arrays.asList(ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png"),
                    null);
            assertThatThrownBy(() -> Message.userWithAttachments("text", attachments))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Attachments must not contain null elements");
        }

        @Test
        @DisplayName("Should create user message with attachments")
        void shouldCreateUserMessageWithAttachments() {
            List<ContentBlock> attachments = List.of(ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png"));

            Message message = Message.userWithAttachments("What's in this image?", attachments);

            assertThat(message.getContentBlocks()).hasSize(2);
            assertThat(message.getContent()).isEqualTo("What's in this image?");
            assertThat(message.hasNonTextContentBlocks()).isTrue();
        }

        @Test
        @DisplayName("Should skip text block when text is empty in userWithAttachments")
        void shouldSkipTextBlockWhenTextIsEmpty() {
            List<ContentBlock> attachments = List.of(ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png"));

            Message message = Message.userWithAttachments("", attachments);

            assertThat(message.getContentBlocks()).hasSize(1);
            assertThat(message.getContentBlocks().get(0)).isInstanceOf(ImageContentBlock.class);
        }

        @Test
        @DisplayName("Should reject empty text and empty attachments")
        void shouldRejectEmptyTextAndEmptyAttachments() {
            assertThatThrownBy(() -> Message.userWithAttachments("", List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Text-only message should report no non-text content blocks")
        void textOnlyMessageShouldReportNoNonTextContentBlocks() {
            Message message = Message.user("Hello");

            assertThat(message.hasNonTextContentBlocks()).isFalse();
            assertThat(message.hasContentBlocks()).isTrue();
        }

        @Test
        @DisplayName("Should return content blocks for text message")
        void shouldReturnContentBlocksForTextMessage() {
            Message message = Message.user("Hello");

            assertThat(message.getContentBlocks()).hasSize(1);
            assertThat(message.getContentBlocks().get(0)).isInstanceOf(TextContentBlock.class);
        }

        @Test
        @DisplayName("Tool result message should have empty content blocks")
        void toolResultMessageShouldHaveEmptyContentBlocks() {
            Message message = Message.toolUseResults(List.of(at.aimon.core.llm.ToolUseResult.success("id", "result")));

            assertThat(message.getContentBlocks()).isEmpty();
            assertThat(message.hasContentBlocks()).isFalse();
            assertThat(message.hasNonTextContentBlocks()).isFalse();
        }
    }

    @Nested
    @DisplayName("Artifacts")
    class Artifacts {

        @Test
        @DisplayName("Should create assistant message with artifacts")
        void shouldCreateAssistantMessageWithArtifacts() {
            MessageArtifact artifact = MessageArtifact.builder().path("/reports/sales.csv").fileName("sales.csv")
                    .size(1024).mimeType("text/csv").toolUseId("tool_1").build();
            List<ToolUse> toolUses = List.of(ToolUse.of("tool_1", "Write", Map.of("path", "/reports/sales.csv")));

            Message message = Message.assistant("I'll create the report.", toolUses, List.of(artifact));

            assertThat(message.getRole()).isEqualTo(Role.ASSISTANT);
            assertThat(message.hasArtifacts()).isTrue();
            assertThat(message.getArtifacts()).hasSize(1);
            assertThat(message.getArtifacts().get(0).getPath()).isEqualTo("/reports/sales.csv");
            assertThat(message.getArtifacts().get(0).getToolUseId()).hasValue("tool_1");
        }

        @Test
        @DisplayName("Should have empty artifacts by default")
        void shouldHaveEmptyArtifactsByDefault() {
            Message message = Message.assistant("Hello");

            assertThat(message.hasArtifacts()).isFalse();
            assertThat(message.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Should have empty artifacts for user message")
        void shouldHaveEmptyArtifactsForUserMessage() {
            Message message = Message.user("Hello");

            assertThat(message.hasArtifacts()).isFalse();
            assertThat(message.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Should create message with withArtifacts")
        void shouldCreateMessageWithWithArtifacts() {
            MessageArtifact artifact = MessageArtifact.builder().path("/output/result.json").fileName("result.json")
                    .size(512).build();
            List<ToolUse> toolUses = List.of(ToolUse.of("tool_1", "Write", Map.of()));
            Message original = Message.assistant("Writing file.", toolUses);

            Message withArtifacts = original.withArtifacts(List.of(artifact));

            assertThat(original.hasArtifacts()).isFalse();
            assertThat(withArtifacts.hasArtifacts()).isTrue();
            assertThat(withArtifacts.getArtifacts()).hasSize(1);
            assertThat(withArtifacts.getContent()).isEqualTo("Writing file.");
            assertThat(withArtifacts.getToolUses()).isEqualTo(toolUses);
            assertThat(withArtifacts.getRole()).isEqualTo(Role.ASSISTANT);
        }

        @Test
        @DisplayName("Should reject null artifacts in factory method")
        void shouldRejectNullArtifactsInFactoryMethod() {
            assertThatThrownBy(() -> Message.assistant("text", List.of(), null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Artifacts cannot be null");
        }

        @Test
        @DisplayName("Should reject null artifacts in withArtifacts")
        void shouldRejectNullArtifactsInWithArtifacts() {
            Message message = Message.assistant("text");

            assertThatThrownBy(() -> message.withArtifacts(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Artifacts cannot be null");
        }

        @Test
        @DisplayName("Should return immutable artifacts list")
        void shouldReturnImmutableArtifactsList() {
            MessageArtifact artifact = MessageArtifact.builder().path("/file.txt").fileName("file.txt").build();
            Message message = Message.assistant("text", List.of(), List.of(artifact));

            assertThatThrownBy(() -> message.getArtifacts().add(artifact))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Should include artifacts in equals and hashCode")
        void shouldIncludeArtifactsInEqualsAndHashCode() {
            MessageArtifact artifact = MessageArtifact.builder().path("/file.txt").fileName("file.txt").build();
            List<ToolUse> toolUses = List.of();

            Message msg1 = Message.assistant("text", toolUses, List.of(artifact));
            Message msg2 = Message.assistant("text", toolUses, List.of(artifact));
            Message msg3 = Message.assistant("text", toolUses, List.of());

            assertThat(msg1).isEqualTo(msg2);
            assertThat(msg1.hashCode()).isEqualTo(msg2.hashCode());
            assertThat(msg1).isNotEqualTo(msg3);
        }

        @Test
        @DisplayName("Should include artifacts count in toString")
        void shouldIncludeArtifactsCountInToString() {
            MessageArtifact artifact = MessageArtifact.builder().path("/file.txt").fileName("file.txt").build();
            Message message = Message.assistant("text", List.of(), List.of(artifact));

            assertThat(message.toString()).contains("artifacts=1");
        }
    }

    @Nested
    @DisplayName("Multimodal Equals and HashCode")
    class MultimodalEqualsHashCode {

        @Test
        @DisplayName("Should be equal for same content blocks")
        void shouldBeEqualForSameContentBlocks() {
            List<ContentBlock> blocks1 = List.of(TextContentBlock.of("Hello"),
                    ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png"));
            List<ContentBlock> blocks2 = List.of(TextContentBlock.of("Hello"),
                    ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png"));

            Message msg1 = Message.user(blocks1);
            Message msg2 = Message.user(blocks2);

            assertThat(msg1).isEqualTo(msg2);
            assertThat(msg1.hashCode()).isEqualTo(msg2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal for different content blocks")
        void shouldNotBeEqualForDifferentContentBlocks() {
            Message msg1 = Message.user(List.of(TextContentBlock.of("Hello"),
                    ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png")));
            Message msg2 = Message.user(List.of(TextContentBlock.of("Hello"),
                    ImageContentBlock.ofBase64(new byte[]{4, 5, 6}, "image/png")));

            assertThat(msg1).isNotEqualTo(msg2);
        }
    }

    @Nested
    @DisplayName("mapText")
    class MapText {

        @Test
        @DisplayName("transforms text content and tool-use argument string values (redaction seam)")
        void redactsToolUseArgumentValues() {
            List<ToolUse> toolUses = List
                    .of(ToolUse.of("t1", "Bash", Map.of("command", "mysql -pSECRET", "timeout", 30)));
            Message original = Message.assistant("run SECRET now", toolUses);

            Message redacted = original.mapText(s -> s.replace("SECRET", "[REDACTED]"));

            // Assistant text is transformed.
            assertThat(redacted.getContent()).isEqualTo("run [REDACTED] now");
            // Tool-use string argument is transformed; ids/names and non-string args are preserved.
            ToolUse redactedUse = redacted.getToolUses().get(0);
            assertThat(redactedUse.getId()).isEqualTo("t1");
            assertThat(redactedUse.getName()).isEqualTo("Bash");
            assertThat(redactedUse.getInput().get("command")).isEqualTo("mysql -p[REDACTED]");
            assertThat(redactedUse.getInput().get("timeout")).isEqualTo(30);
        }

        @Test
        @DisplayName("recurses into nested maps and lists inside tool-use inputs")
        void redactsNestedToolUseArguments() {
            Map<String, Object> nested = Map.of("headers", Map.of("Authorization", "Bearer SECRET"), "flags",
                    List.of("--token=SECRET", "--verbose"));
            List<ToolUse> toolUses = List.of(ToolUse.of("t1", "HttpRequest", nested));
            Message original = Message.assistant("call api", toolUses);

            Message redacted = original.mapText(s -> s.replace("SECRET", "[REDACTED]"));

            Map<String, Object> input = redacted.getToolUses().get(0).getInput();
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) input.get("headers");
            assertThat(headers.get("Authorization")).isEqualTo("Bearer [REDACTED]");
            @SuppressWarnings("unchecked")
            List<Object> flags = (List<Object>) input.get("flags");
            assertThat(flags).containsExactly("--token=[REDACTED]", "--verbose");
        }
    }
}
