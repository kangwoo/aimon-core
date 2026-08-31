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

class LocalFileSystemCopyTest {

    @TempDir
    Path tempDir;

    @Test
    void testCopyCreatesExactDuplicate() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "Original content";
        fs.write("source.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        // Copy file
        fs.copy("source.txt", "destination.txt", false);

        // Verify both files exist
        assertThat(fs.exists("source.txt")).isTrue();
        assertThat(fs.exists("destination.txt")).isTrue();

        // Verify content is identical
        try (InputStream source = fs.read("source.txt"); InputStream dest = fs.read("destination.txt")) {
            String sourceContent = new String(source.readAllBytes());
            String destContent = new String(dest.readAllBytes());
            assertThat(destContent).isEqualTo(sourceContent);
            assertThat(destContent).isEqualTo(content);
        }

        fs.close();
    }

    @Test
    void testCopyToNestedDirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "Test content";
        fs.write("source.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        // Copy to nested directory (auto-creates)
        fs.copy("source.txt", "level1/level2/destination.txt", false);

        assertThat(fs.exists("source.txt")).isTrue();
        assertThat(fs.exists("level1/level2/destination.txt")).isTrue();

        try (InputStream is = fs.read("level1/level2/destination.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(content);
        }

        fs.close();
    }

    @Test
    void testCopyWithOverwriteFalseFailsIfDestinationExists() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create source and destination files
        fs.write("source.txt", new ByteArrayInputStream("source".getBytes()), 6);
        fs.write("destination.txt", new ByteArrayInputStream("dest".getBytes()), 4);

        // Copy with overwrite=false should fail
        assertThatThrownBy(() -> fs.copy("source.txt", "destination.txt", false))
                .isInstanceOf(FileAlreadyExistsException.class).hasMessageContaining("destination.txt");

        // Original destination should be unchanged
        try (InputStream is = fs.read("destination.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo("dest");
        }

        fs.close();
    }

    @Test
    void testCopyWithOverwriteTrueReplacesDestination() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create source and destination files
        String sourceContent = "source content";
        String destContent = "old destination";
        fs.write("source.txt", new ByteArrayInputStream(sourceContent.getBytes()), sourceContent.length());
        fs.write("destination.txt", new ByteArrayInputStream(destContent.getBytes()), destContent.length());

        // Copy with overwrite=true should succeed
        fs.copy("source.txt", "destination.txt", true);

        // Destination should now have source content
        try (InputStream is = fs.read("destination.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(sourceContent);
        }

        // Source should still exist and be unchanged
        try (InputStream is = fs.read("source.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(sourceContent);
        }

        fs.close();
    }

    @Test
    void testCopyLargeFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create 5MB file
        byte[] largeData = new byte[5 * 1024 * 1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }
        fs.write("large.bin", new ByteArrayInputStream(largeData), largeData.length);

        // Copy large file
        long start = System.currentTimeMillis();
        fs.copy("large.bin", "large-copy.bin", false);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("Copied 5MB file in " + elapsed + "ms");

        // Verify copy
        assertThat(fs.exists("large-copy.bin")).isTrue();
        try (InputStream is = fs.read("large-copy.bin")) {
            byte[] copiedData = is.readAllBytes();
            assertThat(copiedData).isEqualTo(largeData);
        }

        fs.close();
    }

    @Test
    void testCopyThrowsForNonExistentSource() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.copy("nonexistent.txt", "destination.txt", false))
                .isInstanceOf(FileNotFoundException.class).hasMessageContaining("nonexistent.txt");

        fs.close();
    }

    @Test
    void testCopyRejectsNullSourcePath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.copy(null, "destination.txt", false)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Source path cannot be null");

        fs.close();
    }

    @Test
    void testCopyRejectsNullDestinationPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.copy("source.txt", null, false)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Destination path cannot be null");

        fs.close();
    }

    @Test
    void testCopyRejectsInvalidSourcePath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.copy("../../../etc/passwd", "destination.txt", false))
                .isInstanceOf(InvalidPathException.class).hasMessageContaining("Path traversal detected");

        fs.close();
    }

    @Test
    void testCopyRejectsInvalidDestinationPath() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.write("source.txt", new ByteArrayInputStream("test".getBytes()), 4);

        assertThatThrownBy(() -> fs.copy("source.txt", "../../../tmp/hacked.txt", false))
                .isInstanceOf(InvalidPathException.class).hasMessageContaining("Path traversal detected");

        fs.close();
    }

    @Test
    void testCopyPreservesFileMetadata() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "Test content for metadata";
        fs.write("source.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        fs.copy("source.txt", "destination.txt", false);

        // Both files should have same size
        assertThat(fs.getMetadata("source.txt").getSize()).isEqualTo(fs.getMetadata("destination.txt").getSize());

        fs.close();
    }
}
