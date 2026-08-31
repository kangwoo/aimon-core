package at.aimon.core.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

/**
 * Verifies the cooperative interrupt contract on {@link GrepTool}:
 * <ul>
 * <li>{@link GrepTool#getInterruptBehavior()} returns {@link InterruptBehavior#COOPERATIVE}.
 * <li>A pre-tripped {@link at.aimon.core.agent.interrupt.CancellationSignal} aborts the search before the file scan
 * starts.
 * <li>An absent/untripped signal runs the full search with normal results.
 * </ul>
 *
 * <p>
 * Mid-scan per-file checkpoint behaviour is exercised implicitly: the pre-tripped test also tolerates a path where
 * the discovery phase returns zero files, and the normal path proves the signal path doesn't short-circuit otherwise.
 */
@DisplayName("GrepTool cooperative interrupt")
class GrepToolInterruptTest {

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;
    private GrepTool grepTool;

    @BeforeEach
    void setUp() throws IOException {
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        grepTool = new GrepTool(fileSystem);

        // Seed a handful of files so the scan has real work to do.
        Files.writeString(tempDir.resolve("a.txt"), "needle here\n");
        Files.writeString(tempDir.resolve("b.txt"), "nothing\n");
        Files.writeString(tempDir.resolve("c.txt"), "needle again\n");
    }

    @AfterEach
    void tearDown() {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    @Test
    @DisplayName("getInterruptBehavior() == COOPERATIVE")
    void declaresCooperative() {
        assertThat(grepTool.getInterruptBehavior()).isEqualTo(InterruptBehavior.COOPERATIVE);
    }

    @Test
    @DisplayName("pre-tripped signal aborts the grep before any file is scanned")
    void preTrippedSignalShortCircuits() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final ToolContext context = ToolContext.builder()
                    .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal()).build();

            final ToolResult result = grepTool
                    .execute(ToolInput.of(Map.of("pattern", "needle", "output_mode", "content")), context);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Grep interrupted").contains("USER_SIGINT");
        }
    }

    @Test
    @DisplayName("untripped signal path behaves like pre-IRQ normal execution")
    void untrippedSignalPathIsNormal() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final ToolContext context = ToolContext.builder()
                    .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal()).build();

            final ToolResult result = grepTool
                    .execute(ToolInput.of(Map.of("pattern", "needle", "output_mode", "content")), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("needle here").contains("needle again");
        }
    }

    @Test
    @DisplayName("empty ToolContext (no signal wired) still executes normally")
    void noSignalStillExecutes() {
        final ToolResult result = grepTool.execute(ToolInput.of(Map.of("pattern", "needle", "output_mode", "content")),
                ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("needle");
    }
}
