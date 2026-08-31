package at.aimon.core.tools.todo;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Tests for {@link TodoStatus}. */
class TodoStatusTest {

    @Test
    void test_GetValue_AllStatuses_ReturnsCorrectValues() {
        // Assert
        assertThat(TodoStatus.PENDING.getValue()).isEqualTo("pending");
        assertThat(TodoStatus.IN_PROGRESS.getValue()).isEqualTo("in_progress");
        assertThat(TodoStatus.COMPLETED.getValue()).isEqualTo("completed");
    }

    @Test
    void test_FromValue_ValidValues_ReturnsCorrectStatus() {
        // Act & Assert
        assertThat(TodoStatus.fromValue("pending")).isEqualTo(TodoStatus.PENDING);
        assertThat(TodoStatus.fromValue("in_progress")).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(TodoStatus.fromValue("completed")).isEqualTo(TodoStatus.COMPLETED);
    }

    @Test
    void test_FromValue_InvalidValue_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> TodoStatus.fromValue("invalid")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid status: invalid")
                .hasMessageContaining("Must be one of: pending, in_progress, completed");
    }

    @Test
    void test_FromValue_NullValue_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> TodoStatus.fromValue(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid status: null");
    }

    @Test
    void test_ToString_AllStatuses_ReturnsValue() {
        // Assert
        assertThat(TodoStatus.PENDING.toString()).isEqualTo("pending");
        assertThat(TodoStatus.IN_PROGRESS.toString()).isEqualTo("in_progress");
        assertThat(TodoStatus.COMPLETED.toString()).isEqualTo("completed");
    }
}
