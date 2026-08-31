package at.aimon.core.agent.input;

/**
 * Represents the type of user input.
 *
 * @see UserInput
 */
public enum InputType {

    /** Text-based input. */
    TEXT,

    /** Image-based input. */
    IMAGE,

    /** Audio-based input. */
    AUDIO,

    /** File-based input with metadata. */
    FILE,

    /** Multimodal input combining multiple types. */
    MULTIMODAL
}
