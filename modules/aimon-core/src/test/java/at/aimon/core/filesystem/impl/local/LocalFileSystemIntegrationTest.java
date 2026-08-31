package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.BackendType;

class LocalFileSystemIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void testFullWriteReadDeleteCycle() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);

        // Test getStatus before initialization
        BackendStatus status = fs.getStatus();
        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.UNKNOWN);

        // Initialize
        fs.initialize();

        // Test getStatus after initialization
        status = fs.getStatus();
        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.CONNECTED);
        assertThat(status.getType()).isEqualTo(BackendType.LOCAL);
        assertThat(status.isAvailable()).isTrue();

        // Step 1: Write file
        String content = "Integration test content";
        fs.write("integration-test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        // Step 2: Verify file exists
        assertThat(fs.exists("integration-test.txt")).isTrue();

        // Step 3: Read file
        try (InputStream inputStream = fs.read("integration-test.txt")) {
            String readContent = new String(inputStream.readAllBytes());
            assertThat(readContent).isEqualTo(content);
        }

        // Step 4: Delete file
        fs.delete("integration-test.txt");

        // Step 5: Verify file is deleted
        assertThat(fs.exists("integration-test.txt")).isFalse();

        // Close and verify status
        fs.close();
        status = fs.getStatus();
        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.DISCONNECTED);
        assertThat(status.isAvailable()).isFalse();
    }

    @Test
    void testMultipleFilesInDifferentDirectories() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write multiple files
        fs.write("file1.txt", new ByteArrayInputStream("Content 1".getBytes()), 9);
        fs.write("dir1/file2.txt", new ByteArrayInputStream("Content 2".getBytes()), 9);
        fs.write("dir1/dir2/file3.txt", new ByteArrayInputStream("Content 3".getBytes()), 9);

        // Verify all files exist
        assertThat(fs.exists("file1.txt")).isTrue();
        assertThat(fs.exists("dir1/file2.txt")).isTrue();
        assertThat(fs.exists("dir1/dir2/file3.txt")).isTrue();

        // Read all files
        try (InputStream is1 = fs.read("file1.txt")) {
            assertThat(new String(is1.readAllBytes())).isEqualTo("Content 1");
        }
        try (InputStream is2 = fs.read("dir1/file2.txt")) {
            assertThat(new String(is2.readAllBytes())).isEqualTo("Content 2");
        }
        try (InputStream is3 = fs.read("dir1/dir2/file3.txt")) {
            assertThat(new String(is3.readAllBytes())).isEqualTo("Content 3");
        }

        // Delete all files
        fs.delete("file1.txt");
        fs.delete("dir1/file2.txt");
        fs.delete("dir1/dir2/file3.txt");

        // Verify all deleted
        assertThat(fs.exists("file1.txt")).isFalse();
        assertThat(fs.exists("dir1/file2.txt")).isFalse();
        assertThat(fs.exists("dir1/dir2/file3.txt")).isFalse();

        fs.close();
    }

    @Test
    void testOverwriteAndReadCycle() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write initial content
        String content1 = "Initial content";
        fs.write("test.txt", new ByteArrayInputStream(content1.getBytes()), content1.length());

        try (InputStream is = fs.read("test.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(content1);
        }

        // Overwrite with new content
        String content2 = "Updated content that is longer than the initial content";
        fs.write("test.txt", new ByteArrayInputStream(content2.getBytes()), content2.length());

        try (InputStream is = fs.read("test.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(content2);
        }

        fs.delete("test.txt");
        assertThat(fs.exists("test.txt")).isFalse();

        fs.close();
    }

    @Test
    void testCustomBufferSize() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString(), 1024, // 1KB buffer
                true);
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write and read with custom buffer size
        byte[] data = new byte[10 * 1024]; // 10KB
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }

        fs.write("buffered.bin", new ByteArrayInputStream(data), data.length);

        try (InputStream is = fs.read("buffered.bin")) {
            byte[] readData = is.readAllBytes();
            assertThat(readData).isEqualTo(data);
        }

        fs.delete("buffered.bin");
        fs.close();
    }

    @Test
    void testFileOperationsWithoutAutoCreateDirectories() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString(),
                LocalFileSystemConfig.DEFAULT_BUFFER_SIZE, false // Don't auto-create directories
        );
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Writing to root should work
        fs.write("root-file.txt", new ByteArrayInputStream("Root".getBytes()), 4);
        assertThat(fs.exists("root-file.txt")).isTrue();

        // Writing to non-existent nested directory should fail
        assertThatThrownBy(() -> fs.write("nonexistent/file.txt", new ByteArrayInputStream("Fail".getBytes()), 4))
                .isInstanceOf(Exception.class);

        fs.close();
    }

    @Test
    void testInitializeCreatesMissingBaseDirectory() throws IOException {
        Path nonExistentDir = tempDir.resolve("new-base-dir");
        assertThat(nonExistentDir).doesNotExist();

        LocalFileSystemConfig config = new LocalFileSystemConfig(nonExistentDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThat(nonExistentDir).exists();
        assertThat(nonExistentDir).isDirectory();

        BackendStatus status = fs.getStatus();
        assertThat(status.isAvailable()).isTrue();

        fs.close();
    }

    @Test
    void testMultipleInitializeCallsAreIdempotent() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);

        fs.initialize();
        fs.initialize(); // Should not throw or cause issues
        fs.initialize();

        BackendStatus status = fs.getStatus();
        assertThat(status.isAvailable()).isTrue();

        fs.close();
    }

    @Test
    void testMultipleCloseCallsAreIdempotent() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.close();
        fs.close(); // Should not throw or cause issues
        fs.close();

        BackendStatus status = fs.getStatus();
        assertThat(status.isAvailable()).isFalse();
    }
}
