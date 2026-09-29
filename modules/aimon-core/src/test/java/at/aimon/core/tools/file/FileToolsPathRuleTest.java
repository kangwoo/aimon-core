package at.aimon.core.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.PathRule;
import at.aimon.core.filesystem.impl.PathRuleVirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A path rule's refusal reaches the model as a plain tool error — not as "Unexpected error", and not logged at ERROR
 * with a stack trace — because it is an expected answer (execution-environment design §9.2).
 */
@DisplayName("File tools answer a path-rule refusal with a plain error")
class FileToolsPathRuleTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem raw;
    private ToolContext context;
    private final List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(ReadTool.class),
            (Logger) LoggerFactory.getLogger(WriteTool.class), (Logger) LoggerFactory.getLogger(EditTool.class));
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve(".aimon"));
        Files.writeString(tempDir.resolve(".aimon/secret.txt"), "CONTROL-ONLY");
        Files.createDirectories(tempDir.resolve(".aimon-staged/n/k"));
        Files.writeString(tempDir.resolve(".aimon-staged/n/k/run.sh"), "echo staged");
        raw = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        raw.initialize();
        context = TestExecutionEnvironments.context(new PathRuleVirtualFileSystem(raw,
                List.of(PathRule.deny(".aimon"), PathRule.readOnly(".aimon-staged"))));
        appender.start();
        loggers.forEach(logger -> logger.addAppender(appender));
    }

    @AfterEach
    void tearDown() {
        loggers.forEach(logger -> logger.detachAppender(appender));
        raw.close();
    }

    private void assertPlainRefusal(ToolResult result) {
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent()).doesNotContain("Unexpected error").doesNotContain("Failed to")
                .doesNotContain("CONTROL-ONLY").doesNotContain("echo staged");
        assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(appender.list).noneMatch(event -> event.getThrowableProxy() != null);
    }

    @Test
    @DisplayName("Read of a hidden path")
    void readHidden() {
        assertPlainRefusal(new ReadTool().execute(ToolInput.of("file_path", ".aimon/secret.txt"), context));
    }

    @Test
    @DisplayName("Write into a read-only or hidden path")
    void writeRefused() {
        final ToolResult readOnly = new WriteTool()
                .execute(ToolInput.of("file_path", ".aimon-staged/n/k/new.sh", "content", "PWNED"), context);
        assertPlainRefusal(readOnly);
        assertThat(readOnly.getContent()).startsWith("Access denied");
        assertPlainRefusal(
                new WriteTool().execute(ToolInput.of("file_path", ".aimon/new.txt", "content", "x"), context));
        assertThat(tempDir.resolve(".aimon-staged/n/k/new.sh")).doesNotExist();
    }

    @Test
    @DisplayName("Edit of a read-only path, after reading it")
    void editReadOnly() {
        assertThat(new ReadTool().execute(ToolInput.of("file_path", ".aimon-staged/n/k/run.sh"), context).isSuccess())
                .isTrue();
        final ToolResult result = new EditTool().execute(ToolInput.of("file_path", ".aimon-staged/n/k/run.sh",
                "old_string", "echo staged", "new_string", "echo PWNED"), context);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent()).startsWith("Access denied").doesNotContain("Unexpected error");
        assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(tempDir.resolve(".aimon-staged/n/k/run.sh")).hasContent("echo staged");
    }
}
