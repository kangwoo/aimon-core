package at.aimon.core.tools.todo;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Tests for {@link Todo}. */
class TodoTest {

    @Test
    void test_Constructor_ValidInput_TodoCreated() {
        // Arrange & Act
        Todo todo = new Todo("Run tests", TodoStatus.PENDING, "Running tests");

        // Assert
        assertThat(todo.getContent()).isEqualTo("Run tests");
        assertThat(todo.getStatus()).isEqualTo(TodoStatus.PENDING);
        assertThat(todo.getActiveForm()).isEqualTo("Running tests");
    }

    @Test
    void test_Constructor_NullContent_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> new Todo(null, TodoStatus.PENDING, "Running tests"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Content cannot be null");
    }

    @Test
    void test_Constructor_NullStatus_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> new Todo("Run tests", null, "Running tests")).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Status cannot be null");
    }

    @Test
    void test_Constructor_NullActiveForm_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> new Todo("Run tests", TodoStatus.PENDING, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Active form cannot be null");
    }

    @Test
    void test_Constructor_BlankContent_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> new Todo("  ", TodoStatus.PENDING, "Running tests"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Content cannot be blank");
    }

    @Test
    void test_Constructor_BlankActiveForm_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> new Todo("Run tests", TodoStatus.PENDING, "  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Active form cannot be blank");
    }

    @Test
    void test_WithStatus_ValidStatus_NewTodoCreated() {
        // Arrange
        Todo original = new Todo("Run tests", TodoStatus.PENDING, "Running tests");

        // Act
        Todo updated = original.withStatus(TodoStatus.IN_PROGRESS);

        // Assert
        assertThat(updated.getContent()).isEqualTo("Run tests");
        assertThat(updated.getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(updated.getActiveForm()).isEqualTo("Running tests");
        assertThat(original.getStatus()).isEqualTo(TodoStatus.PENDING); // Original unchanged
    }

    @Test
    void test_WithStatus_NullStatus_ThrowsException() {
        // Arrange
        Todo todo = new Todo("Run tests", TodoStatus.PENDING, "Running tests");

        // Act & Assert
        assertThatThrownBy(() -> todo.withStatus(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("New status cannot be null");
    }

    @Test
    void test_Equals_SameTodos_ReturnsTrue() {
        // Arrange
        Todo todo1 = new Todo("Run tests", TodoStatus.PENDING, "Running tests");
        Todo todo2 = new Todo("Run tests", TodoStatus.PENDING, "Running tests");

        // Act & Assert
        assertThat(todo1).isEqualTo(todo2);
        assertThat(todo1.hashCode()).isEqualTo(todo2.hashCode());
    }

    @Test
    void test_Equals_DifferentStatus_ReturnsFalse() {
        // Arrange
        Todo todo1 = new Todo("Run tests", TodoStatus.PENDING, "Running tests");
        Todo todo2 = new Todo("Run tests", TodoStatus.IN_PROGRESS, "Running tests");

        // Act & Assert
        assertThat(todo1).isNotEqualTo(todo2);
    }

    @Test
    void test_ToString_ValidTodo_ReturnsFormattedString() {
        // Arrange
        Todo todo = new Todo("Run tests", TodoStatus.PENDING, "Running tests");

        // Act
        String result = todo.toString();

        // Assert
        assertThat(result).contains("Run tests");
        assertThat(result).contains("pending");
        assertThat(result).contains("Running tests");
    }
}
