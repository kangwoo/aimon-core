/**
 * User input abstractions supporting text, image, audio, and multimodal inputs.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides a unified interface for handling various types of user input to agents. It supports text,
 * image, audio, file, and multimodal inputs.
 *
 * <h2>Key Concepts</h2>
 *
 * <h3>User Input Interface</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.input.UserInput} is the sealed base interface for all input types:
 *
 * <pre>
 * {
 *     &#64;code
 *     public sealed interface UserInput permits TextInput, ImageInput, AudioInput, FileInput, MultimodalInput {
 *         InputType getType(); // Returns InputType.TEXT, IMAGE, AUDIO, FILE, or MULTIMODAL
 *
 *         String asText(); // Text representation of the input
 *     }
 * }
 * </pre>
 *
 * <h3>Text Input</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.input.TextInput} represents simple text-based user input:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create text input
 *     UserInput input = TextInput.of("What files are in the current directory?");
 *
 *     // Access content
 *     InputType type = input.getType(); // InputType.TEXT
 *     String text = input.asText(); // "What files are in the current directory?"
 * }
 * </pre>
 *
 * <h3>Image Input</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.input.ImageInput} provides support for image-based inputs:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create image input
 *     byte[] imageData = Files.readAllBytes(Paths.get("screenshot.png"));
 *     UserInput input = ImageInput.of(imageData, "image/png");
 *
 *     // Access content
 *     InputType type = input.getType(); // InputType.IMAGE
 *     String text = input.asText(); // "[Image: image/png, 1024 bytes]"
 * }
 * </pre>
 *
 * <h3>Audio Input</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.input.AudioInput} provides support for audio-based inputs:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create audio input
 *     byte[] audioData = Files.readAllBytes(Paths.get("voice-command.mp3"));
 *     UserInput input = AudioInput.of(audioData, "audio/mp3");
 *
 *     // Access content
 *     InputType type = input.getType(); // InputType.AUDIO
 *     String text = input.asText(); // "[Audio: audio/mp3, 2048 bytes]"
 * }
 * </pre>
 *
 * <h3>Multimodal Input</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.input.MultimodalInput} combines multiple input types:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create multimodal input
 *     UserInput textInput = TextInput.of("What's in this image?");
 *     UserInput imageInput = ImageInput.of(imageData, "image/png");
 *     UserInput input = MultimodalInput.of(textInput, imageInput);
 *
 *     // Access content
 *     InputType type = input.getType(); // InputType.MULTIMODAL
 *     String text = input.asText(); // "What's in this image? [Image: image/png, 1024 bytes]"
 * }
 * </pre>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>Sealed Hierarchy:</b> UserInput is a sealed interface, ensuring exhaustive pattern matching
 * <li><b>Polymorphism:</b> All inputs implement UserInput, allowing uniform handling
 * <li><b>Value Object:</b> Input objects are immutable and represent values without identity
 * <li><b>Type Safety:</b> {@link at.aimon.core.agent.input.InputType} enum provides compile-time type checking
 * <li><b>Future-Proof:</b> Designed to support emerging input modalities
 * </ul>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Type-Based Handling</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     public void processInput(UserInput input) {
 *         switch (input.getType()) {
 *             case TEXT:
 *                 handleTextInput(input.asText());
 *                 break;
 *             case IMAGE:
 *                 handleImageInput((ImageInput) input);
 *                 break;
 *             case AUDIO:
 *                 handleAudioInput((AudioInput) input);
 *                 break;
 *             case MULTIMODAL:
 *                 handleMultimodalInput((MultimodalInput) input);
 *                 break;
 *             default:
 *                 throw new IllegalArgumentException("Unsupported input type: " + input.getType());
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>Instance-Based Handling</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     public void processInput(UserInput input) {
 *         if (input instanceof TextInput textInput) {
 *             handleTextInput(textInput.getText());
 *         } else if (input instanceof ImageInput imageInput) {
 *             handleImageInput(imageInput.getData(), imageInput.getMimeType());
 *         } else if (input instanceof AudioInput audioInput) {
 *             handleAudioInput(audioInput.getData(), audioInput.getMimeType());
 *         } else if (input instanceof MultimodalInput multimodalInput) {
 *             handleMultimodalInput(multimodalInput.getInputs());
 *         } else {
 *             throw new IllegalArgumentException("Unsupported input type: " + input.getClass());
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>Multimodal Type Filtering</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     MultimodalInput multimodal = MultimodalInput.of(textInput, imageInput);
 *
 *     // Filter by type
 *     List&lt;UserInput&gt; images = multimodal.getInputsOfType(InputType.IMAGE);
 *
 *     // Check for type
 *     boolean hasAudio = multimodal.hasType(InputType.AUDIO);
 * }
 * </pre>
 *
 * <h3>Logging and Display</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // All input types provide text representation
 *     UserInput input = ImageInput.of(imageData, "image/png");
 *     String description = input.asText(); // "[Image: image/png, 1024 bytes]"
 *     logger.info("User input: {}", description);
 * }
 * </pre>
 *
 * <h2>Implementation Notes</h2>
 *
 * <ul>
 * <li>TextInput is currently the primary input type used in the framework
 * <li>All input types are immutable and thread-safe
 * <li>Binary data (images, audio) is stored as byte arrays with defensive copying
 * <li>MIME types are validated at construction time (e.g., ImageInput requires "image/*")
 * </ul>
 *
 * <h3>File Input</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.input.FileInput} provides file-based input with metadata (file name, MIME type)
 * preservation. Text-based files are converted to structured documents for LLM providers:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create file input
 *     byte[] fileData = Files.readAllBytes(Paths.get("config.yaml"));
 *     UserInput input = FileInput.of(fileData, "text/plain", "config.yaml");
 *
 *     // Access content
 *     InputType type = input.getType(); // InputType.FILE
 *     String text = input.asText(); // file content as UTF-8 string
 * }
 * </pre>
 *
 * <h2>Future Enhancements</h2>
 *
 * <p>
 * As LLM capabilities evolve, this package is designed to support:
 *
 * <ul>
 * <li>Video input for visual question answering
 * <li>Structured data input (JSON, CSV) for data analysis
 * <li>Code input with syntax highlighting metadata
 * <li>3D model input for spatial reasoning
 * </ul>
 *
 * @see at.aimon.core.agent.input.UserInput
 * @see at.aimon.core.agent.input.InputType
 * @see at.aimon.core.agent.input.TextInput
 * @see at.aimon.core.agent.input.ImageInput
 * @see at.aimon.core.agent.input.AudioInput
 * @see at.aimon.core.agent.input.FileInput
 * @see at.aimon.core.agent.input.MultimodalInput
 */
package at.aimon.core.agent.input;
