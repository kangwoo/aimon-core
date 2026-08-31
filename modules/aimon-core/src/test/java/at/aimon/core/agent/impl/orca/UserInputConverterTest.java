package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.input.FileInput;
import at.aimon.core.agent.input.ImageInput;
import at.aimon.core.agent.input.MultimodalInput;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

@DisplayName("UserInputConverter Tests")
class UserInputConverterTest {

    @Test
    @DisplayName("Should build simple text message")
    void buildUserMessage_textInput() {
        TextInput input = TextInput.of("Hello, world!");

        Message message = UserInputConverter.buildUserMessage(input);

        assertThat(message.getRole()).isEqualTo(Role.USER);
        assertThat(message.getContent()).isEqualTo("Hello, world!");
    }

    @Test
    @DisplayName("Should build image message with base64 content block")
    void buildUserMessage_imageInput() {
        byte[] imageData = {(byte) 0x89, 0x50, 0x4E, 0x47};
        ImageInput input = ImageInput.of(imageData, "image/png");

        Message message = UserInputConverter.buildUserMessage(input);

        assertThat(message.getRole()).isEqualTo(Role.USER);
        assertThat(message.getContentBlocks()).hasSize(1);
        assertThat(message.getContentBlocks().get(0)).isInstanceOf(ImageContentBlock.class);
    }

    @Test
    @DisplayName("Should build document content block for PDF file input")
    void buildUserMessage_fileInput_pdf() {
        byte[] pdfData = "PDF content".getBytes(StandardCharsets.UTF_8);
        FileInput input = FileInput.of(pdfData, "application/pdf", "report.pdf");

        Message message = UserInputConverter.buildUserMessage(input);

        assertThat(message.getRole()).isEqualTo(Role.USER);
        assertThat(message.getContentBlocks()).hasSize(1);
        assertThat(message.getContentBlocks().get(0)).isInstanceOf(DocumentContentBlock.class);
    }

    @Test
    @DisplayName("Should flatten multimodal input into multiple content blocks")
    void buildUserMessage_multimodalInput() {
        TextInput text = TextInput.of("Describe this image:");
        ImageInput image = ImageInput.of(new byte[]{1, 2, 3}, "image/jpeg");
        MultimodalInput input = MultimodalInput.of(text, image);

        Message message = UserInputConverter.buildUserMessage(input);

        assertThat(message.getRole()).isEqualTo(Role.USER);
        assertThat(message.getContentBlocks()).hasSize(2);
        assertThat(message.getContentBlocks().get(0)).isInstanceOf(TextContentBlock.class);
        assertThat(message.getContentBlocks().get(1)).isInstanceOf(ImageContentBlock.class);
    }

    @Test
    @DisplayName("Should convert text file input to document content block")
    void buildUserMessage_fileInput_text() {
        byte[] textData = "Hello".getBytes(StandardCharsets.UTF_8);
        FileInput input = FileInput.of(textData, "text/plain", "notes.txt");

        ContentBlock block = UserInputConverter.toSingleContentBlock(input);

        assertThat(block).isInstanceOf(DocumentContentBlock.class);
    }

    @Test
    @DisplayName("Should convert image file input to image content block")
    void buildUserMessage_fileInput_image() {
        byte[] imageData = {(byte) 0xFF, (byte) 0xD8};
        FileInput input = FileInput.of(imageData, "image/jpeg", "photo.jpg");

        ContentBlock block = UserInputConverter.toSingleContentBlock(input);

        assertThat(block).isInstanceOf(ImageContentBlock.class);
    }
}
