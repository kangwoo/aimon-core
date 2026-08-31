package at.aimon.core.subagent.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.subagent.exception.SubagentRepositoryException;

@DisplayName("VfsSubagentRepository Tests")
class VfsSubagentRepositoryTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem;
    private VfsSubagentRepository repository;
    private String agentsDirectory;

    @BeforeEach
    void setUp() throws IOException {
        // Setup file system
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();

        // Setup repository
        agentsDirectory = ".aimon/agents";
        repository = new VfsSubagentRepository(fileSystem, agentsDirectory);

        // Create agents directory
        Path agentsPath = tempDir.resolve(agentsDirectory);
        Files.createDirectories(agentsPath);
    }

    @AfterEach
    void tearDown() {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    @Test
    @DisplayName("Should find subagent by name when file exists")
    void testFindByName_WhenFileExists_ReturnsContent() throws IOException {
        // Given: Create a subagent file
        String subagentName = "code-reviewer";
        String content = "---\nname: code-reviewer\ndescription: Reviews code\n---\nYou are a code reviewer.";
        Path subagentFile = tempDir.resolve(agentsDirectory + "/" + subagentName + ".md");
        Files.writeString(subagentFile, content);

        // When: Find by name
        Optional<String> result = repository.findByName(subagentName);

        // Then: Content is returned
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(content);
    }

    @Test
    @DisplayName("Should return empty when subagent file does not exist")
    void testFindByName_WhenFileDoesNotExist_ReturnsEmpty() {
        // When: Find non-existent subagent
        Optional<String> result = repository.findByName("non-existent");

        // Then: Empty is returned
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should throw exception when subagent name is null")
    void testFindByName_WhenNameIsNull_ThrowsException() {
        // When/Then: Null name throws exception
        assertThatThrownBy(() -> repository.findByName(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Subagent name cannot be null");
    }

    @Test
    @DisplayName("Should find all subagent names")
    void testFindAllNames_WhenMultipleFilesExist_ReturnsAllNames() throws IOException {
        // Given: Create multiple subagent files
        createSubagentFile("code-reviewer", "Code reviewer content");
        createSubagentFile("test-generator", "Test generator content");
        createSubagentFile("doc-writer", "Doc writer content");

        // When: Find all names
        List<String> names = repository.findAllNames();

        // Then: All names are returned (without .md extension)
        assertThat(names).hasSize(3).containsExactlyInAnyOrder("code-reviewer", "test-generator", "doc-writer");
    }

    @Test
    @DisplayName("Should return only .md files in findAllNames")
    void testFindAllNames_WhenNonMdFilesExist_ReturnsOnlyMdFiles() throws IOException {
        // Given: Create .md and non-.md files
        createSubagentFile("code-reviewer", "Code reviewer content");
        Path txtFile = tempDir.resolve(agentsDirectory + "/readme.txt");
        Files.writeString(txtFile, "This is a text file");

        // When: Find all names
        List<String> names = repository.findAllNames();

        // Then: Only .md file is returned
        assertThat(names).hasSize(1).containsExactly("code-reviewer");
    }

    @Test
    @DisplayName("Should return empty list when agents directory does not exist")
    void testFindAllNames_WhenDirectoryDoesNotExist_ReturnsEmptyList() {
        // Given: Repository with non-existent directory
        VfsSubagentRepository emptyRepository = new VfsSubagentRepository(fileSystem, ".aimon/nonexistent");

        // When: Find all names
        List<String> names = emptyRepository.findAllNames();

        // Then: Empty list is returned
        assertThat(names).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list when agents directory is empty")
    void testFindAllNames_WhenDirectoryIsEmpty_ReturnsEmptyList() {
        // When: Find all names in empty directory
        List<String> names = repository.findAllNames();

        // Then: Empty list is returned
        assertThat(names).isEmpty();
    }

    @Test
    @DisplayName("Should extract filename correctly from full path")
    void testFindAllNames_ExtractsFilenameFromPath() throws IOException {
        // Given: Create subagent file (list() returns full relative path)
        createSubagentFile("my-agent", "Content");

        // When: Find all names
        List<String> names = repository.findAllNames();

        // Then: Only filename without extension is returned
        assertThat(names).containsExactly("my-agent");
    }

    @Test
    @DisplayName("Should check if subagent exists")
    void testExists_WhenFileExists_ReturnsTrue() throws IOException {
        // Given: Create a subagent file
        createSubagentFile("code-reviewer", "Content");

        // When: Check existence
        boolean exists = repository.exists("code-reviewer");

        // Then: Returns true
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("Should return false when subagent does not exist")
    void testExists_WhenFileDoesNotExist_ReturnsFalse() {
        // When: Check non-existent subagent
        boolean exists = repository.exists("non-existent");

        // Then: Returns false
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("Should throw exception when exists() receives null name")
    void testExists_WhenNameIsNull_ThrowsException() {
        // When/Then: Null name throws exception
        assertThatThrownBy(() -> repository.exists(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Subagent name cannot be null");
    }

    @Test
    @DisplayName("Should handle subagent names with hyphens and underscores")
    void testFindAllNames_WithSpecialCharactersInName_ReturnsCorrectNames() throws IOException {
        // Given: Create files with special characters
        createSubagentFile("code-reviewer", "Content 1");
        createSubagentFile("test_generator", "Content 2");
        createSubagentFile("my-super_agent", "Content 3");

        // When: Find all names
        List<String> names = repository.findAllNames();

        // Then: All names with special characters are returned
        assertThat(names).hasSize(3).containsExactlyInAnyOrder("code-reviewer", "test_generator", "my-super_agent");
    }

    @Test
    @DisplayName("Should preserve newlines in file content")
    void testFindByName_PreservesNewlines() throws IOException {
        // Given: Create file with multiple lines
        String subagentName = "multi-line";
        String content = "Line 1\nLine 2\nLine 3";
        Path subagentFile = tempDir.resolve(agentsDirectory + "/" + subagentName + ".md");
        Files.writeString(subagentFile, content);

        // When: Find by name
        Optional<String> result = repository.findByName(subagentName);

        // Then: Newlines are preserved
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(content);
    }

    @Test
    @DisplayName("Should throw exception when filesystem is closed")
    void testFindByName_WhenFilesystemClosed_ThrowsException() throws IOException {
        // Given: Create a file
        String subagentName = "test-agent";
        Path subagentFile = tempDir.resolve(agentsDirectory + "/" + subagentName + ".md");
        Files.writeString(subagentFile, "Content");

        // Close filesystem to simulate closed state
        fileSystem.close();

        // When/Then: Closed filesystem throws SubagentRepositoryException wrapping IllegalStateException
        assertThatThrownBy(() -> repository.findByName(subagentName)).isInstanceOf(SubagentRepositoryException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Should build correct file path")
    void testFindByName_BuildsCorrectFilePath() throws IOException {
        // Given: Create subagent in subdirectory
        String subagentName = "test-agent";
        Path subagentFile = tempDir.resolve(agentsDirectory + "/" + subagentName + ".md");
        Files.writeString(subagentFile, "Test content");

        // When: Find by name
        Optional<String> result = repository.findByName(subagentName);

        // Then: File is found with correct path
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("Test content");
    }

    // Helper method to create subagent files
    private void createSubagentFile(String name, String content) throws IOException {
        Path subagentFile = tempDir.resolve(agentsDirectory + "/" + name + ".md");
        Files.writeString(subagentFile, content);
    }
}
