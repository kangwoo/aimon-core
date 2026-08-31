package at.aimon.core.agent;

/**
 * Core constants used throughout the Aimon agent system.
 *
 * <p>
 * This class contains commonly used string literals and other constant values that are shared across multiple
 * components.
 *
 * <p>
 * All constants are public, static, and final. The class cannot be instantiated.
 */
public final class Constants {

    /** Unix-style newline character for consistent text formatting across platforms. */
    public static final String NEWLINE = "\n";

    /** Double newline for paragraph separation. */
    public static final String DOUBLE_NEWLINE = "\n\n";

    private Constants() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
