package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileSystemCopyMoveIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void testFullCopyMoveWorkflow() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Step 1: Create original file
        String originalContent = "Original file content";
        fs.write("original.txt", new ByteArrayInputStream(originalContent.getBytes()), originalContent.length());
        assertThat(fs.exists("original.txt")).isTrue();

        // Step 2: Copy to new location
        fs.copy("original.txt", "copy.txt", false);

        // Verify both exist with same content
        assertThat(fs.exists("original.txt")).isTrue();
        assertThat(fs.exists("copy.txt")).isTrue();

        try (InputStream is1 = fs.read("original.txt"); InputStream is2 = fs.read("copy.txt")) {
            assertThat(new String(is1.readAllBytes())).isEqualTo(originalContent);
            assertThat(new String(is2.readAllBytes())).isEqualTo(originalContent);
        }

        // Step 3: Move copied file to another location
        fs.move("copy.txt", "moved.txt", false);

        // Verify copy no longer exists, moved file exists
        assertThat(fs.exists("copy.txt")).isFalse();
        assertThat(fs.exists("moved.txt")).isTrue();
        assertThat(fs.exists("original.txt")).isTrue(); // Original still exists

        // Step 4: Verify moved file has correct content
        try (InputStream is = fs.read("moved.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(originalContent);
        }

        // Step 5: Clean up
        fs.delete("original.txt");
        fs.delete("moved.txt");

        assertThat(fs.exists("original.txt")).isFalse();
        assertThat(fs.exists("moved.txt")).isFalse();

        fs.close();
    }

    @Test
    void testCopyMultipleFilesThenMoveToArchive() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create multiple files
        for (int i = 1; i <= 5; i++) {
            String content = "File " + i + " content";
            fs.write("file" + i + ".txt", new ByteArrayInputStream(content.getBytes()), content.length());
        }

        // Copy all to backup directory
        for (int i = 1; i <= 5; i++) {
            fs.copy("file" + i + ".txt", "backup/file" + i + ".txt", false);
        }

        // Verify all originals and backups exist
        for (int i = 1; i <= 5; i++) {
            assertThat(fs.exists("file" + i + ".txt")).isTrue();
            assertThat(fs.exists("backup/file" + i + ".txt")).isTrue();
        }

        // Move originals to archive
        for (int i = 1; i <= 5; i++) {
            fs.move("file" + i + ".txt", "archive/file" + i + ".txt", false);
        }

        // Verify originals moved, backups still exist
        for (int i = 1; i <= 5; i++) {
            assertThat(fs.exists("file" + i + ".txt")).isFalse();
            assertThat(fs.exists("backup/file" + i + ".txt")).isTrue();
            assertThat(fs.exists("archive/file" + i + ".txt")).isTrue();
        }

        fs.close();
    }

    @Test
    void testCopyWithOverwriteWorkflow() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create v1 file
        fs.write("document.txt", new ByteArrayInputStream("Version 1".getBytes()), 9);

        // Copy to backup
        fs.copy("document.txt", "document-backup.txt", false);

        // Update original
        fs.write("document.txt", new ByteArrayInputStream("Version 2".getBytes()), 9);

        // Try to backup again without overwrite - should fail
        assertThatThrownBy(() -> fs.copy("document.txt", "document-backup.txt", false)).isInstanceOf(Exception.class);

        // Backup with overwrite - should succeed
        fs.copy("document.txt", "document-backup.txt", true);

        // Verify backup now has v2
        try (InputStream is = fs.read("document-backup.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo("Version 2");
        }

        fs.close();
    }

    @Test
    void testMoveChainAcrossMultipleDirectories() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        String content = "Moving file";
        fs.write("file.txt", new ByteArrayInputStream(content.getBytes()), content.length());

        // Move through multiple locations
        fs.move("file.txt", "stage1/file.txt", false);
        assertThat(fs.exists("file.txt")).isFalse();
        assertThat(fs.exists("stage1/file.txt")).isTrue();

        fs.move("stage1/file.txt", "stage2/file.txt", false);
        assertThat(fs.exists("stage1/file.txt")).isFalse();
        assertThat(fs.exists("stage2/file.txt")).isTrue();

        fs.move("stage2/file.txt", "final/file.txt", false);
        assertThat(fs.exists("stage2/file.txt")).isFalse();
        assertThat(fs.exists("final/file.txt")).isTrue();

        // Verify final location has correct content
        try (InputStream is = fs.read("final/file.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(content);
        }

        fs.close();
    }

    @Test
    void testCopyThenModifyOriginal() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create and copy file
        String original = "Original content";
        fs.write("original.txt", new ByteArrayInputStream(original.getBytes()), original.length());
        fs.copy("original.txt", "copy.txt", false);

        // Modify original
        String modified = "Modified content";
        fs.write("original.txt", new ByteArrayInputStream(modified.getBytes()), modified.length());

        // Verify copy is unchanged
        try (InputStream is = fs.read("copy.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(original);
        }

        // Verify original is modified
        try (InputStream is = fs.read("original.txt")) {
            assertThat(new String(is.readAllBytes())).isEqualTo(modified);
        }

        fs.close();
    }

    @Test
    void testReorganizeFileStructure() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create messy structure
        fs.write("doc1.txt", new ByteArrayInputStream("Doc 1".getBytes()), 5);
        fs.write("doc2.txt", new ByteArrayInputStream("Doc 2".getBytes()), 5);
        fs.write("img1.png", new ByteArrayInputStream("Img 1".getBytes()), 5);
        fs.write("img2.png", new ByteArrayInputStream("Img 2".getBytes()), 5);

        // Reorganize into proper structure
        fs.move("doc1.txt", "documents/doc1.txt", false);
        fs.move("doc2.txt", "documents/doc2.txt", false);
        fs.move("img1.png", "images/img1.png", false);
        fs.move("img2.png", "images/img2.png", false);

        // Verify organization
        assertThat(fs.exists("doc1.txt")).isFalse();
        assertThat(fs.exists("doc2.txt")).isFalse();
        assertThat(fs.exists("img1.png")).isFalse();
        assertThat(fs.exists("img2.png")).isFalse();

        assertThat(fs.exists("documents/doc1.txt")).isTrue();
        assertThat(fs.exists("documents/doc2.txt")).isTrue();
        assertThat(fs.exists("images/img1.png")).isTrue();
        assertThat(fs.exists("images/img2.png")).isTrue();

        // Verify can list organized directories
        assertThat(fs.list("documents")).hasSize(2);
        assertThat(fs.list("images")).hasSize(2);

        fs.close();
    }

    @Test
    void testPerformanceOfCopyVsMove() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create 10MB file
        byte[] largeData = new byte[10 * 1024 * 1024];
        fs.write("large.bin", new ByteArrayInputStream(largeData), largeData.length);

        // Test copy performance
        long copyStart = System.currentTimeMillis();
        fs.copy("large.bin", "large-copy.bin", false);
        long copyTime = System.currentTimeMillis() - copyStart;

        // Test move performance
        long moveStart = System.currentTimeMillis();
        fs.move("large-copy.bin", "large-moved.bin", false);
        long moveTime = System.currentTimeMillis() - moveStart;

        System.out.println("Copy 10MB: " + copyTime + "ms");
        System.out.println("Move 10MB: " + moveTime + "ms");

        // Move should be much faster than copy
        assertThat(moveTime).isLessThan(copyTime);

        // Move should be very fast (atomic operation)
        assertThat(moveTime).isLessThan(100);

        fs.close();
    }
}
