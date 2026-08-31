package at.aimon.core.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("FilePathSubjects Tests")
class FilePathSubjectsTest {

    @TempDir
    Path tempDir;

    private static ToolInput input(Object filePath) {
        return ToolInput.of(Map.of("file_path", filePath));
    }

    private static ToolContext withWorkingDirectory(String workingDirectory) {
        return ToolContext.builder()
                .put(ToolContextKeys.ENVIRONMENT_KEY, Environment.createWithWorkingDirectory(workingDirectory)).build();
    }

    @Test
    @DisplayName("Should produce a PATH subject for an absolute path")
    void shouldProducePathSubjectForAbsolutePath() {
        Optional<PermissionSubject> subject = FilePathSubjects.filePathSubject(input("/tmp/a.txt"),
                ToolContext.empty());

        assertThat(subject).isPresent();
        assertThat(subject.get().getKind()).isEqualTo(PermissionSubject.Kind.PATH);
        assertThat(subject.get().getValue()).isEqualTo("/tmp/a.txt");
    }

    /**
     * Normalizing first is what makes {@code /allowed/../secrets} fail an {@code /allowed/**} pattern instead of
     * passing it on the strength of its prefix.
     */
    @Test
    @DisplayName("Should fold traversal segments before judging")
    void shouldNormalizeTraversal() {
        assertThat(FilePathSubjects.filePathSubject(input("/allowed/../secrets"), ToolContext.empty())).get()
                .extracting(PermissionSubject::getValue).isEqualTo("/secrets");
        assertThat(FilePathSubjects.filePathSubject(input("/tmp/./a.txt"), ToolContext.empty())).get()
                .extracting(PermissionSubject::getValue).isEqualTo("/tmp/a.txt");
    }

    @Test
    @DisplayName("Should resolve a relative path against the environment's working directory")
    void shouldResolveRelativeAgainstWorkingDirectory() {
        ToolContext context = withWorkingDirectory("/work");

        assertThat(FilePathSubjects.filePathSubject(input("notes.txt"), context)).get()
                .extracting(PermissionSubject::getValue).isEqualTo("/work/notes.txt");
        assertThat(FilePathSubjects.filePathSubject(input("sub/../notes.txt"), context)).get()
                .extracting(PermissionSubject::getValue).isEqualTo("/work/notes.txt");
    }

    /**
     * Falling back to the process CWD would make the verdict depend on where the JVM was started, which the person
     * writing the pattern cannot see. With nothing to resolve against there is no subject, and no subject is a denial
     * whenever a pattern is configured.
     */
    @Test
    @DisplayName("Should give no subject for a relative path with no environment")
    void shouldGiveNoSubjectForRelativePathWithoutEnvironment() {
        assertThat(FilePathSubjects.filePathSubject(input("notes.txt"), ToolContext.empty())).isEmpty();
    }

    @Test
    @DisplayName("Should still judge an absolute path with no environment")
    void shouldStillJudgeAbsolutePathWithoutEnvironment() {
        assertThat(FilePathSubjects.filePathSubject(input("/tmp/a.txt"), ToolContext.empty())).isPresent();
    }

    @Test
    @DisplayName("Should give no subject for a relative working directory")
    void shouldGiveNoSubjectForRelativeWorkingDirectory() {
        // Resolving against a relative base only pushes the guess one level out — still unpredictable
        assertThat(FilePathSubjects.filePathSubject(input("notes.txt"), withWorkingDirectory("work"))).isEmpty();
    }

    @Test
    @DisplayName("Should give no subject when file_path is missing or blank")
    void shouldGiveNoSubjectForMissingOrBlankPath() {
        assertThat(FilePathSubjects.filePathSubject(ToolInput.of(), ToolContext.empty())).isEmpty();
        assertThat(FilePathSubjects.filePathSubject(input("   "), ToolContext.empty())).isEmpty();
    }

    /**
     * The subject is derived ahead of the tool, outside the guard that turns a failing execution into an error result,
     * so a wrong-typed argument must come back as "cannot be judged" rather than as an exception.
     */
    @Test
    @DisplayName("Should give no subject when file_path is not a string")
    void shouldGiveNoSubjectWhenPathIsNotString() {
        assertThat(FilePathSubjects.filePathSubject(input(42), ToolContext.empty())).isEmpty();
        assertThat(FilePathSubjects.filePathSubject(input(Map.of("nested", "value")), ToolContext.empty())).isEmpty();
    }

    @Test
    @DisplayName("Should give no subject for a path this platform cannot represent")
    void shouldGiveNoSubjectForUnrepresentablePath() {
        assertThat(FilePathSubjects.filePathSubject(input("/tmp/a\0b"), ToolContext.empty())).isEmpty();
    }

    @Test
    @DisplayName("Should be what the file tools present as their subject")
    void shouldBeWhatFileToolsPresent() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();

        ReadTool readTool = new ReadTool(fileSystem);
        EditTool editTool = new EditTool(fileSystem);
        WriteTool writeTool = new WriteTool(fileSystem);
        ToolInput input = input("/tmp/../tmp/a.txt");

        assertThat(readTool.permissionSubject(input, ToolContext.empty()))
                .isEqualTo(FilePathSubjects.filePathSubject(input, ToolContext.empty()));
        assertThat(editTool.permissionSubject(input, ToolContext.empty())).get().extracting(PermissionSubject::getValue)
                .isEqualTo("/tmp/a.txt");
        assertThat(writeTool.permissionSubject(input, ToolContext.empty())).get()
                .extracting(PermissionSubject::getValue).isEqualTo("/tmp/a.txt");
    }
}
