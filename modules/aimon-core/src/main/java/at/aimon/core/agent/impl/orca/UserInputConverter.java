package at.aimon.core.agent.impl.orca;

import java.util.ArrayList;
import java.util.List;

import at.aimon.core.agent.input.AudioInput;
import at.aimon.core.agent.input.FileInput;
import at.aimon.core.agent.input.ImageInput;
import at.aimon.core.agent.input.MultimodalInput;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

/**
 * Converts {@link UserInput} instances to LLM {@link Message} objects.
 *
 * <p>
 * Handles text, image, file, audio, and multimodal inputs, producing the appropriate content blocks for each type.
 * Multimodal inputs are recursively flattened into a single list of content blocks.
 *
 * <p>
 * All methods are static and thread-safe.
 */
final class UserInputConverter {

    private UserInputConverter() {
        // Utility class
    }

    /**
     * Builds a Message from a UserInput, preserving multimodal content.
     *
     * <p>
     * For TextInput, creates a simple text message. For all other input types, delegates to {@link #toContentBlocks}
     * which handles both leaf types and recursive MultimodalInput.
     *
     * @param userInput
     *            The user input (must not be null)
     * @return A Message containing the appropriate content blocks
     */
    static Message buildUserMessage(UserInput userInput) {
        if (userInput instanceof TextInput textInput) {
            return Message.user(textInput.asText());
        }
        return Message.user(toContentBlocks(userInput));
    }

    /**
     * Converts a UserInput to a list of ContentBlocks, recursively flattening MultimodalInput.
     *
     * @param userInput
     *            The user input
     * @return The list of content blocks (never null or empty)
     */
    static List<ContentBlock> toContentBlocks(UserInput userInput) {
        if (userInput instanceof MultimodalInput multimodalInput) {
            final List<ContentBlock> blocks = new ArrayList<>();
            for (UserInput input : multimodalInput.getInputs()) {
                blocks.addAll(toContentBlocks(input));
            }
            return blocks;
        }
        return List.of(toSingleContentBlock(userInput));
    }

    /**
     * Converts a single non-multimodal UserInput to a ContentBlock.
     *
     * @param userInput
     *            The user input (must not be MultimodalInput)
     * @return The corresponding content block
     */
    static ContentBlock toSingleContentBlock(UserInput userInput) {
        if (userInput instanceof TextInput textInput) {
            return TextContentBlock.of(textInput.asText());
        } else if (userInput instanceof ImageInput imageInput) {
            return ImageContentBlock.ofBase64(imageInput.getData(), imageInput.getMimeType());
        } else if (userInput instanceof FileInput fileInput) {
            String mime = fileInput.getMimeType();
            // Each block is asked whether it takes the type rather than matched on a prefix: both factories throw on
            // a type outside their allow-list, and a throw here fails the whole turn (#164).
            if (DocumentContentBlock.isSupportedMimeType(mime)) {
                return DocumentContentBlock.of(fileInput.getData(), mime, fileInput.getFileName());
            } else if (ImageContentBlock.isSupportedMimeType(mime)) {
                return ImageContentBlock.ofBase64(fileInput.getData(), mime);
            } else if (mime.startsWith("text/")) {
                // An unlisted text type (text/yaml, text/x-java, ...): send the content, under the same header the
                // provider converters put on a text document, so two attachments stay distinguishable by name.
                return TextContentBlock
                        .of("[File: " + fileInput.getFileName() + " (" + mime + ")]\n" + fileInput.asText());
            } else {
                // Anything else, an unlisted image/* (svg, bmp, heic) included: FileInput.asText() is a placeholder
                // naming the file, its type and its size.
                return TextContentBlock.of(fileInput.asText());
            }
        } else if (userInput instanceof AudioInput audioInput) {
            // Fallback to text representation until AudioContentBlock is added
            return TextContentBlock.of(audioInput.asText());
        }
        throw new IllegalArgumentException("Unsupported input type: " + userInput.getClass());
    }
}
