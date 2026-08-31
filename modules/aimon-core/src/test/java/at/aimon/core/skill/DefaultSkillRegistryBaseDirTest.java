package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

/** Tests that DefaultSkillRegistry enriches a loaded skill with baseDir and files. */
class DefaultSkillRegistryBaseDirTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem;
    private DefaultSkillRegistry registry;

    private static final String SKILLS_DIR = "skills";
    private static final String SKILL_NAME = "demo";
    private static final String SKILL_MD_CONTENT = "---\n" + "name: demo\n"
            + "description: Demo skill for testing baseDir and files enrichment\n" + "---\n" + "\n" + "# Demo Skill\n"
            + "\n" + "This skill is used for testing.";

    @BeforeEach
    void setUp() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();

        // Write SKILL.md
        writeFile(SKILLS_DIR + "/" + SKILL_NAME + "/SKILL.md", SKILL_MD_CONTENT);

        // Write templates/report.md
        writeFile(SKILLS_DIR + "/" + SKILL_NAME + "/templates/report.md", "# Report Template");

        // Write scripts/run.py
        writeFile(SKILLS_DIR + "/" + SKILL_NAME + "/scripts/run.py", "print('hello')");

        registry = new DefaultSkillRegistry(fileSystem, SKILLS_DIR);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    @Test
    void getSkill_skillWithSubdirectories_baseDirIsPresent() {
        Optional<Skill> result = registry.getSkill(SKILL_NAME);

        assertThat(result).isPresent();
        assertThat(result.get().getBaseDir()).isPresent();
        assertThat(result.get().getBaseDir().get()).isEqualTo(SKILLS_DIR + "/" + SKILL_NAME);
    }

    @Test
    void getSkill_skillWithSubdirectories_filesContainsTemplateFile() {
        Optional<Skill> result = registry.getSkill(SKILL_NAME);

        assertThat(result).isPresent();
        Map<String, String> files = result.get().getFiles();
        assertThat(files).containsKey("templates/report.md");
    }

    @Test
    void getSkill_skillWithSubdirectories_filesContainsScriptFile() {
        Optional<Skill> result = registry.getSkill(SKILL_NAME);

        assertThat(result).isPresent();
        Map<String, String> files = result.get().getFiles();
        assertThat(files).containsKey("scripts/run.py");
    }

    @Test
    void getSkill_skillWithSubdirectories_filesDoesNotContainSkillMd() {
        Optional<Skill> result = registry.getSkill(SKILL_NAME);

        assertThat(result).isPresent();
        Map<String, String> files = result.get().getFiles();
        assertThat(files).doesNotContainKey("SKILL.md");
    }

    @Test
    void getSkill_skillWithSubdirectories_filesHasCorrectFullVfsPaths() {
        Optional<Skill> result = registry.getSkill(SKILL_NAME);

        assertThat(result).isPresent();
        Map<String, String> files = result.get().getFiles();
        assertThat(files.get("templates/report.md")).isEqualTo(SKILLS_DIR + "/" + SKILL_NAME + "/templates/report.md");
        assertThat(files.get("scripts/run.py")).isEqualTo(SKILLS_DIR + "/" + SKILL_NAME + "/scripts/run.py");
    }

    @Test
    void getSkill_nonExistentSkill_returnsEmpty() {
        Optional<Skill> result = registry.getSkill("no-such-skill");

        assertThat(result).isEmpty();
    }

    @Test
    void reloadSkill_afterReload_baseDirStillPresent() {
        // warm the cache
        registry.getSkill(SKILL_NAME);

        // reload
        registry.reloadSkill(SKILL_NAME);

        Optional<Skill> result = registry.getSkill(SKILL_NAME);
        assertThat(result).isPresent();
        assertThat(result.get().getBaseDir()).isPresent();
        assertThat(result.get().getBaseDir().get()).isEqualTo(SKILLS_DIR + "/" + SKILL_NAME);
    }

    @Test
    void reloadSkill_afterReload_filesStillContainsExpectedEntries() {
        // warm the cache
        registry.getSkill(SKILL_NAME);

        // reload
        registry.reloadSkill(SKILL_NAME);

        Optional<Skill> result = registry.getSkill(SKILL_NAME);
        assertThat(result).isPresent();
        Map<String, String> files = result.get().getFiles();
        assertThat(files).containsKey("templates/report.md");
        assertThat(files).containsKey("scripts/run.py");
        assertThat(files).doesNotContainKey("SKILL.md");
    }

    @Test
    void reloadSkill_afterAddingNewFile_newFileAppearsInFiles() throws IOException {
        // warm the cache
        registry.getSkill(SKILL_NAME);

        // add a new file to the skill directory
        writeFile(SKILLS_DIR + "/" + SKILL_NAME + "/references/schema.md", "# Schema");

        // reload
        registry.reloadSkill(SKILL_NAME);

        Optional<Skill> result = registry.getSkill(SKILL_NAME);
        assertThat(result).isPresent();
        assertThat(result.get().getFiles()).containsKey("references/schema.md");
    }

    @Test
    void getSkill_skillWithNoSubdirectoryFiles_baseDirPresentButFilesEmpty() throws IOException {
        // create a minimal skill with only SKILL.md
        String minimalSkillName = "minimal";
        writeFile(SKILLS_DIR + "/" + minimalSkillName + "/SKILL.md",
                "---\nname: minimal\ndescription: Minimal skill\n---\n\nBody.");

        Optional<Skill> result = registry.getSkill(minimalSkillName);

        assertThat(result).isPresent();
        assertThat(result.get().getBaseDir()).isPresent();
        assertThat(result.get().getBaseDir().get()).isEqualTo(SKILLS_DIR + "/" + minimalSkillName);
        assertThat(result.get().getFiles()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void writeFile(String vfsPath, String content) throws IOException {
        // Ensure the parent directory exists on the real filesystem
        String relative = vfsPath;
        int lastSlash = relative.lastIndexOf('/');
        if (lastSlash > 0) {
            Path dir = tempDir.resolve(relative.substring(0, lastSlash));
            Files.createDirectories(dir);
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        fileSystem.write(vfsPath, new ByteArrayInputStream(bytes), bytes.length);
    }
}
