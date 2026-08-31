package at.aimon.core.agent.input;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents audio-based user input.
 *
 * <p>
 * This input type supports audio data for speech-to-text or audio-understanding AI models.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     byte[] audioData = Files.readAllBytes(Path.of("voice.mp3"));
 *     UserInput input = AudioInput.of(audioData, "audio/mp3");
 * }
 * </pre>
 */
public final class AudioInput implements UserInput {
    private final byte[] data;
    private final String mimeType;

    /**
     * Creates a new AudioInput.
     *
     * @param data
     *            The audio data (must not be null)
     * @param mimeType
     *            The MIME type (e.g., "audio/mp3", "audio/wav")
     * @throws NullPointerException
     *             if any parameter is null
     * @throws IllegalArgumentException
     *             if mimeType does not start with "audio/"
     */
    private AudioInput(byte[] data, String mimeType) {
        this.data = Objects.requireNonNull(data, "Audio data cannot be null").clone();
        Objects.requireNonNull(mimeType, "MIME type cannot be null");
        if (!mimeType.startsWith("audio/")) {
            throw new IllegalArgumentException("MIME type must start with 'audio/', got: " + mimeType);
        }
        this.mimeType = mimeType;
    }

    /**
     * Creates a new AudioInput with the given data and MIME type.
     *
     * @param data
     *            The audio data (must not be null)
     * @param mimeType
     *            The MIME type (e.g., "audio/mp3", "audio/wav")
     * @return A new AudioInput
     * @throws NullPointerException
     *             if any parameter is null
     * @throws IllegalArgumentException
     *             if mimeType does not start with "audio/"
     */
    public static AudioInput of(byte[] data, String mimeType) {
        return new AudioInput(data, mimeType);
    }

    @Override
    public InputType getType() {
        return InputType.AUDIO;
    }

    @Override
    public String asText() {
        return "[Audio: " + mimeType + ", " + data.length + " bytes]";
    }

    /**
     * Gets the audio data.
     *
     * @return A copy of the audio data (never null)
     */
    public byte[] getData() {
        return data.clone();
    }

    /**
     * Gets the MIME type.
     *
     * @return The MIME type (never null)
     */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * Gets the size of the audio data in bytes.
     *
     * @return The size in bytes
     */
    public int getSize() {
        return data.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AudioInput that = (AudioInput) o;
        return Arrays.equals(data, that.data) && mimeType.equals(that.mimeType);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mimeType);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "AudioInput{" + "mimeType='" + mimeType + '\'' + ", size=" + data.length + " bytes" + '}';
    }
}
