package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

class LocalFileSystemSearchTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem createFs() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        return fs;
    }

    private void writeFile(LocalFileSystem fs, String path) {
        byte[] content = "test".getBytes();
        fs.write(path, new ByteArrayInputStream(content), content.length);
    }

    @Test
    void testSearchByExtension() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "file1.json");
        writeFile(fs, "file2.json");
        writeFile(fs, "file3.txt");

        List<String> results = fs.search(".", "*.json", 100);

        assertThat(results).hasSize(2).allMatch(p -> p.endsWith(".json"));

        fs.close();
    }

    @Test
    void testSearchByPrefix() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "report-01.csv");
        writeFile(fs, "report-02.csv");
        writeFile(fs, "data.csv");

        List<String> results = fs.search(".", "report-*", 100);

        assertThat(results).hasSize(2).allMatch(p -> p.startsWith("report-"));

        fs.close();
    }

    @Test
    void testSearchWithQuestionMark() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "report-01.csv");
        writeFile(fs, "report-02.csv");
        writeFile(fs, "report-100.csv");

        List<String> results = fs.search(".", "report-??.csv", 100);

        assertThat(results).hasSize(2).allMatch(p -> p.matches("report-\\d{2}\\.csv"));

        fs.close();
    }

    @Test
    void testSearchMaxResults() {
        LocalFileSystem fs = createFs();
        for (int i = 0; i < 10; i++) {
            writeFile(fs, "file" + i + ".txt");
        }

        List<String> results = fs.search(".", "*.txt", 3);

        assertThat(results).hasSize(3);

        fs.close();
    }

    @Test
    void testSearchInSubdirectory() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "sub/a.json");
        writeFile(fs, "sub/b.json");
        writeFile(fs, "root.json");

        List<String> results = fs.search("sub", "*.json", 100);

        assertThat(results).hasSize(2).allMatch(p -> p.startsWith("sub/"));

        fs.close();
    }

    @Test
    void testSearchReturnsRelativePaths() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "dir/sub/deep.txt");

        List<String> results = fs.search(".", "deep.txt", 100);

        assertThat(results).containsExactly("dir/sub/deep.txt");

        fs.close();
    }

    @Test
    void testSearchMatchesFilesOnly() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "docs/readme.txt");
        fs.createDirectory("docs/txtdir");

        List<String> results = fs.search(".", "*.txt", 100);

        // Should not include directories
        assertThat(results).containsExactly("docs/readme.txt");

        fs.close();
    }

    @Test
    void testSearchPatternSanitization() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "file.json");

        // Blocked characters should be stripped: {, }, [, ], /, \
        List<String> results = fs.search(".", "*.{json}", 100);

        // After sanitization "*.{json}" becomes "*.json"
        assertThat(results).containsExactly("file.json");

        fs.close();
    }

    @Test
    void testSearchPatternDoubleStarCollapsed() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "anything.txt");

        // "**" should collapse to "*"
        List<String> results = fs.search(".", "**.txt", 100);

        assertThat(results).containsExactly("anything.txt");

        fs.close();
    }

    @Test
    void testSearchEmptyPatternAfterSanitization() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "file.txt");

        // Pattern of only blocked characters becomes empty
        List<String> results = fs.search(".", "/\\{}[]", 100);

        assertThat(results).isEmpty();

        fs.close();
    }

    @Test
    void testSearchUnicodeFilenames() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "설정파일.yml");
        writeFile(fs, "설정파일2.yml");
        writeFile(fs, "config.yml");

        List<String> results = fs.search(".", "설정*.yml", 100);

        assertThat(results).hasSize(2).allMatch(p -> p.startsWith("설정"));

        fs.close();
    }

    @Test
    void testSearchNonExistentDirectory() {
        LocalFileSystem fs = createFs();

        assertThatThrownBy(() -> fs.search("nonexistent", "*.txt", 10)).isInstanceOf(FileNotFoundException.class);

        fs.close();
    }

    @Test
    void testSearchRejectsNullDirectory() {
        LocalFileSystem fs = createFs();

        assertThatThrownBy(() -> fs.search(null, "*.txt", 10)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Directory cannot be null");

        fs.close();
    }

    @Test
    void testSearchRejectsNullPattern() {
        LocalFileSystem fs = createFs();

        assertThatThrownBy(() -> fs.search(".", null, 10)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Pattern cannot be null");

        fs.close();
    }

    @Test
    void testSearchRejectsInvalidMaxResults() {
        LocalFileSystem fs = createFs();

        assertThatThrownBy(() -> fs.search(".", "*.txt", 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResults");

        fs.close();
    }

    @Test
    void testSearchRejectsInvalidPath() {
        LocalFileSystem fs = createFs();

        assertThatThrownBy(() -> fs.search("../../../etc", "*.txt", 10)).isInstanceOf(InvalidPathException.class);

        fs.close();
    }

    @Test
    void testSearchFailsWhenNotInitialized() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);

        assertThatThrownBy(() -> fs.search(".", "*.txt", 10)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        fs.close();
    }

    @Test
    void testSearchFailsWhenClosed() {
        LocalFileSystem fs = createFs();
        fs.close();

        assertThatThrownBy(() -> fs.search(".", "*.txt", 10)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
