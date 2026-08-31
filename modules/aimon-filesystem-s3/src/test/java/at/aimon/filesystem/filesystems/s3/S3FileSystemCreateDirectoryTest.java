package at.aimon.filesystem.filesystems.s3;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.filesystem.core.s3.S3FileSystem;

@Tag("docker")
class S3FileSystemCreateDirectoryTest {

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
    void testCreateDirectoryRootIsNoOp() {
        fs.createDirectory(".");
    }

    @Test
    void testCreateDirectoryIdempotent() {
        fs.createDirectory("mydir");
        fs.createDirectory("mydir");
    }

    @Test
    void testCreateDirectoryCreatesMarkerObject() {
        fs.createDirectory("mydir");

        // S3 creates a zero-byte marker object ending with "/"
        assertThat(fs.exists("mydir/")).isTrue();
    }

    @Test
    void testCreateDirectoryFailsWhenFileExists() {
        fs.write("conflict", new ByteArrayInputStream("data".getBytes()), 4);

        assertThatThrownBy(() -> fs.createDirectory("conflict")).isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void testCreateDirectoryRejectsNull() {
        assertThatThrownBy(() -> fs.createDirectory(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");
    }

    @Test
    void testCreateDirectoryFailsWhenNotInitialized() {
        S3FileSystem uninitFs = new S3FileSystem(S3TestSupport.createConfig());

        assertThatThrownBy(() -> uninitFs.createDirectory("test")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        uninitFs.close();
    }

    @Test
    void testCreateDirectoryFailsWhenClosed() {
        S3FileSystem closedFs = S3TestSupport.createAndInitialize();
        closedFs.close();

        assertThatThrownBy(() -> closedFs.createDirectory("test")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
