package at.aimon.filesystem.filesystems.s3;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.filesystem.core.s3.S3FileSystem;

@Tag("docker")
class S3FileSystemMoveTest {

    private S3FileSystem fs;

    @BeforeEach
    void setUp() {
        S3TestSupport.cleanBucket();
        fs = S3TestSupport.createAndInitialize();
    }

    @AfterEach
    void tearDown() {
        if (fs != null) {
            fs.close();
        }
    }

    @Test
    void testMoveTransfersFile() throws Exception {
        String content = "move me";
        fs.write("source.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        fs.move("source.txt", "dest.txt", false);

        assertThat(fs.exists("source.txt")).isFalse();
        assertThat(fs.exists("dest.txt")).isTrue();

        try (InputStream in = fs.read("dest.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo(content);
        }
    }

    @Test
    void testMoveToNestedDirectory() throws Exception {
        String content = "nested move";
        fs.write("source.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        fs.move("source.txt", "a/b/dest.txt", false);

        assertThat(fs.exists("source.txt")).isFalse();

        try (InputStream in = fs.read("a/b/dest.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo(content);
        }
    }

    @Test
    void testMoveOverwriteFalseThrows() {
        fs.write("source.txt", new ByteArrayInputStream("src".getBytes()), 3);
        fs.write("dest.txt", new ByteArrayInputStream("dst".getBytes()), 3);

        assertThatThrownBy(() -> fs.move("source.txt", "dest.txt", false))
                .isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void testMoveOverwriteTrueReplaces() throws Exception {
        fs.write("source.txt", new ByteArrayInputStream("new content".getBytes()), 11);
        fs.write("dest.txt", new ByteArrayInputStream("old".getBytes()), 3);

        fs.move("source.txt", "dest.txt", true);

        assertThat(fs.exists("source.txt")).isFalse();

        try (InputStream in = fs.read("dest.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo("new content");
        }
    }

    @Test
    void testMoveNonExistentSource() {
        assertThatThrownBy(() -> fs.move("nonexistent.txt", "dest.txt", false))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void testMoveRejectsNullSource() {
        assertThatThrownBy(() -> fs.move(null, "dest.txt", false)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Source path cannot be null");
    }

    @Test
    void testMoveRejectsNullDestination() {
        assertThatThrownBy(() -> fs.move("source.txt", null, false)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Destination path cannot be null");
    }
}
