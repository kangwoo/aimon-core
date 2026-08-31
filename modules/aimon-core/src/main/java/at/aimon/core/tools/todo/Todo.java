package at.aimon.core.tools.todo;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a single todo item with status tracking.
 *
 * <p>
 * Immutable value object that contains:
 *
 * <ul>
 * <li>content: Imperative form of the task (e.g., "Run tests")
 * <li>status: Current status (pending, in_progress, or completed)
 * <li>activeForm: Present continuous form (e.g., "Running tests")
 * </ul>
 *
 * <p>
 * This class follows the Value Object pattern - it is immutable and thread-safe.
 *
 * @see TodoStatus
 */
public final class Todo {
    private final String content;
    private final TodoStatus status;
    private final String activeForm;

    /**
     * Creates a new Todo.
     *
     * @param content
     *            The imperative form of the task (must not be null or blank)
     * @param status
     *            The status (must not be null)
     * @param activeForm
     *            The present continuous form (must not be null or blank)
     * @throws NullPointerException
     *             if any parameter is null
     * @throws IllegalArgumentException
     *             if content or activeForm is blank
     */
    @JsonCreator
    public Todo(@JsonProperty("content") String content, @JsonProperty("status") TodoStatus status,
            @JsonProperty("activeForm") String activeForm) {
        Objects.requireNonNull(content, "Content cannot be null");
        Objects.requireNonNull(status, "Status cannot be null");
        Objects.requireNonNull(activeForm, "Active form cannot be null");

        if (content.isBlank()) {
            throw new IllegalArgumentException("Content cannot be blank");
        }
        if (activeForm.isBlank()) {
            throw new IllegalArgumentException("Active form cannot be blank");
        }

        this.content = content;
        this.status = status;
        this.activeForm = activeForm;
    }

    /**
     * Gets the imperative form of the task.
     *
     * @return The content (never null or blank)
     */
    public String getContent() {
        return content;
    }

    /**
     * Gets the status.
     *
     * @return The status (never null)
     */
    public TodoStatus getStatus() {
        return status;
    }

    /**
     * Gets the present continuous form.
     *
     * @return The active form (never null or blank)
     */
    public String getActiveForm() {
        return activeForm;
    }

    /**
     * Creates a new Todo with a different status.
     *
     * @param newStatus
     *            The new status (must not be null)
     * @return A new Todo with the updated status
     * @throws NullPointerException
     *             if newStatus is null
     */
    public Todo withStatus(TodoStatus newStatus) {
        Objects.requireNonNull(newStatus, "New status cannot be null");
        return new Todo(content, newStatus, activeForm);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Todo todo = (Todo) o;
        return content.equals(todo.content) && status == todo.status && activeForm.equals(todo.activeForm);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, status, activeForm);
    }

    @Override
    public String toString() {
        return "Todo{" + "content='" + content + '\'' + ", status=" + status + ", activeForm='" + activeForm + '\''
                + '}';
    }
}
