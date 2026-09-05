package at.aimon.core.subagent.task.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.input.AudioInput;
import at.aimon.core.agent.input.FileInput;
import at.aimon.core.agent.input.ImageInput;
import at.aimon.core.agent.input.InputType;
import at.aimon.core.agent.input.MultimodalInput;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;

/**
 * Pins the encoding a rewind point stores and a routed submission now travels in.
 *
 * <p>
 * A round-trip through this one class cannot say whether the stored shape drifted — encoder and decoder share the
 * constants, so a renamed field agrees with itself. So the shapes are also asserted against <b>hard-coded
 * literals</b>, which is what an existing snapshot file or an already-queued inbox entry actually contains. That is
 * the same reason the frozen-name pins exist, applied to a format this repository writes to three backends and a
 * virtual filesystem.
 */
@DisplayName("UserInputCodec")
class UserInputCodecTest {

    private static final byte[] BYTES = {1, 2, 3, 4};
    /** {@code AQIDBA==} — pinned rather than computed, for the same reason the field names are. */
    private static final String BYTES_BASE64 = "AQIDBA==";

    @Test
    @DisplayName("text encodes to {type:text, text:…} and nothing else")
    void textShapeIsPinned() {
        final ObjectNode node = UserInputCodec.encode(TextInput.of("what is this?"));

        assertThat(fieldNames(node)).containsExactlyInAnyOrder("type", "text");
        assertThat(node.get("type").asText()).isEqualTo("text");
        assertThat(node.get("text").asText()).isEqualTo("what is this?");
    }

    @Test
    @DisplayName("image encodes to {type:image, mimeType, data} with the bytes as plain base64")
    void imageShapeIsPinned() {
        final ObjectNode node = UserInputCodec.encode(ImageInput.of(BYTES, "image/png"));

        assertThat(fieldNames(node)).containsExactlyInAnyOrder("type", "mimeType", "data");
        assertThat(node.get("type").asText()).isEqualTo("image");
        assertThat(node.get("mimeType").asText()).isEqualTo("image/png");
        assertThat(node.get("data").asText()).isEqualTo(BYTES_BASE64);
    }

    @Test
    @DisplayName("audio encodes to {type:audio, mimeType, data}")
    void audioShapeIsPinned() {
        final ObjectNode node = UserInputCodec.encode(AudioInput.of(BYTES, "audio/mp3"));

        assertThat(fieldNames(node)).containsExactlyInAnyOrder("type", "mimeType", "data");
        assertThat(node.get("type").asText()).isEqualTo("audio");
        assertThat(node.get("mimeType").asText()).isEqualTo("audio/mp3");
        assertThat(node.get("data").asText()).isEqualTo(BYTES_BASE64);
    }

    @Test
    @DisplayName("file encodes to {type:file, mimeType, fileName, data} — the name is what distinguishes it")
    void fileShapeIsPinned() {
        final ObjectNode node = UserInputCodec.encode(FileInput.of(BYTES, "application/pdf", "report.pdf"));

        assertThat(fieldNames(node)).containsExactlyInAnyOrder("type", "mimeType", "fileName", "data");
        assertThat(node.get("type").asText()).isEqualTo("file");
        assertThat(node.get("fileName").asText()).isEqualTo("report.pdf");
    }

    @Test
    @DisplayName("multimodal encodes to {type:multimodal, inputs:[…]} with each part in the same shape")
    void multimodalShapeIsPinned() {
        final ObjectNode node = UserInputCodec
                .encode(MultimodalInput.of(TextInput.of("describe"), ImageInput.of(BYTES, "image/png")));

        assertThat(fieldNames(node)).containsExactlyInAnyOrder("type", "inputs");
        assertThat(node.get("type").asText()).isEqualTo("multimodal");
        assertThat(node.get("inputs")).hasSize(2);
        assertThat(node.get("inputs").get(0).get("type").asText()).isEqualTo("text");
        assertThat(node.get("inputs").get(1).get("data").asText()).isEqualTo(BYTES_BASE64);
    }

    @Test
    @DisplayName("every input type round-trips to an equal value")
    void everyTypeRoundTrips() {
        final UserInput text = TextInput.of("hello");
        final UserInput image = ImageInput.of(BYTES, "image/png");
        final UserInput audio = AudioInput.of(BYTES, "audio/wav");
        final UserInput file = FileInput.of("k: v".getBytes(StandardCharsets.UTF_8), "text/plain", "config.yaml");
        final UserInput multimodal = MultimodalInput.of(text, image, audio, file);

        for (UserInput input : new UserInput[]{text, image, audio, file, multimodal}) {
            assertThat(UserInputCodec.decode(UserInputCodec.encode(input))).isEqualTo(input);
            assertThat(UserInputCodec.decodeFromString(UserInputCodec.encodeToString(input))).isEqualTo(input);
        }
    }

    @Test
    @DisplayName("the published type tags cover every InputType, so no shape can be added without one")
    void typeTagsCoverEveryInputType() {
        assertThat(UserInputCodec.TYPE_TAGS).hasSize(InputType.values().length);
        assertThat(UserInputCodec.TYPE_TAGS).containsExactlyInAnyOrder(Arrays.stream(InputType.values())
                .map(t -> t.name().toLowerCase(java.util.Locale.ROOT)).toArray(String[]::new));
    }

    @Test
    @DisplayName("nesting within the bound decodes; past it the document is refused rather than overflowing")
    void nestingIsBounded() {
        // One below the bound, built by hand so the assertion is about the decode rather than about what an encode
        // happens to produce.
        assertThat(UserInputCodec.decodeFromString(nested(UserInputCodec.MAX_NESTING))).isNotNull();

        assertThatThrownBy(() -> UserInputCodec.decodeFromString(nested(UserInputCodec.MAX_NESTING + 1)))
                .isInstanceOf(SessionSnapshotCodecException.class).hasMessageContaining("nests deeper than");
    }

    @Test
    @DisplayName("an unknown type tag is refused, not silently dropped")
    void unknownTypeIsRefused() {
        assertThatThrownBy(() -> UserInputCodec.decodeFromString("{\"type\":\"video\"}"))
                .isInstanceOf(SessionSnapshotCodecException.class).hasMessageContaining("Unknown user input type");
    }

    @Test
    @DisplayName("a missing required field is refused, naming the field")
    void missingFieldIsRefused() {
        assertThatThrownBy(() -> UserInputCodec.decodeFromString("{\"type\":\"image\",\"mimeType\":\"image/png\"}"))
                .isInstanceOf(SessionSnapshotCodecException.class).hasMessageContaining("'data'");
    }

    @Test
    @DisplayName("an empty multimodal is refused — MultimodalInput cannot hold zero parts")
    void emptyMultimodalIsRefused() {
        assertThatThrownBy(() -> UserInputCodec.decodeFromString("{\"type\":\"multimodal\",\"inputs\":[]}"))
                .isInstanceOf(SessionSnapshotCodecException.class).hasMessageContaining("carries no inputs");
    }

    @Test
    @DisplayName("invalid base64 is refused as a codec failure, not as an IllegalArgumentException from the JDK")
    void invalidBase64IsRefused() {
        assertThatThrownBy(() -> UserInputCodec
                .decodeFromString("{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"not base64!\"}"))
                .isInstanceOf(SessionSnapshotCodecException.class).hasMessageContaining("Invalid base64");
    }

    @Test
    @DisplayName("a non-object subtree is refused rather than yielding an empty input")
    void nonObjectIsRefused() {
        assertThatThrownBy(() -> UserInputCodec.decodeFromString("\"hello\""))
                .isInstanceOf(SessionSnapshotCodecException.class).hasMessageContaining("not a JSON object");
        assertThatThrownBy(() -> UserInputCodec.decode(null)).isInstanceOf(SessionSnapshotCodecException.class);
    }

    /** {@code depth} levels of {@code multimodal} wrapping one text leaf. */
    private static String nested(int depth) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("{\"type\":\"multimodal\",\"inputs\":[");
        }
        sb.append("{\"type\":\"text\",\"text\":\"leaf\"}");
        for (int i = 0; i < depth; i++) {
            sb.append("]}");
        }
        return sb.toString();
    }

    private static Set<String> fieldNames(ObjectNode node) {
        return node.propertyStream().map(java.util.Map.Entry::getKey).collect(Collectors.toSet());
    }
}
