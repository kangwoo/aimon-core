package at.aimon.core.tools.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.skill.repository.ClasspathSkillRepository;
import at.aimon.core.skill.repository.SkillRepository;
import at.aimon.core.skill.repository.VfsSkillRepository;

/**
 * Regression integration tests for built-in skills.
 *
 * <p>
 * Drives {@link SkillTool} end-to-end through the inline path against the two repository implementations shipped today
 * — a filesystem-backed {@link VfsSkillRepository} and {@link ClasspathSkillRepository} — wired through
 * {@link DefaultSkillRegistry} with a real {@link MarkdownSkillParser} and {@link DefaultSkillContentRenderer}. Each
 * cell exercises a built-in skill with and without an {@code args} string so we catch regressions across skill loading,
 * parsing, rendering, and result formatting.
 *
 * <p>
 * The SK-IT-1 plan literally names {@code commit} and {@code wiki-maintenance}; the latter only ships under
 * {@code aimon-cli}'s production resources, so this module-level test substitutes {@code summarize} (the second skill
 * already present in the test fixture index). The regression value — exercising the args-handling path on two distinct
 * built-in skills across both repository backends — is unchanged.
 *
 * <p>
 * The bundled SKILL.md files do not reference {@code $ARGUMENTS}/{@code $1..$9}, so the args-present cases verify that
 * the rendering pipeline silently drops args when no placeholder is present and the tool still surfaces the skill body
 * — that is the regression we want to lock down at this seam.
 */
@DisplayName("SK-IT-1: SkillTool → built-in skills end-to-end (VFS + classpath × args present/absent)")
class BuiltinSkillsIntegrationTest {

    private static final String SKILLS_BASE_PATH = "skills";
    private static final String CLASSPATH_BASE_PATH = "builtin/skills";

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem;

    @BeforeEach
    void setUp() throws IOException {
        // Stage the same fixtures the classpath repository sees onto a temp filesystem so both repositories
        // return identical content for the same skill name. Keeps the two paths comparable.
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();

        for (String name : new String[]{"commit", "summarize"}) {
            stageSkillOnVfs(name);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    static Stream<Arguments> matrix() {
        return Stream.of(Arguments.of("vfs", "commit", ""), Arguments.of("vfs", "commit", "--scope=feat"),
                Arguments.of("vfs", "summarize", ""), Arguments.of("vfs", "summarize", "the meeting notes"),
                Arguments.of("classpath", "commit", ""), Arguments.of("classpath", "commit", "--scope=feat"),
                Arguments.of("classpath", "summarize", ""),
                Arguments.of("classpath", "summarize", "the meeting notes"));
    }

    @ParameterizedTest(name = "[{0}] {1} args=\"{2}\"")
    @MethodSource("matrix")
    void invocation_SurfacesSkillBodyAndDescription(String repoKind, String skillName, String args) {
        final SkillRegistry registry = buildRegistry(repoKind);
        final SkillTool tool = new SkillTool(registry, new DefaultSkillContentRenderer());

        final ToolInput input = args.isEmpty()
                ? ToolInput.of("skill", skillName)
                : ToolInput.of("skill", skillName, "args", args);

        final ToolResult result = tool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).as("repo=%s skill=%s args=\"%s\"", repoKind, skillName, args).isTrue();
        assertThat(result.getContent()).contains("=== Skill Activated ===").contains("Skill: " + skillName)
                .contains("Description: ").contains("Allowed Tools: No restrictions").contains("Instructions:")
                .contains(expectedBodyMarker(skillName));
    }

    private SkillRegistry buildRegistry(String repoKind) {
        final SkillRepository repo = "vfs".equals(repoKind)
                ? new VfsSkillRepository(fileSystem, SKILLS_BASE_PATH)
                : new ClasspathSkillRepository(CLASSPATH_BASE_PATH);
        return new DefaultSkillRegistry(repo, new MarkdownSkillParser());
    }

    private void stageSkillOnVfs(String skillName) throws IOException {
        final String resourcePath = CLASSPATH_BASE_PATH + "/" + skillName + "/SKILL.md";
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(resourcePath),
                "Test fixture not on classpath: " + resourcePath)) {
            final byte[] bytes = in.readAllBytes();
            final String filePath = SKILLS_BASE_PATH + "/" + skillName + "/SKILL.md";
            fileSystem.write(filePath, new ByteArrayInputStream(bytes), bytes.length);
        }
    }

    private static String expectedBodyMarker(String skillName) {
        return switch (skillName) {
            case "commit" -> "Commit Message Guide";
            case "summarize" -> "Summarization Guide";
            default -> throw new IllegalArgumentException("Unexpected skill: " + skillName);
        };
    }
}
