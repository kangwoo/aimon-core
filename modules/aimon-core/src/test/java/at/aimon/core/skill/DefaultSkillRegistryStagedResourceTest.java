package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.skill.exception.SkillRepositoryException;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.repository.ClasspathSkillRepository;
import at.aimon.core.skill.repository.PathSkillRepository;
import at.aimon.core.skill.repository.SkillRepository;
import at.aimon.core.skill.repository.SkillSource;
import at.aimon.core.skill.repository.VfsSkillRepository;

/**
 * Tests that DefaultSkillRegistry enriches a loaded skill with its files and the {@link StagedResource} its directory
 * is
 * staged from (execution-environment design §4.4) — whatever repository the skill came from.
 */
class DefaultSkillRegistryStagedResourceTest {

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
    void getSkill_skillWithSubdirectories_stagedResourceCoversTheDirectory() {
        Optional<Skill> result = registry.getSkill(SKILL_NAME);

        assertThat(result).isPresent();
        StagedResource resource = result.get().getStagedResource().orElseThrow();
        assertThat(resource.getSourceFileSystem()).isSameAs(fileSystem);
        assertThat(resource.getSourceDir()).isEqualTo(SKILLS_DIR + "/" + SKILL_NAME);
        assertThat(resource.getName()).isEqualTo(SKILL_NAME);
        assertThat(resource.getFiles()).containsExactly("SKILL.md", "scripts/run.py", "templates/report.md");
        assertThat(resource.getContentKey()).isEqualTo(expectedKey(Map.of("SKILL.md", SKILL_MD_CONTENT,
                "scripts/run.py", "print('hello')", "templates/report.md", "# Report Template")));
        assertThat(resource.getTotalBytes()).isEqualTo(SKILL_MD_CONTENT.getBytes(StandardCharsets.UTF_8).length
                + "print('hello')".length() + "# Report Template".length());
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
    void reloadSkill_identicalContent_keepsTheContentKey() {
        String before = registry.getSkill(SKILL_NAME).orElseThrow().getStagedResource().orElseThrow().getContentKey();

        registry.reloadSkill(SKILL_NAME);

        assertThat(registry.getSkill(SKILL_NAME).orElseThrow().getStagedResource().orElseThrow().getContentKey())
                .isEqualTo(before);
    }

    @Test
    void reloadSkill_editedContent_changesTheContentKey() throws IOException {
        String before = registry.getSkill(SKILL_NAME).orElseThrow().getStagedResource().orElseThrow().getContentKey();

        writeFile(SKILLS_DIR + "/" + SKILL_NAME + "/scripts/run.py", "print('changed')");
        registry.reloadSkill(SKILL_NAME);

        assertThat(registry.getSkill(SKILL_NAME).orElseThrow().getStagedResource().orElseThrow().getContentKey())
                .isNotEqualTo(before);
    }

    @Test
    void getSkill_stageIgnoredFilesAreLeftOutOfTheHashAndTheFileSet() throws IOException {
        writeFile(SKILLS_DIR + "/" + SKILL_NAME + "/.stageignore", "templates/\n");
        registry.reloadSkill(SKILL_NAME);

        StagedResource resource = registry.getSkill(SKILL_NAME).orElseThrow().getStagedResource().orElseThrow();

        assertThat(resource.getFiles()).containsExactly(".stageignore", "SKILL.md", "scripts/run.py");
        assertThat(resource.getContentKey()).isEqualTo(expectedKey(Map.of(".stageignore", "templates/\n", "SKILL.md",
                SKILL_MD_CONTENT, "scripts/run.py", "print('hello')")));
    }

    @Test
    void getSkill_pathRepository_yieldsAStagedResourceOverTheHostDirectory() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("host-skills"));
        Files.createDirectories(root.resolve("demo/scripts"));
        Files.writeString(root.resolve("demo/SKILL.md"), SKILL_MD_CONTENT);
        Files.writeString(root.resolve("demo/scripts/run.py"), "print('hello')");

        StagedResource resource = new DefaultSkillRegistry(new PathSkillRepository(root), new MarkdownSkillParser())
                .getSkill(SKILL_NAME).orElseThrow().getStagedResource().orElseThrow();

        assertThat(resource.getSourceFileSystem().listRecursive(resource.getSourceDir()))
                .containsExactlyInAnyOrder("demo/SKILL.md", "demo/scripts/run.py");
        assertThat(resource.getContentKey())
                .isEqualTo(expectedKey(Map.of("SKILL.md", SKILL_MD_CONTENT, "scripts/run.py", "print('hello')")));
    }

    @Test
    void getSkill_classpathRepository_yieldsAStagedResourceOverTheClassPath() {
        // builtin/skills/commit/SKILL.md is a test-classpath fixture.
        StagedResource resource = new DefaultSkillRegistry(
                new ClasspathSkillRepository("builtin/skills", getClass().getClassLoader()), new MarkdownSkillParser())
                .getSkill("commit").orElseThrow().getStagedResource().orElseThrow();

        assertThat(resource.getSourceFileSystem().listRecursive(resource.getSourceDir()))
                .containsExactly("commit/SKILL.md");
        assertThat(resource.getFiles()).containsExactly("SKILL.md");
    }

    @Test
    void getSkill_repositoryWithoutStagingSource_failsNamingTheRepository() {
        SkillRepository sourceless = new VfsSkillRepository(fileSystem, SKILLS_DIR) {
            @Override
            public Optional<SkillSource> resolveSource(String skillName) {
                return Optional.empty();
            }
        };
        DefaultSkillRegistry broken = new DefaultSkillRegistry(sourceless, new MarkdownSkillParser());

        assertThatThrownBy(() -> broken.getSkill(SKILL_NAME)).isInstanceOf(SkillRepositoryException.class)
                .hasMessageContaining(sourceless.getClass().getName()).hasMessageContaining("'" + SKILL_NAME + "'");
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
    void getSkill_skillWithNoSubdirectoryFiles_stagedResourceHoldsOnlySkillMd() throws IOException {
        // create a minimal skill with only SKILL.md
        String minimalSkillName = "minimal";
        writeFile(SKILLS_DIR + "/" + minimalSkillName + "/SKILL.md",
                "---\nname: minimal\ndescription: Minimal skill\n---\n\nBody.");

        Optional<Skill> result = registry.getSkill(minimalSkillName);

        assertThat(result).isPresent();
        assertThat(result.get().getStagedResource().orElseThrow().getFiles()).containsExactly("SKILL.md");
        assertThat(result.get().getFiles()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** The content key computed by hand: SHA-256 over sorted {@code relPath \0 bytes \0}, first 16 hex chars. */
    private static String expectedKey(Map<String, String> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
                digest.update(file.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(file.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

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
