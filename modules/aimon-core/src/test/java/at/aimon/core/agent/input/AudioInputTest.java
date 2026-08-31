package at.aimon.core.agent.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AudioInput Tests")
class AudioInputTest {

    private static final byte[] AUDIO_DATA = {0x49, 0x44, 0x33};

    @Test
    @DisplayName("Should create audio input via factory method")
    void shouldCreateAudioInput() {
        AudioInput input = AudioInput.of(AUDIO_DATA, "audio/mp3");

        assertThat(input.getData()).isEqualTo(AUDIO_DATA);
        assertThat(input.getMimeType()).isEqualTo("audio/mp3");
        assertThat(input.getSize()).isEqualTo(AUDIO_DATA.length);
    }

    @Test
    @DisplayName("Should return AUDIO type")
    void shouldReturnAudioType() {
        AudioInput input = AudioInput.of(AUDIO_DATA, "audio/mp3");

        assertThat(input.getType()).isEqualTo(InputType.AUDIO);
    }

    @Test
    @DisplayName("Should return descriptive text via asText")
    void shouldReturnDescriptiveText() {
        AudioInput input = AudioInput.of(AUDIO_DATA, "audio/mp3");

        assertThat(input.asText()).isEqualTo("[Audio: audio/mp3, 3 bytes]");
    }

    @Test
    @DisplayName("Should defensively copy data on construction")
    void shouldDefensivelyCopyDataOnConstruction() {
        byte[] originalData = {1, 2, 3};
        AudioInput input = AudioInput.of(originalData, "audio/wav");

        originalData[0] = 99;
        assertThat(input.getData()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Should defensively copy data on retrieval")
    void shouldDefensivelyCopyDataOnRetrieval() {
        AudioInput input = AudioInput.of(new byte[]{1, 2, 3}, "audio/wav");

        byte[] retrieved = input.getData();
        retrieved[0] = 99;
        assertThat(input.getData()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Should accept valid audio MIME types")
    void shouldAcceptValidAudioMimeTypes() {
        assertThat(AudioInput.of(AUDIO_DATA, "audio/mp3").getMimeType()).isEqualTo("audio/mp3");
        assertThat(AudioInput.of(AUDIO_DATA, "audio/wav").getMimeType()).isEqualTo("audio/wav");
        assertThat(AudioInput.of(AUDIO_DATA, "audio/ogg").getMimeType()).isEqualTo("audio/ogg");
        assertThat(AudioInput.of(AUDIO_DATA, "audio/flac").getMimeType()).isEqualTo("audio/flac");
    }

    @Test
    @DisplayName("Should reject non-audio MIME type")
    void shouldRejectNonAudioMimeType() {
        assertThatThrownBy(() -> AudioInput.of(AUDIO_DATA, "image/png")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME type must start with 'audio/'");
    }

    @Test
    @DisplayName("Should reject null data")
    void shouldRejectNullData() {
        assertThatThrownBy(() -> AudioInput.of(null, "audio/mp3")).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Audio data cannot be null");
    }

    @Test
    @DisplayName("Should reject null MIME type")
    void shouldRejectNullMimeType() {
        assertThatThrownBy(() -> AudioInput.of(AUDIO_DATA, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MIME type cannot be null");
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEquals() {
        AudioInput input1 = AudioInput.of(new byte[]{1, 2, 3}, "audio/mp3");
        AudioInput input2 = AudioInput.of(new byte[]{1, 2, 3}, "audio/mp3");
        AudioInput input3 = AudioInput.of(new byte[]{4, 5, 6}, "audio/mp3");
        AudioInput input4 = AudioInput.of(new byte[]{1, 2, 3}, "audio/wav");

        assertThat(input1).isEqualTo(input2);
        assertThat(input1).isNotEqualTo(input3);
        assertThat(input1).isNotEqualTo(input4);
        assertThat(input1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCode() {
        AudioInput input1 = AudioInput.of(new byte[]{1, 2, 3}, "audio/mp3");
        AudioInput input2 = AudioInput.of(new byte[]{1, 2, 3}, "audio/mp3");

        assertThat(input1.hashCode()).isEqualTo(input2.hashCode());
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        AudioInput input = AudioInput.of(AUDIO_DATA, "audio/mp3");

        assertThat(input.toString()).contains("AudioInput").contains("audio/mp3");
    }
}
