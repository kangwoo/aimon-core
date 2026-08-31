package at.aimon.filesystem.filesystems.s3;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.filesystem.core.s3.S3Config;
import at.aimon.filesystem.core.s3.S3FileSystem;

@Tag("docker")
class S3FileSystemIntegrationTest {

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
    void testFullWriteReadDeleteCycle() throws Exception {
        String content = "Integration test content";

        // Write
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());
        assertThat(fs.exists("test.txt")).isTrue();

        // Read
        try (InputStream in = fs.read("test.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo(content);
        }

        // Delete
        fs.delete("test.txt");
        assertThat(fs.exists("test.txt")).isFalse();

        // Verify read after delete throws
        assertThatThrownBy(() -> fs.read("test.txt")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void testMultipleFilesInDifferentDirectories() {
        fs.write("root.txt", new ByteArrayInputStream("r".getBytes()), 1);
        fs.write("docs/readme.md", new ByteArrayInputStream("d".getBytes()), 1);
        fs.write("src/main/App.java", new ByteArrayInputStream("s".getBytes()), 1);

        assertThat(fs.exists("root.txt")).isTrue();
        assertThat(fs.exists("docs/readme.md")).isTrue();
        assertThat(fs.exists("src/main/App.java")).isTrue();

        assertThat(fs.listRecursive(".")).hasSize(3);
    }

    @Test
    void testOverwriteAndReadCycle() throws Exception {
        fs.write("test.txt", new ByteArrayInputStream("version1".getBytes()), 8);

        try (InputStream in = fs.read("test.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo("version1");
        }

        fs.write("test.txt", new ByteArrayInputStream("version2".getBytes()), 8);

        try (InputStream in = fs.read("test.txt")) {
            assertThat(new String(in.readAllBytes())).isEqualTo("version2");
        }
    }

    @Test
    void testMultipleInitializeCallsAreIdempotent() {
        S3Config config = S3TestSupport.createConfig();
        S3FileSystem testFs = new S3FileSystem(config);

        testFs.initialize();
        testFs.initialize();
        testFs.initialize();

        assertThat(testFs.getStatus().isAvailable()).isTrue();

        testFs.close();
    }

    @Test
    void testMultipleCloseCallsAreIdempotent() {
        S3FileSystem testFs = S3TestSupport.createAndInitialize();

        testFs.close();
        testFs.close();
        testFs.close();

        assertThat(testFs.getStatus().isAvailable()).isFalse();
    }

    @Test
    void testStatusBeforeInitialization() {
        S3Config config = S3TestSupport.createConfig();
        S3FileSystem testFs = new S3FileSystem(config);

        BackendStatus status = testFs.getStatus();

        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.UNKNOWN);
        assertThat(status.getType()).isEqualTo(BackendType.S3);

        testFs.close();
    }

    @Test
    void testStatusAfterInitialization() {
        BackendStatus status = fs.getStatus();

        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.CONNECTED);
        assertThat(status.getType()).isEqualTo(BackendType.S3);
        assertThat(status.isAvailable()).isTrue();
    }

    @Test
    void testStatusAfterClose() {
        S3FileSystem testFs = S3TestSupport.createAndInitialize();
        testFs.close();

        BackendStatus status = testFs.getStatus();

        assertThat(status.getState()).isEqualTo(BackendStatus.ConnectionState.DISCONNECTED);
        assertThat(status.isAvailable()).isFalse();
    }
}
