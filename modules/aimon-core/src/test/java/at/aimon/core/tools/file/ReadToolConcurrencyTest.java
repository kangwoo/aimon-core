package at.aimon.core.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.FileStamp;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.tools.ToolContextKeys;

/**
 * PAR-05 regression: {@link ReadTool} is {@code CONCURRENT_SAFE}, so many reads may run in parallel sharing one
 * {@link ToolContext}. When the executor injects a thread-safe stamp map (as it does), concurrent reads
 * must record themselves without {@code ConcurrentModificationException} or lost updates.
 */
@DisplayName("ReadTool concurrency Tests")
class ReadToolConcurrencyTest {

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;
    private ReadTool readTool;

    @BeforeEach
    void setUp() {
        final LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();
        readTool = new ReadTool();
    }

    @AfterEach
    void tearDown() {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    @Test
    @DisplayName("concurrent reads record every file in the shared thread-safe set without races")
    void concurrentReadsRecordAllFiles() throws Exception {
        final int fileCount = 64;
        final List<String> paths = new ArrayList<>(fileCount);
        final List<String> keys = new ArrayList<>(fileCount);
        for (int i = 0; i < fileCount; i++) {
            final Path file = tempDir.resolve("file-" + i + ".txt");
            Files.write(file, ("content-" + i).getBytes(StandardCharsets.UTF_8));
            paths.add(file.toString());
            keys.add("file-" + i + ".txt");
        }

        // The thread-safe stamp map the executors inject (a ConcurrentHashMap).
        final Map<String, FileStamp> stamps = new ConcurrentHashMap<>();
        final ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ENVIRONMENT, TestExecutionEnvironments.of(fileSystem))
                .put(ReadTool.FILE_STAMPS_KEY, stamps).build();

        final ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            final List<Callable<ToolResult>> tasks = new ArrayList<>(fileCount);
            for (String path : paths) {
                tasks.add(() -> readTool.execute(ToolInput.of("file_path", path), context));
            }
            final List<Future<ToolResult>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);

            for (Future<ToolResult> future : futures) {
                final ToolResult result = future.get();
                assertThat(result.isSuccess()).isTrue();
            }
            // Every read must be recorded exactly once (under its normalised key), with no lost updates.
            assertThat(stamps).containsOnlyKeys(keys);
        } finally {
            pool.shutdownNow();
        }
    }
}
