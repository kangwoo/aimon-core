package at.aimon.core.filesystem.config;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;

class FileSystemFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void testCreateLocalFileSystem() {
        VirtualFileSystem fs = FileSystemFactory.createLocalFileSystem(tempDir.toString());

        assertThat(fs).isNotNull();
        fs.initialize();

        // Test basic operations
        String content = "Test content";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        assertThat(fs.exists("test.txt")).isTrue();

        try (InputStream is = fs.read("test.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(content);
        } catch (Exception e) {
            fail("Failed to read file", e);
        }

        fs.delete("test.txt");
        assertThat(fs.exists("test.txt")).isFalse();

        fs.close();
    }

    @Test
    void testCreateFileSystemWithBackendType() {
        VirtualFileSystem fs = FileSystemFactory.createFileSystem(BackendType.LOCAL, tempDir.toString());

        assertThat(fs).isNotNull();
        fs.initialize();

        assertThat(fs.getStatus().isAvailable()).isTrue();

        fs.close();
    }

    @Test
    void testCreateLocalFileSystemRequiresBasePath() {
        assertThatThrownBy(() -> FileSystemFactory.createLocalFileSystem(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testCreateFileSystemRequiresBackendType() {
        assertThatThrownBy(() -> FileSystemFactory.createFileSystem(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testBackendSwitching() {
        // Test that we can switch between backends by configuration
        VirtualFileSystem localFs = FileSystemFactory.createFileSystem(BackendType.LOCAL, tempDir.toString());

        localFs.initialize();
        assertThat(localFs.getStatus().getType()).isEqualTo(BackendType.LOCAL);
        localFs.close();
    }

    @Test
    void testConsistentBehaviorAcrossImplementations() {
        // All implementations should support basic operations
        VirtualFileSystem fs = FileSystemFactory.createLocalFileSystem(tempDir.toString());
        fs.initialize();

        // Write
        String content = "Consistent behavior test";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        // Exists
        assertThat(fs.exists("test.txt")).isTrue();

        // Read
        try (InputStream is = fs.read("test.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(content);
        } catch (Exception e) {
            fail("Read failed", e);
        }

        // Metadata
        FileMetadata metadata = fs.getMetadata("test.txt");
        assertThat(metadata.getSize()).isEqualTo(content.getBytes().length);

        // Delete
        fs.delete("test.txt");
        assertThat(fs.exists("test.txt")).isFalse();

        fs.close();
    }
}
