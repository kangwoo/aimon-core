package at.aimon.core.subagent.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathSubagentRepositoryTest {

    @TempDir
    Path tempDir;

    private PathSubagentRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("explore.md"), "You are an explore agent.");
        Files.writeString(tempDir.resolve("review.md"), "You are a review agent.");
        repository = new PathSubagentRepository(tempDir);
    }

    @Test
    void findByName_returnsContentWhenExists() {
        final Optional<String> result = repository.findByName("explore");

        assertTrue(result.isPresent());
        assertEquals("You are an explore agent.", result.get());
    }

    @Test
    void findByName_returnsEmptyWhenNotExists() {
        final Optional<String> result = repository.findByName("nonexistent");

        assertTrue(result.isEmpty());
    }

    @Test
    void findByName_throwsOnNullName() {
        assertThrows(NullPointerException.class, () -> repository.findByName(null));
    }

    @Test
    void findAllNames_returnsAllMdFiles() {
        final List<String> names = repository.findAllNames();

        assertEquals(2, names.size());
        assertTrue(names.contains("explore"));
        assertTrue(names.contains("review"));
    }

    @Test
    void findAllNames_excludesNonMdFiles() throws IOException {
        Files.writeString(tempDir.resolve("readme.txt"), "not a subagent");

        final List<String> names = repository.findAllNames();

        assertEquals(2, names.size());
        assertFalse(names.contains("readme"));
    }

    @Test
    void findAllNames_returnsEmptyForNonexistentDirectory() {
        final PathSubagentRepository emptyRepo = new PathSubagentRepository(tempDir.resolve("nonexistent"));

        final List<String> names = emptyRepo.findAllNames();

        assertTrue(names.isEmpty());
    }

    @Test
    void findAllNames_returnsEmptyForEmptyDirectory() throws IOException {
        final Path emptyDir = Files.createDirectory(tempDir.resolve("empty"));
        final PathSubagentRepository emptyRepo = new PathSubagentRepository(emptyDir);

        final List<String> names = emptyRepo.findAllNames();

        assertTrue(names.isEmpty());
    }

    @Test
    void exists_returnsTrueWhenFileExists() {
        assertTrue(repository.exists("explore"));
    }

    @Test
    void exists_returnsFalseWhenFileDoesNotExist() {
        assertFalse(repository.exists("nonexistent"));
    }

    @Test
    void exists_throwsOnNullName() {
        assertThrows(NullPointerException.class, () -> repository.exists(null));
    }

    @Test
    void constructor_throwsOnNullBasePath() {
        assertThrows(NullPointerException.class, () -> new PathSubagentRepository(null));
    }

    @Test
    void findByName_returnsEmptyForPathTraversal() {
        final Optional<String> result = repository.findByName("../../etc/passwd");

        assertTrue(result.isEmpty());
    }

    @Test
    void exists_returnsFalseForPathTraversal() {
        assertFalse(repository.exists("../../../etc/passwd"));
    }
}
