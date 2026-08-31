package at.aimon.core.agent.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MultimodalInput Tests")
class MultimodalInputTest {

    private static final TextInput TEXT = TextInput.of("What's in this image?");
    private static final ImageInput IMAGE = ImageInput.of(new byte[]{1, 2, 3}, "image/png");
    private static final AudioInput AUDIO = AudioInput.of(new byte[]{4, 5, 6}, "audio/mp3");

    @Test
    @DisplayName("Should create multimodal input via varargs factory")
    void shouldCreateViaVarargsFactory() {
        MultimodalInput input = MultimodalInput.of(TEXT, IMAGE);

        assertThat(input.getInputs()).hasSize(2);
        assertThat(input.getInputs().get(0)).isEqualTo(TEXT);
        assertThat(input.getInputs().get(1)).isEqualTo(IMAGE);
    }

    @Test
    @DisplayName("Should create multimodal input via list factory")
    void shouldCreateViaListFactory() {
        MultimodalInput input = MultimodalInput.of(List.of(TEXT, IMAGE));

        assertThat(input.getInputs()).hasSize(2);
    }

    @Test
    @DisplayName("Should create multimodal input via builder")
    void shouldCreateViaBuilder() {
        MultimodalInput input = MultimodalInput.builder().add(TEXT).add(IMAGE).add(AUDIO).build();

        assertThat(input.getInputs()).hasSize(3);
    }

    @Test
    @DisplayName("Should return MULTIMODAL type")
    void shouldReturnMultimodalType() {
        MultimodalInput input = MultimodalInput.of(TEXT, IMAGE);

        assertThat(input.getType()).isEqualTo(InputType.MULTIMODAL);
    }

    @Test
    @DisplayName("Should include all inputs in asText")
    void shouldIncludeAllInputsInAsText() {
        MultimodalInput input = MultimodalInput.of(TEXT, IMAGE);

        String text = input.asText();
        assertThat(text).contains("What's in this image?");
        assertThat(text).contains("[Image: image/png, 3 bytes]");
    }

    @Test
    @DisplayName("Should include non-text inputs in asText")
    void shouldIncludeNonTextInputsInAsText() {
        MultimodalInput input = MultimodalInput.of(IMAGE, AUDIO);

        String text = input.asText();
        assertThat(text).contains("[Image: image/png, 3 bytes]");
        assertThat(text).contains("[Audio: audio/mp3, 3 bytes]");
    }

    @Test
    @DisplayName("Should reject empty inputs")
    void shouldRejectEmptyInputs() {
        assertThatThrownBy(() -> MultimodalInput.of(new UserInput[0])).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Inputs cannot be empty");
    }

    @Test
    @DisplayName("Should reject empty list")
    void shouldRejectEmptyList() {
        assertThatThrownBy(() -> MultimodalInput.of(List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Inputs cannot be empty");
    }

    @Test
    @DisplayName("Should reject empty builder")
    void shouldRejectEmptyBuilder() {
        assertThatThrownBy(() -> MultimodalInput.builder().build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Inputs cannot be empty");
    }

    @Test
    @DisplayName("Should return immutable list from getInputs")
    void shouldReturnImmutableList() {
        MultimodalInput input = MultimodalInput.of(TEXT, IMAGE);

        assertThatThrownBy(() -> input.getInputs().add(AUDIO)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should filter inputs by type")
    void shouldFilterInputsByType() {
        MultimodalInput input = MultimodalInput.of(TEXT, IMAGE, AUDIO);

        List<UserInput> textInputs = input.getInputsOfType(InputType.TEXT);
        assertThat(textInputs).hasSize(1);
        assertThat(textInputs.get(0)).isEqualTo(TEXT);

        List<UserInput> imageInputs = input.getInputsOfType(InputType.IMAGE);
        assertThat(imageInputs).hasSize(1);
        assertThat(imageInputs.get(0)).isEqualTo(IMAGE);

        List<UserInput> fileInputs = input.getInputsOfType(InputType.FILE);
        assertThat(fileInputs).isEmpty();
    }

    @Test
    @DisplayName("Should check if type exists")
    void shouldCheckIfTypeExists() {
        MultimodalInput input = MultimodalInput.of(TEXT, IMAGE);

        assertThat(input.hasType(InputType.TEXT)).isTrue();
        assertThat(input.hasType(InputType.IMAGE)).isTrue();
        assertThat(input.hasType(InputType.AUDIO)).isFalse();
        assertThat(input.hasType(InputType.FILE)).isFalse();
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEquals() {
        MultimodalInput input1 = MultimodalInput.of(TEXT, IMAGE);
        MultimodalInput input2 = MultimodalInput.of(TEXT, IMAGE);
        MultimodalInput input3 = MultimodalInput.of(TEXT, AUDIO);

        assertThat(input1).isEqualTo(input2);
        assertThat(input1).isNotEqualTo(input3);
        assertThat(input1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCode() {
        MultimodalInput input1 = MultimodalInput.of(TEXT, IMAGE);
        MultimodalInput input2 = MultimodalInput.of(TEXT, IMAGE);

        assertThat(input1.hashCode()).isEqualTo(input2.hashCode());
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        MultimodalInput input = MultimodalInput.of(TEXT, IMAGE);

        assertThat(input.toString()).contains("MultimodalInput").contains("2");
    }
}
