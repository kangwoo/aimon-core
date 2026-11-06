package at.aimon.core.agent.tool;

import java.util.Locale;
import java.util.Objects;

/**
 * Which language {@code ExecuteCode} runs model-written code in, as an agent's {@code programmatic-engine} frontmatter
 * key chooses it ({@code docs/design/tool/programmatic-tool-calling.md} §6.2).
 *
 * <p>
 * The choice is made at offer time, once per iteration, from static facts about the execution environment. A chosen
 * engine that cannot run is never replaced by another language: {@code ExecuteCode} is then not offered (decision 5).
 */
public enum ProgrammaticEngineChoice {

    /**
     * JavaScript on a host-isolated environment, Python on a sandboxed one. An environment that does not declare its
     * isolation gets neither.
     */
    AUTO,

    /** The in-JVM GraalJS engine, on any environment. */
    JAVASCRIPT,

    /** The shell engine (Python inside a sandboxed environment). */
    PYTHON;

    /**
     * Returns the spelling used in frontmatter: the constant's name in lower case.
     *
     * @return {@code "auto"}, {@code "javascript"} or {@code "python"} (never null)
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a frontmatter value, case ignored.
     *
     * @param value
     *            the value (must not be null)
     * @return the choice (never null)
     * @throws IllegalArgumentException
     *             if the value names no choice
     */
    public static ProgrammaticEngineChoice parse(String value) {
        Objects.requireNonNull(value, "value cannot be null");
        final String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ProgrammaticEngineChoice choice : values()) {
            if (choice.name().equals(normalized)) {
                return choice;
            }
        }
        throw new IllegalArgumentException(
                "unknown programmatic engine '" + value + "' (expected auto, javascript or python)");
    }
}
