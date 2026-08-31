package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.FileSystemUsage;

class LocalFileSystemGetUsageSummaryTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem createFs() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        return fs;
    }

    private void writeFile(LocalFileSystem fs, String path, String content) {
        fs.write(path, new ByteArrayInputStream(content.getBytes()), content.length());
    }

    @Test
    void testEmptyVfs() {
        LocalFileSystem fs = createFs();

        FileSystemUsage usage = fs.getUsageSummary();

        assertThat(usage.getTotalSize()).isZero();
        assertThat(usage.getFileCount()).isZero();
        assertThat(usage.getDirectoryCount()).isZero();

        fs.close();
    }

    @Test
    void testSingleFile() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "test.txt", "hello");

        FileSystemUsage usage = fs.getUsageSummary();

        assertThat(usage.getFileCount()).isEqualTo(1);
        assertThat(usage.getTotalSize()).isEqualTo(5);
        assertThat(usage.getDirectoryCount()).isZero();

        fs.close();
    }

    @Test
    void testMultipleFilesAndDirectories() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "a.txt", "aaa");
        writeFile(fs, "dir1/b.txt", "bbbb");
        writeFile(fs, "dir1/dir2/c.txt", "ccccc");

        FileSystemUsage usage = fs.getUsageSummary();

        assertThat(usage.getFileCount()).isEqualTo(3);
        assertThat(usage.getTotalSize()).isEqualTo(3 + 4 + 5);
        assertThat(usage.getDirectoryCount()).isEqualTo(2); // dir1, dir1/dir2

        fs.close();
    }

    @Test
    void testRootDirectoryNotCounted() {
        LocalFileSystem fs = createFs();
        writeFile(fs, "file.txt", "x");

        FileSystemUsage usage = fs.getUsageSummary();

        // Root (basePath) should NOT be counted as a directory
        assertThat(usage.getDirectoryCount()).isZero();

        fs.close();
    }

    @Test
    void testAccurateTotalSize() {
        LocalFileSystem fs = createFs();
        String content100 = "a".repeat(100);
        String content200 = "b".repeat(200);

        writeFile(fs, "small.bin", content100);
        writeFile(fs, "large.bin", content200);

        FileSystemUsage usage = fs.getUsageSummary();

        assertThat(usage.getTotalSize()).isEqualTo(300);

        fs.close();
    }

    @Test
    void testEmptyDirectoryIsCounted() throws IOException {
        LocalFileSystem fs = createFs();
        Files.createDirectory(tempDir.resolve("emptydir"));

        FileSystemUsage usage = fs.getUsageSummary();

        assertThat(usage.getDirectoryCount()).isEqualTo(1);
        assertThat(usage.getFileCount()).isZero();
        assertThat(usage.getTotalSize()).isZero();

        fs.close();
    }

    @Test
    void testFailsWhenNotInitialized() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);

        assertThatThrownBy(fs::getUsageSummary).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");

        fs.close();
    }

    @Test
    void testFailsWhenClosed() {
        LocalFileSystem fs = createFs();
        fs.close();

        assertThatThrownBy(fs::getUsageSummary).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
