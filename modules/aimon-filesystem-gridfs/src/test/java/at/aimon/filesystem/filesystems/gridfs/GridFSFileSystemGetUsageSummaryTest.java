package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.FileSystemUsage;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

@Tag("docker")
class GridFSFileSystemGetUsageSummaryTest {

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
    void testEmptyVfs() {
        FileSystemUsage usage = fs.getUsageSummary();

        assertThat(usage.getTotalSize()).isZero();
        assertThat(usage.getFileCount()).isZero();
        assertThat(usage.getDirectoryCount()).isZero();
    }

    @Test
    void testSingleFile() {
        String content = "hello";
        fs.write("test.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        FileSystemUsage usage = fs.getUsageSummary();

        assertThat(usage.getFileCount()).isEqualTo(1);
        assertThat(usage.getTotalSize()).isEqualTo(content.getBytes().length);
        assertThat(usage.getDirectoryCount()).isZero();
    }

    @Test
    void testMultipleFilesAndDirectories() {
        fs.write("a.txt", new ByteArrayInputStream("a".getBytes()), 1);
        fs.write("dir/b.txt", new ByteArrayInputStream("b".getBytes()), 1);
        fs.write("dir/sub/c.txt", new ByteArrayInputStream("c".getBytes()), 1);

        FileSystemUsage usage = fs.getUsageSummary();

        assertThat(usage.getFileCount()).isEqualTo(3);
        // "dir/" and "dir/sub/" are derived directories
        assertThat(usage.getDirectoryCount()).isEqualTo(2);
    }

    @Test
    void testAccurateTotalSize() {
        byte[] content1 = new byte[100];
        byte[] content2 = new byte[200];
        fs.write("a.bin", new ByteArrayInputStream(content1), content1.length);
        fs.write("b.bin", new ByteArrayInputStream(content2), content2.length);

        FileSystemUsage usage = fs.getUsageSummary();

        assertThat(usage.getTotalSize()).isEqualTo(300);
    }

    @Test
    void testFailsWhenNotInitialized() {
        GridFSFileSystem uninitFs = new GridFSFileSystem(GridFSTestSupport.createConfig());

        assertThatThrownBy(uninitFs::getUsageSummary).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        uninitFs.close();
    }

    @Test
    void testFailsWhenClosed() {
        GridFSFileSystem closedFs = GridFSTestSupport.createAndInitialize();
        closedFs.close();

        assertThatThrownBy(closedFs::getUsageSummary).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
