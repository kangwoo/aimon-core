package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

class LocalFileSystemMetadataTest {

    @TempDir
    Path tempDir;

    @Test
    void testGetMetadataReturnsAccurateSize() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "Test content with 30 bytes!";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        FileMetadata metadata = fs.getMetadata("test.txt");

        assertThat(metadata.getSize()).isEqualTo(content.getBytes().length);
        assertThat(metadata.getPath()).isEqualTo("test.txt");

        fs.close();
    }

    @Test
    void testGetMetadataReturnsAccurateTimestamps() throws IOException, InterruptedException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Use a wider time window to account for filesystem time precision
        Instant beforeWrite = Instant.now().minusSeconds(2);

        String content = "Test";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        Instant afterWrite = Instant.now().plusSeconds(2);

        FileMetadata metadata = fs.getMetadata("test.txt");

        // Timestamps should be recent and reasonable
        assertThat(metadata.getCreatedAt()).isBetween(beforeWrite, afterWrite);
        assertThat(metadata.getModifiedAt()).isBetween(beforeWrite, afterWrite);
        assertThat(metadata.getCreatedAt()).isNotNull();
        assertThat(metadata.getModifiedAt()).isNotNull();

        fs.close();
    }

    @Test
    void testGetMetadataDetectsMimeTypeForTextFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.write("test.txt", new ByteArrayInputStream("text".getBytes()), 4);

        FileMetadata metadata = fs.getMetadata("test.txt");

        assertThat(metadata.getMimeType()).hasValue("text/plain");

        fs.close();
    }

    @Test
    void testGetMetadataDetectsMimeTypeForPdf() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.write("document.pdf", new ByteArrayInputStream("pdf".getBytes()), 3);

        FileMetadata metadata = fs.getMetadata("document.pdf");

        assertThat(metadata.getMimeType()).hasValue("application/pdf");

        fs.close();
    }

    @Test
    void testGetMetadataDetectsMimeTypeForJpeg() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.write("photo.jpg", new ByteArrayInputStream("jpeg".getBytes()), 4);

        FileMetadata metadata = fs.getMetadata("photo.jpg");

        assertThat(metadata.getMimeType()).hasValue("image/jpeg");

        fs.close();
    }

    @Test
    void testGetMetadataReturnsNullForUnknownExtension() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Use a synthetic extension no platform MIME database recognizes. A "real-looking" but obscure extension is
        // not safe: e.g. Files.probeContentType maps ".xyz" to "chemical/x-xyz" on Linux (shared-mime-info) while
        // returning null on macOS, which made this test platform-dependent (issue #16).
        fs.write("unknown.aimonunknownext", new ByteArrayInputStream("data".getBytes()), 4);

        FileMetadata metadata = fs.getMetadata("unknown.aimonunknownext");

        assertThat(metadata.getMimeType()).isEmpty();

        fs.close();
    }

    @Test
    void testGetMetadataForFileWithoutExtension() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.write("noextension", new ByteArrayInputStream("data".getBytes()), 4);

        FileMetadata metadata = fs.getMetadata("noextension");

        assertThat(metadata.getMimeType()).isEmpty();

        fs.close();
    }

    @Test
    void testGetMetadataForNestedFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "Nested content";
        fs.write("dir1/dir2/nested.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        FileMetadata metadata = fs.getMetadata("dir1/dir2/nested.txt");

        assertThat(metadata.getPath()).isEqualTo("dir1/dir2/nested.txt");
        assertThat(metadata.getSize()).isEqualTo(content.getBytes().length);
        assertThat(metadata.getMimeType()).hasValue("text/plain");

        fs.close();
    }

    @Test
    void testGetMetadataForLargeFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        byte[] largeContent = new byte[10 * 1024 * 1024]; // 10MB
        fs.write("large.bin", new ByteArrayInputStream(largeContent), largeContent.length);

        FileMetadata metadata = fs.getMetadata("large.bin");

        assertThat(metadata.getSize()).isEqualTo(largeContent.length);
        // MIME type for .bin files can vary by platform (application/octet-stream,
        // application/macbinary, etc.)
        // Just verify that a MIME type is detected
        assertThat(metadata.getMimeType()).isPresent();
        assertThat(metadata.getMimeType().get()).contains("application/");

        fs.close();
    }

    @Test
    void testGetMetadataThrowsForNonExistentFile() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.getMetadata("nonexistent.txt")).isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("nonexistent.txt");

        fs.close();
    }

    @Test
    void testGetMetadataForDirectory() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create a directory
        java.nio.file.Files.createDirectory(tempDir.resolve("testdir"));

        FileMetadata metadata = fs.getMetadata("testdir");

        assertThat(metadata.isDirectory()).isTrue();
        assertThat(metadata.getSize()).isZero();
        assertThat(metadata.getPath()).isEqualTo("testdir");
        assertThat(metadata.getMimeType()).isEmpty();
        assertThat(metadata.getCreatedAt()).isNotNull();
        assertThat(metadata.getModifiedAt()).isNotNull();

        fs.close();
    }

    @Test
    void testGetMetadataRejectsNullPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.getMetadata(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");

        fs.close();
    }

    @Test
    void testGetMetadataRejectsInvalidPath() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        assertThatThrownBy(() -> fs.getMetadata("../../../etc/passwd")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("Path traversal detected");

        fs.close();
    }

    @Test
    void testGetMetadataForMultipleExtensions() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        fs.write("archive.tar.gz", new ByteArrayInputStream("data".getBytes()), 4);

        FileMetadata metadata = fs.getMetadata("archive.tar.gz");

        // Should detect based on last extension (.gz)
        assertThat(metadata.getMimeType()).hasValue("application/gzip");

        fs.close();
    }
}
