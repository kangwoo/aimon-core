package at.aimon.core.tools.todo;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link InMemoryTodoRepository}. */
class InMemoryTodoRepositoryTest {

    private InMemoryTodoRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTodoRepository();
    }

    @Test
    void test_Save_ValidTodos_TodosSaved() {
        // Arrange
        List<Todo> todos = List.of(new Todo("Run tests", TodoStatus.PENDING, "Running tests"));

        // Act
        repository.save("context1", todos);

        // Assert
        Optional<List<Todo>> retrieved = repository.get("context1");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).hasSize(1);
        assertThat(retrieved.get().get(0).getContent()).isEqualTo("Run tests");
    }

    @Test
    void test_Save_NullContextId_ThrowsException() {
        // Arrange
        List<Todo> todos = List.of(new Todo("Run tests", TodoStatus.PENDING, "Running tests"));

        // Act & Assert
        assertThatThrownBy(() -> repository.save(null, todos)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Context ID cannot be null");
    }

    @Test
    void test_Save_NullTodos_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> repository.save("context1", null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Todos cannot be null");
    }

    @Test
    void test_Save_ExistingContext_TodosReplaced() {
        // Arrange
        List<Todo> todos1 = List.of(new Todo("Run tests", TodoStatus.PENDING, "Running tests"));
        List<Todo> todos2 = List.of(new Todo("Fix bugs", TodoStatus.IN_PROGRESS, "Fixing bugs"));

        // Act
        repository.save("context1", todos1);
        repository.save("context1", todos2);

        // Assert
        Optional<List<Todo>> retrieved = repository.get("context1");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).hasSize(1);
        assertThat(retrieved.get().get(0).getContent()).isEqualTo("Fix bugs");
    }

    @Test
    void test_Save_DefensiveCopy_ExternalModificationDoesNotAffectStorage() {
        // Arrange
        List<Todo> todos = new ArrayList<>();
        todos.add(new Todo("Run tests", TodoStatus.PENDING, "Running tests"));
        repository.save("context1", todos);

        // Act - Modify external list
        todos.add(new Todo("Fix bugs", TodoStatus.PENDING, "Fixing bugs"));

        // Assert - Repository is unaffected
        Optional<List<Todo>> retrieved = repository.get("context1");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).hasSize(1);
    }

    @Test
    void test_Get_ExistingContext_ReturnsTodos() {
        // Arrange
        List<Todo> todos = List.of(new Todo("Run tests", TodoStatus.PENDING, "Running tests"));
        repository.save("context1", todos);

        // Act
        Optional<List<Todo>> retrieved = repository.get("context1");

        // Assert
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).hasSize(1);
    }

    @Test
    void test_Get_NonExistingContext_ReturnsEmpty() {
        // Act
        Optional<List<Todo>> retrieved = repository.get("nonexistent");

        // Assert
        assertThat(retrieved).isEmpty();
    }

    @Test
    void test_Get_NullContextId_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> repository.get(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Context ID cannot be null");
    }

    @Test
    void test_Get_DefensiveCopy_ExternalModificationDoesNotAffectStorage() {
        // Arrange
        List<Todo> todos = List.of(new Todo("Run tests", TodoStatus.PENDING, "Running tests"));
        repository.save("context1", todos);

        // Act - Get and modify returned list
        Optional<List<Todo>> retrieved = repository.get("context1");
        assertThat(retrieved).isPresent();
        List<Todo> modifiedList = retrieved.get();
        modifiedList.clear();

        // Assert - Repository is unaffected
        Optional<List<Todo>> retrievedAgain = repository.get("context1");
        assertThat(retrievedAgain).isPresent();
        assertThat(retrievedAgain.get()).hasSize(1);
    }

    @Test
    void test_Remove_ExistingContext_TodosRemoved() {
        // Arrange
        List<Todo> todos = List.of(new Todo("Run tests", TodoStatus.PENDING, "Running tests"));
        repository.save("context1", todos);

        // Act
        repository.remove("context1");

        // Assert
        Optional<List<Todo>> retrieved = repository.get("context1");
        assertThat(retrieved).isEmpty();
    }

    @Test
    void test_Remove_NonExistingContext_NoError() {
        // Act & Assert - Should not throw
        assertThatCode(() -> repository.remove("nonexistent")).doesNotThrowAnyException();
    }

    @Test
    void test_Remove_NullContextId_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> repository.remove(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Context ID cannot be null");
    }

    @Test
    void test_Exists_ExistingContext_ReturnsTrue() {
        // Arrange
        List<Todo> todos = List.of(new Todo("Run tests", TodoStatus.PENDING, "Running tests"));
        repository.save("context1", todos);

        // Act & Assert
        assertThat(repository.exists("context1")).isTrue();
    }

    @Test
    void test_Exists_NonExistingContext_ReturnsFalse() {
        // Act & Assert
        assertThat(repository.exists("nonexistent")).isFalse();
    }

    @Test
    void test_Clear_MultipleContexts_AllRemoved() {
        // Arrange
        List<Todo> todos = List.of(new Todo("Run tests", TodoStatus.PENDING, "Running tests"));
        repository.save("context1", todos);
        repository.save("context2", todos);

        // Act
        repository.clear();

        // Assert
        assertThat(repository.get("context1")).isEmpty();
        assertThat(repository.get("context2")).isEmpty();
        assertThat(repository.size()).isZero();
    }

    @Test
    void test_Size_MultipleContexts_ReturnsCorrectCount() {
        // Arrange
        List<Todo> todos = List.of(new Todo("Run tests", TodoStatus.PENDING, "Running tests"));

        // Act & Assert
        assertThat(repository.size()).isZero();

        repository.save("context1", todos);
        assertThat(repository.size()).isEqualTo(1);

        repository.save("context2", todos);
        assertThat(repository.size()).isEqualTo(2);

        repository.remove("context1");
        assertThat(repository.size()).isEqualTo(1);
    }
}
