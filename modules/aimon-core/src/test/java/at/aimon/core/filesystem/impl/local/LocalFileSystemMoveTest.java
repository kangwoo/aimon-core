package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

class LocalFileSystemMoveTest {

    @TempDir
    Path tempDir;

    @Test
    void testMoveTransfersFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "Original content";
        fs.write("source.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        // Move file
        fs.move("source.txt", "destination.txt", false);

        // Source should not exist, destination should exist
        assertThat(fs.exists("source.txt")).isFalse();
        assertThat(fs.exists("destination.txt")).isTrue();

        // Verify content
        try (InputStream is = fs.read("destination.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(content);
        }

        fs.close();
    }

    @Test
    void testMoveToNestedDirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "Test content";
        fs.write("source.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        // Move to nested directory
        fs.move("source.txt", "level1/level2/destination.txt", false);

        assertThat(fs.exists("source.txt")).isFalse();
        assertThat(fs.exists("level1/level2/destination.txt")).isTrue();

        try (InputStream is = fs.read("level1/level2/destination.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(content);
        }

        fs.close();
    }

    @Test
    void testMoveWithOverwriteFalseFailsIfDestinationExists() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create source and destination files
        fs.write("source.txt", new ByteArrayInputStream("source".getBytes()), 6);
        fs.write("destination.txt", new ByteArrayInputStream("dest".getBytes()), 4);

        // Move with overwrite=false should fail
        assertThatThrownBy(() -> fs.move("source.txt", "destination.txt", false))
                .isInstanceOf(FileAlreadyExistsException.class).hasMessageContaining("destination.txt");

        // Both files should still exist
        assertThat(fs.exists("source.txt")).isTrue();
        assertThat(fs.exists("destination.txt")).isTrue();

        fs.close();
    }

    @Test
    void testMoveWithOverwriteTrueReplacesDestination() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create source and destination files
        String sourceContent = "source content";
        String destContent = "old destination";
        fs.write("source.txt", new ByteArrayInputStream(sourceContent.getBytes()), sourceContent.length());
        fs.write("destination.txt", new ByteArrayInputStream(destContent.getBytes()), destContent.length());

        // Move with overwrite=true should succeed
        fs.move("source.txt", "destination.txt", true);

        // Source should not exist, destination should have source content
        assertThat(fs.exists("source.txt")).isFalse();
        assertThat(fs.exists("destination.txt")).isTrue();

        try (InputStream is = fs.read("destination.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(sourceContent);
        }

        fs.close();
    }

    @Test
    void testMoveLargeFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create 5MB file
        byte[] largeData = new byte[5 * 1024 * 1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }
        fs.write("large.bin", new ByteArrayInputStream(largeData), largeData.length);

        // Move large file
        long start = System.currentTimeMillis();
        fs.move("large.bin", "large-moved.bin", false);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("Moved 5MB file in " + elapsed + "ms");

        // Verify move (should be very fast due to atomic move)
        assertThat(fs.exists("large.bin")).isFalse();
        assertThat(fs.exists("large-moved.bin")).isTrue();

        try (InputStream is = fs.read("large-moved.bin")) {
            byte[] movedData = is.readAllBytes();
            assertThat(movedData).isEqualTo(largeData);
        }

        // Move should be much faster than copy (< 100ms for 5MB)
        assertThat(elapsed).isLessThan(100);

        fs.close();
    }

    @Test
    void testMoveThrowsForNonExistentSource() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.move("nonexistent.txt", "destination.txt", false))
                .isInstanceOf(FileNotFoundException.class).hasMessageContaining("nonexistent.txt");

        fs.close();
    }

    @Test
    void testMoveRejectsNullSourcePath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.move(null, "destination.txt", false)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Source path cannot be null");

        fs.close();
    }

    @Test
    void testMoveRejectsNullDestinationPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.move("source.txt", null, false)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Destination path cannot be null");

        fs.close();
    }

    @Test
    void testMoveRejectsInvalidSourcePath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.move("../../../etc/passwd", "destination.txt", false))
                .isInstanceOf(InvalidPathException.class).hasMessageContaining("Path traversal detected");

        fs.close();
    }

    @Test
    void testMoveRejectsInvalidDestinationPath() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.write("source.txt", new ByteArrayInputStream("test".getBytes()), 4);

        assertThatThrownBy(() -> fs.move("source.txt", "../../../tmp/hacked.txt", false))
                .isInstanceOf(InvalidPathException.class).hasMessageContaining("Path traversal detected");

        fs.close();
    }

    @Test
    void testMoveAcrossDirectories() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "Move across directories";
        fs.write("dir1/source.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        fs.move("dir1/source.txt", "dir2/destination.txt", false);

        assertThat(fs.exists("dir1/source.txt")).isFalse();
        assertThat(fs.exists("dir2/destination.txt")).isTrue();

        try (InputStream is = fs.read("dir2/destination.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(content);
        }

        fs.close();
    }

    @Test
    void testMoveIsAtomic() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "Atomic move test";
        fs.write("source.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        // Move should be atomic - either fully succeeds or fails
        fs.move("source.txt", "destination.txt", false);

        // After successful move, source should not exist
        assertThat(fs.exists("source.txt")).isFalse();
        assertThat(fs.exists("destination.txt")).isTrue();

        fs.close();
    }
}
