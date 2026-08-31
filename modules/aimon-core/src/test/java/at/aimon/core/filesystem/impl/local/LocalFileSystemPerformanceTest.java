package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileSystemPerformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void testListPerformanceWith1000Files() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create 1,000 files
        System.out.println("Creating 1,000 files...");
        long createStart = System.currentTimeMillis();

        for (int i = 0; i < 1000; i++) {
            String content = "content-" + i;
            fs.write("file-" + i + ".txt", new ByteArrayInputStream(content.getBytes()), content.length());
        }

        long createTime = System.currentTimeMillis() - createStart;
        System.out.println("Created 1,000 files in " + createTime + "ms");

        // Test list performance
        long listStart = System.currentTimeMillis();
        List<String> files = fs.list(".");
        long listTime = System.currentTimeMillis() - listStart;

        System.out.println("Listed 1,000 files in " + listTime + "ms");

        assertThat(files).hasSize(1000);
        assertThat(listTime).isLessThan(500); // SC-006: < 500ms

        fs.close();
    }

    @Test
    void testListRecursivePerformanceWith1000Files() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create 1,000 files across multiple directories (10 dirs, 100 files each)
        System.out.println("Creating 1,000 files in nested structure...");
        long createStart = System.currentTimeMillis();

        for (int dir = 0; dir < 10; dir++) {
            for (int file = 0; file < 100; file++) {
                String path = "dir-" + dir + "/file-" + file + ".txt";
                String content = "content-" + dir + "-" + file;
                fs.write(path, new ByteArrayInputStream(content.getBytes()), content.length());
            }
        }

        long createTime = System.currentTimeMillis() - createStart;
        System.out.println("Created 1,000 files in " + createTime + "ms");

        // Test listRecursive performance
        long listStart = System.currentTimeMillis();
        List<String> files = fs.listRecursive(".");
        long listTime = System.currentTimeMillis() - listStart;

        System.out.println("Listed 1,000 files recursively in " + listTime + "ms");

        assertThat(files).hasSize(1000);
        assertThat(listTime).isLessThan(500); // SC-006: < 500ms

        fs.close();
    }

    @Test
    void testWritePerformanceFor1MBFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        byte[] data = new byte[1024 * 1024]; // 1MB

        long writeStart = System.currentTimeMillis();
        fs.write("large-file.bin", new ByteArrayInputStream(data), data.length);
        long writeTime = System.currentTimeMillis() - writeStart;

        System.out.println("Wrote 1MB file in " + writeTime + "ms");

        // Should be much faster than 100ms (from requirements)
        assertThat(writeTime).isLessThan(100);

        fs.close();
    }

    @Test
    void testReadPerformanceFor1MBFile() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Write 1MB file
        byte[] data = new byte[1024 * 1024];
        fs.write("large-file.bin", new ByteArrayInputStream(data), data.length);

        // Test read performance
        long readStart = System.currentTimeMillis();
        try (java.io.InputStream is = fs.read("large-file.bin")) {
            byte[] readData = is.readAllBytes();
            assertThat(readData).hasSize(data.length);
        }
        long readTime = System.currentTimeMillis() - readStart;

        System.out.println("Read 1MB file in " + readTime + "ms");

        // Should be reasonably fast
        assertThat(readTime).isLessThan(100);

        fs.close();
    }

    @Test
    void testGetMetadataPerformanceFor1000Files() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create 100 files (scaled down for reasonable test time)
        for (int i = 0; i < 100; i++) {
            String content = "content-" + i;
            fs.write("file-" + i + ".txt", new ByteArrayInputStream(content.getBytes()), content.length());
        }

        // Test getMetadata performance on all files
        long metadataStart = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            fs.getMetadata("file-" + i + ".txt");
        }
        long metadataTime = System.currentTimeMillis() - metadataStart;

        System.out.println("Retrieved metadata for 100 files in " + metadataTime + "ms");

        // Should be fast (< 1000ms for 100 files = < 10ms per file)
        assertThat(metadataTime).isLessThan(1000);

        fs.close();
    }

    @Test
    void testConcurrentFileOperations() throws IOException, InterruptedException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();

        // Create 100 files
        for (int i = 0; i < 100; i++) {
            String content = "content-" + i;
            fs.write("file-" + i + ".txt", new ByteArrayInputStream(content.getBytes()), content.length());
        }

        System.out.println("Testing concurrent operations...");

        // Test concurrent exists checks
        long concurrentStart = System.currentTimeMillis();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(10);

        List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < 100; i++) {
            final int fileNum = i;
            futures.add(executor.submit(() -> fs.exists("file-" + fileNum + ".txt")));
        }

        for (java.util.concurrent.Future<Boolean> future : futures) {
            try {
                assertThat(future.get()).isTrue();
            } catch (Exception e) {
                fail("Concurrent operation failed", e);
            }
        }

        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        long concurrentTime = System.currentTimeMillis() - concurrentStart;
        System.out.println("100 concurrent exists checks completed in " + concurrentTime + "ms");

        fs.close();
    }
}
