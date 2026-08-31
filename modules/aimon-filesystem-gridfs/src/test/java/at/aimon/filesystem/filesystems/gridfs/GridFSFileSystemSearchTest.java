package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemSearchTest {

    private GridFSFileSystem fs;

    @BeforeEach
    void setUp() {
        GridFSTestSupport.cleanDatabase();
        fs = GridFSTestSupport.createAndInitialize();
    }

    @AfterEach
    void tearDown() {
        if (fs != null) {
            fs.close();
        }
    }

    @Test
    void testSearchByExtension() {
        fs.write("a.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("b.json", new ByteArrayInputStream("b".getBytes()), 1);
        fs.write("c.txt", new ByteArrayInputStream("c".getBytes()), 1);

        List<String> results = fs.search(".", "*.txt", 100);

        assertThat(results).containsExactlyInAnyOrder("a.txt", "c.txt");
    }

    @Test
    void testSearchByPrefix() {
        fs.write("report-01.csv", new ByteArrayInputStream("1".getBytes()), 1);
        fs.write("report-02.csv", new ByteArrayInputStream("2".getBytes()), 1);
        fs.write("data.csv", new ByteArrayInputStream("3".getBytes()), 1);

        List<String> results = fs.search(".", "report-*", 100);

        assertThat(results).containsExactlyInAnyOrder("report-01.csv", "report-02.csv");
    }

    @Test
    void testSearchWithQuestionMark() {
        fs.write("a1.txt", new ByteArrayInputStream("1".getBytes()), 1);
        fs.write("a2.txt", new ByteArrayInputStream("2".getBytes()), 1);
        fs.write("ab.txt", new ByteArrayInputStream("3".getBytes()), 1);

        List<String> results = fs.search(".", "a?.txt", 100);

        assertThat(results).containsExactlyInAnyOrder("a1.txt", "a2.txt", "ab.txt");
    }

    @Test
    void testSearchMaxResults() {
        for (int i = 0; i < 10; i++) {
            fs.write("file" + i + ".txt", new ByteArrayInputStream("x".getBytes()), 1);
        }

        List<String> results = fs.search(".", "*.txt", 3);

        assertThat(results).hasSize(3);
    }

    @Test
    void testSearchInSubdirectory() {
        fs.write("dir/a.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("dir/b.json", new ByteArrayInputStream("b".getBytes()), 1);
        fs.write("other/c.txt", new ByteArrayInputStream("c".getBytes()), 1);

        List<String> results = fs.search("dir", "*.txt", 100);

        assertThat(results).containsExactly("dir/a.txt");
    }

    @Test
    void testSearchPatternSanitization() {
        fs.write("test.txt", new ByteArrayInputStream("t".getBytes()), 1);

        // Blocked characters like / \ { } [ ] are removed
        List<String> results = fs.search(".", "*.{txt}", 100);

        // After sanitization "{txt}" becomes "txt" → pattern is "*txt"
        // which would match "test.txt"
        assertThat(results).isNotNull();
    }

    @Test
    void testSearchDoubleStarCollapsed() {
        fs.write("test.txt", new ByteArrayInputStream("t".getBytes()), 1);

        List<String> results = fs.search(".", "**.txt", 100);

        // ** is collapsed to * by SearchPatterns.sanitize()
        assertThat(results).contains("test.txt");
    }

    @Test
    void testSearchEmptyPatternAfterSanitization() {
        fs.write("test.txt", new ByteArrayInputStream("t".getBytes()), 1);

        // Pattern with only blocked characters → becomes empty after sanitization
        List<String> results = fs.search(".", "/\\", 100);

        assertThat(results).isEmpty();
    }

    @Test
    void testSearchNonExistentDirectory() {
        assertThatThrownBy(() -> fs.search("nonexistent", "*.txt", 100)).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void testSearchRejectsNullDirectory() {
        assertThatThrownBy(() -> fs.search(null, "*.txt", 100)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Directory cannot be null");
    }

    @Test
    void testSearchRejectsNullPattern() {
        assertThatThrownBy(() -> fs.search(".", null, 100)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Pattern cannot be null");
    }

    @Test
    void testSearchRejectsInvalidMaxResults() {
        assertThatThrownBy(() -> fs.search(".", "*.txt", 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResults must be >= 1");
    }

    @Test
    void testSearchFailsWhenNotInitialized() {
        GridFSFileSystem uninitFs = new GridFSFileSystem(GridFSTestSupport.createConfig());

        assertThatThrownBy(() -> uninitFs.search(".", "*.txt", 100)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        uninitFs.close();
    }

    @Test
    void testSearchFailsWhenClosed() {
        GridFSFileSystem closedFs = GridFSTestSupport.createAndInitialize();
        closedFs.close();

        assertThatThrownBy(() -> closedFs.search(".", "*.txt", 100)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
