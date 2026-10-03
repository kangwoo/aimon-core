package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.skill.exception.SkillParseException;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.parser.SkillHookSetParser;
import at.aimon.core.skill.render.ShellArgumentTokenizer;
import at.aimon.core.tools.skill.SkillTool;

/**
 * One skill whose frontmatter the parser refuses must not take the listing down with it.
 *
 * <p>
 * The refusal used here is the one EE-12 makes loud: a shell action on an event that fires outside any execution,
 * where a skill hook has no execution environment to run in. The registry already skipped a skill it could not
 * <em>read</em> ({@code SkillRepositoryException}); a skill it could not <em>parse</em> ({@code SkillParseException},
 * a sibling type) used to escape {@code getAllSkills()} and break every caller built on it.
 */
@DisplayName("DefaultSkillRegistry: a skill that fails to parse is skipped, not fatal")
class DefaultSkillRegistryParseIsolationTest {

    private static final String SKILLS_DIR = "skills";

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem;
    private DefaultSkillRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();

        writeSkill("alpha", "");
        writeSkill("broken", "hooks:\n  onSessionStart:\n    - action: { type: shell, command: \"echo hi\" }\n");
        writeSkill("omega", "hooks:\n  onStart:\n    - action: { type: shell, command: \"echo hi\" }\n");

        registry = new DefaultSkillRegistry(fileSystem, SKILLS_DIR, new MarkdownSkillParser(
                new ShellArgumentTokenizer(), new SkillHookSetParser(new DefaultShellActionExecutor())));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    @Test
    @DisplayName("getAllSkills() returns the skills that parse and leaves the broken one out")
    void getAllSkills_skipsTheSkillThatFailsToParse() {
        assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactlyInAnyOrder("alpha", "omega");
    }

    @Test
    @DisplayName("the Skill tool still builds its definition from the skills that parse")
    void skillToolDefinitionSurvivesTheBrokenSkill() {
        final String description = new SkillTool(registry).getDefinition().getDescription();

        assertThat(description).contains("alpha").contains("omega").doesNotContain("broken");
    }

    @Test
    @DisplayName("reloadAll() keeps the skills that parse")
    void reloadAll_keepsTheSkillsThatParse() {
        registry.reloadAll();

        assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactlyInAnyOrder("alpha", "omega");
        assertThat(registry.getSkill("omega")).isPresent();
    }

    @Test
    @DisplayName("asking for the broken skill by name still reports why it is broken")
    void getSkill_ofTheBrokenSkill_throwsWithTheReason() {
        // Named lookups are not softened: whoever asks for this skill needs the reason, not an empty answer.
        assertThatThrownBy(() -> registry.getSkill("broken")).isInstanceOf(SkillParseException.class)
                .hasStackTraceContaining("onSessionStart").hasStackTraceContaining("outside any execution");
        assertThatThrownBy(() -> registry.reloadSkill("broken")).isInstanceOf(SkillParseException.class);
    }

    @Test
    @DisplayName("the broken skill is not cached, so fixing the file is enough")
    void brokenSkillIsNotCached() throws IOException {
        assertThat(registry.getAllSkills()).hasSize(2);

        writeSkill("broken", "hooks:\n  onStop:\n    - action: { type: shell, command: \"echo bye\" }\n");

        assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactlyInAnyOrder("alpha", "broken",
                "omega");
    }

    private void writeSkill(String name, String extraFrontmatter) throws IOException {
        final Path file = tempDir.resolve(SKILLS_DIR).resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: " + name + "\ndescription: " + name + " skill\n" + extraFrontmatter
                + "---\n\n# " + name + "\n");
    }
}
