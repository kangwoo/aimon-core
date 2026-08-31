package at.aimon.core.tools.todo;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Represents the status of a todo item.
 *
 * <p>
 * Three possible states:
 *
 * <ul>
 * <li>PENDING: Task not yet started
 * <li>IN_PROGRESS: Currently being worked on
 * <li>COMPLETED: Task fully finished
 * </ul>
 *
 * <p>
 * Uses Value Object pattern for extensibility (OCP). JSON serialization uses lowercase with underscores (pending,
 * in_progress, completed).
 */
public enum TodoStatus {
    /** Task not yet started. */
    PENDING("pending"),

    /** Currently being worked on. Only ONE task should have this status at any time. */
    IN_PROGRESS("in_progress"),

    /** Task fully finished. */
    COMPLETED("completed");

    /**
     * Creates a TodoStatus from its string representation.
     *
     * @param value
     *            The status value (e.g., "pending", "in_progress", "completed")
     * @return The corresponding TodoStatus
     * @throws IllegalArgumentException
     *             if value is not a valid status
     */
    @JsonCreator
    public static TodoStatus fromValue(String value) {
        return Arrays.stream(values()).filter(status -> status.value.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid status: " + value + ". Must be one of: pending, in_progress, completed"));
    }

    private final String value;

    TodoStatus(String value) {
        this.value = value;
    }

    /**
     * Gets the JSON/string representation of this status.
     *
     * @return The status value (e.g., "pending", "in_progress", "completed")
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
