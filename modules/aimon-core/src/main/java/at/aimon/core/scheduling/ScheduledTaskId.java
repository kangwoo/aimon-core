/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing a scheduled task identifier.
 *
 * <p>
 * Encapsulates validation logic and ensures type safety for scheduled task IDs. Instances are immutable and can be
 * safely used as map keys.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     ScheduledTaskId id = ScheduledTaskId.generate();
 *     ScheduledTask task = ScheduledTask.builder().id(id).build();
 *
 *     // Validation happens at construction time
 *     new ScheduledTaskId(""); // throws IllegalArgumentException
 *     new ScheduledTaskId(null); // throws NullPointerException
 * }
 * </pre>
 */
public final class ScheduledTaskId {

    private final String value;

    /**
     * Creates a new scheduled task ID with validation.
     *
     * @param value
     *            the scheduled task identifier value (must not be null or blank)
     * @throws NullPointerException
     *             if value is null
     * @throws IllegalArgumentException
     *             if value is blank
     */
    public ScheduledTaskId(String value) {
        Objects.requireNonNull(value, "Task ID cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Task ID cannot be blank");
        }
        this.value = value;
    }

    /**
     * Creates a new ScheduledTaskId.
     *
     * @param value
     *            The task ID value
     * @return A new ScheduledTaskId with the given value
     */
    public static ScheduledTaskId of(String value) {
        return new ScheduledTaskId(value);
    }

    /**
     * Generates a new random ScheduledTaskId.
     *
     * @return A new ScheduledTaskId with a random UUID value
     */
    public static ScheduledTaskId generate() {
        return new ScheduledTaskId(UUID.randomUUID().toString());
    }

    /**
     * Returns the task ID value.
     *
     * @return the identifier value (never null or blank)
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ScheduledTaskId that = (ScheduledTaskId) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
