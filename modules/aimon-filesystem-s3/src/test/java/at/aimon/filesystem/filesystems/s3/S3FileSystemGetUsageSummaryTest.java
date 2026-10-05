package at.aimon.filesystem.filesystems.s3;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.FileSystemUsage;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.filesystem.core.s3.S3FileSystem;

@Tag("docker")
class S3FileSystemGetUsageSummaryTest {

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
        S3FileSystem uninitFs = new S3FileSystem(S3TestSupport.createConfig());

        assertThatThrownBy(uninitFs::getUsageSummary).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        uninitFs.close();
    }

    @Test
    void testFailsWhenClosed() {
        S3FileSystem closedFs = S3TestSupport.createAndInitialize();
        closedFs.close();

        assertThatThrownBy(closedFs::getUsageSummary).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
    // --- getUsageSummary(String path) ------------------------------------------------------------------------------

    @Test
    @DisplayName("the path overload counts only that subtree, not the rest of the bucket")
    void pathScopesToSubtree() {
        write("a.txt", "1");
        write("dir/b.txt", "22");
        write("dir/deeper/c.txt", "333");
        write("other/d.txt", "4444");

        FileSystemUsage usage = fs.getUsageSummary("dir");

        assertThat(usage.getFileCount()).isEqualTo(2);
        assertThat(usage.getTotalSize()).isEqualTo(5);
        // "deeper/" only; the directory itself is not counted
        assertThat(usage.getDirectoryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a sibling whose name merely starts with the directory's name is not part of its subtree")
    void pathDoesNotMatchSiblingPrefix() {
        write("dir/b.txt", "22");
        write("dir2/x.txt", "55555");
        write("dirfile.txt", "666666");

        FileSystemUsage usage = fs.getUsageSummary("dir");

        assertThat(usage.getFileCount()).isEqualTo(1);
        assertThat(usage.getTotalSize()).isEqualTo(2);
        assertThat(usage.getDirectoryCount()).isZero();
    }

    @Test
    @DisplayName("a trailing slash names the same directory")
    void pathWithTrailingSlash() {
        write("dir/b.txt", "22");
        write("dir/deeper/c.txt", "333");

        assertThat(fs.getUsageSummary("dir/")).isEqualTo(fs.getUsageSummary("dir"));
    }

    @Test
    @DisplayName("a path naming the root reports the same as the no-argument overload")
    void rootPathEqualsWholeBucket() {
        write("a.txt", "1");
        write("dir/b.txt", "22");
        write("dir/deeper/c.txt", "333");
        fs.createDirectory("empty");

        FileSystemUsage all = fs.getUsageSummary();

        assertThat(fs.getUsageSummary("")).isEqualTo(all);
        assertThat(fs.getUsageSummary(".")).isEqualTo(all);
    }

    @Test
    @DisplayName("an explicitly created empty directory exists and counts as a directory")
    void markerDirectories() {
        fs.createDirectory("empty");
        fs.createDirectory("dir/sub");
        write("dir/b.txt", "22");

        FileSystemUsage empty = fs.getUsageSummary("empty");
        assertThat(empty.getFileCount()).isZero();
        assertThat(empty.getTotalSize()).isZero();
        assertThat(empty.getDirectoryCount()).isZero();

        FileSystemUsage dir = fs.getUsageSummary("dir");
        assertThat(dir.getFileCount()).isEqualTo(1);
        assertThat(dir.getDirectoryCount()).isEqualTo(1); // "sub/"

        // dir/, dir/sub/, empty/
        assertThat(fs.getUsageSummary().getDirectoryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a missing path is FileNotFoundException, not an empty summary")
    void missingPathIsRejected() {
        write("dir/b.txt", "22");

        assertThatThrownBy(() -> fs.getUsageSummary("nope")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    @DisplayName("a path naming a regular file is InvalidPathException")
    void regularFileIsRejected() {
        write("dir/b.txt", "22");

        assertThatThrownBy(() -> fs.getUsageSummary("dir/b.txt")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void pathRejectsNull() {
        assertThatThrownBy(() -> fs.getUsageSummary(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void pathFailsWhenNotInitialized() {
        S3FileSystem uninitFs = new S3FileSystem(S3TestSupport.createConfig());

        assertThatThrownBy(() -> uninitFs.getUsageSummary("dir")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        uninitFs.close();
    }

    private void write(String path, String content) {
        byte[] bytes = content.getBytes();
        fs.write(path, new ByteArrayInputStream(bytes), bytes.length);
    }
}
