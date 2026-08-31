package at.aimon.core.skill.repository;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

/** Unit tests for VfsSkillRepository. */
class VfsSkillRepositoryTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem;
    private VfsSkillRepository repository;
    private static final String SKILLS_BASE_PATH = "skills";

    @BeforeEach
    void setUp() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();

        repository = new VfsSkillRepository(fileSystem, SKILLS_BASE_PATH);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    @Test
    void testConstructor_NullFileSystem_ThrowsException() {
        assertThatThrownBy(() -> new VfsSkillRepository(null, SKILLS_BASE_PATH))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("File system cannot be null");
    }

    @Test
    void testConstructor_NullBasePath_ThrowsException() {
        assertThatThrownBy(() -> new VfsSkillRepository(fileSystem, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skills base path cannot be null");
    }

    @Test
    void testFindByName_SkillExists_ReturnsContent() throws IOException {
        // Arrange
        String skillName = "code-review";
        String skillContent = "---\n" + "name: code-review\n" + "description: Expert knowledge in code review\n"
                + "---\n" + "\n" + "# Code Review Skill\n" + "\n" + "## Overview\n"
                + "Expert guidance for code reviews.";

        createSkill(skillName, skillContent);

        // Act
        Optional<String> result = repository.findByName(skillName);

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(skillContent);
        assertThat(result.get()).contains("name: code-review");
        assertThat(result.get()).contains("# Code Review Skill");
    }

    @Test
    void testFindByName_SkillDoesNotExist_ReturnsEmpty() {
        // Arrange
        String nonExistentSkill = "nonexistent-skill";

        // Act
        Optional<String> result = repository.findByName(nonExistentSkill);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void testFindByName_NullSkillName_ThrowsException() {
        assertThatThrownBy(() -> repository.findByName(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill name cannot be null");
    }

    @Test
    void testFindByName_EmptySkillFile_ReturnsEmptyContent() throws IOException {
        // Arrange
        String skillName = "empty-skill";
        createSkill(skillName, "");

        // Act
        Optional<String> result = repository.findByName(skillName);

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void testFindByName_MultilineContent_PreservesFormatting() throws IOException {
        // Arrange
        String skillName = "multiline-skill";
        String skillContent = "---\n" + "name: multiline-skill\n" + "---\n" + "\n" + "# Skill Title\n" + "\n"
                + "Line 1\n" + "Line 2\n" + "Line 3\n" + "\n" + "## Section\n" + "More content";

        createSkill(skillName, skillContent);

        // Act
        Optional<String> result = repository.findByName(skillName);

        // Assert
        assertThat(result).isPresent();
        String content = result.get();
        assertThat(content.split("\n")).hasSizeGreaterThanOrEqualTo(11); // At least 11 lines
        assertThat(content).contains("Line 1\nLine 2\nLine 3");
    }

    @Test
    void testFindAllNames_NoSkills_ReturnsEmptyList() {
        // Act
        List<String> skillNames = repository.findAllNames();

        // Assert
        assertThat(skillNames).isEmpty();
    }

    @Test
    void testFindAllNames_MultipleSkills_ReturnsAllNames() throws IOException {
        // Arrange
        createSkill("code-review", "Code review content");
        createSkill("security", "Security content");
        createSkill("testing", "Testing content");

        // Act
        List<String> skillNames = repository.findAllNames();

        // Assert
        assertThat(skillNames).hasSize(3);
        assertThat(skillNames).containsExactlyInAnyOrder("code-review", "security", "testing");
    }

    @Test
    void testFindAllNames_SkillDirectoryWithoutSKILLFile_NotIncluded() throws IOException {
        // Arrange
        createSkill("valid-skill", "Valid content");

        // Create directory without SKILL.md
        Path invalidSkillDir = tempDir.resolve(SKILLS_BASE_PATH).resolve("invalid-skill");
        Files.createDirectories(invalidSkillDir);
        Files.write(invalidSkillDir.resolve("other-file.txt"), "Other content".getBytes());

        // Act
        List<String> skillNames = repository.findAllNames();

        // Assert
        assertThat(skillNames).hasSize(1);
        assertThat(skillNames).containsOnly("valid-skill");
        assertThat(skillNames).doesNotContain("invalid-skill");
    }

    @Test
    void testFindAllNames_FileInSkillsDirectory_NotIncluded() throws IOException {
        // Arrange
        createSkill("valid-skill", "Valid content");

        // Create a file directly in skills directory (not a skill)
        Path skillsDir = tempDir.resolve(SKILLS_BASE_PATH);
        Files.write(skillsDir.resolve("README.md"), "Not a skill".getBytes());

        // Act
        List<String> skillNames = repository.findAllNames();

        // Assert
        assertThat(skillNames).hasSize(1);
        assertThat(skillNames).containsOnly("valid-skill");
    }

    @Test
    void testFindAllNames_NestedDirectories_OnlyReturnsTopLevel() throws IOException {
        // Arrange
        createSkill("skill1", "Content 1");

        // Create nested directory structure with other files (not SKILL.md)
        Path skill1Dir = tempDir.resolve(SKILLS_BASE_PATH).resolve("skill1");
        Path nestedDir = skill1Dir.resolve("references");
        Files.createDirectories(nestedDir);
        Files.write(nestedDir.resolve("reference.md"), "Nested content".getBytes());

        // Act
        List<String> skillNames = repository.findAllNames();

        // Assert
        assertThat(skillNames).hasSize(1);
        assertThat(skillNames).containsOnly("skill1");
    }

    @Test
    void testExists_SkillExists_ReturnsTrue() throws IOException {
        // Arrange
        String skillName = "existing-skill";
        createSkill(skillName, "Content");

        // Act
        boolean exists = repository.exists(skillName);

        // Assert
        assertThat(exists).isTrue();
    }

    @Test
    void testExists_SkillDoesNotExist_ReturnsFalse() {
        // Arrange
        String nonExistentSkill = "nonexistent-skill";

        // Act
        boolean exists = repository.exists(nonExistentSkill);

        // Assert
        assertThat(exists).isFalse();
    }

    @Test
    void testExists_NullSkillName_ThrowsException() {
        assertThatThrownBy(() -> repository.exists(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill name cannot be null");
    }

    @Test
    void testExists_DirectoryWithoutSKILLFile_ReturnsFalse() throws IOException {
        // Arrange
        String skillName = "incomplete-skill";
        Path skillDir = tempDir.resolve(SKILLS_BASE_PATH).resolve(skillName);
        Files.createDirectories(skillDir);
        Files.write(skillDir.resolve("other-file.txt"), "Not SKILL.md".getBytes());

        // Act
        boolean exists = repository.exists(skillName);

        // Assert
        assertThat(exists).isFalse();
    }

    @Test
    void testFindByName_SkillWithSpecialCharacters_HandlesCorrectly() throws IOException {
        // Arrange
        String skillName = "skill-with-special_chars.123";
        String skillContent = "---\n" + "name: skill-with-special_chars.123\n" + "---\n"
                + "Content with special chars!";

        createSkill(skillName, skillContent);

        // Act
        Optional<String> result = repository.findByName(skillName);

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get()).contains("special chars!");
    }

    @Test
    void testFindByName_SkillWithUTF8Content_HandlesCorrectly() throws IOException {
        // Arrange
        String skillName = "utf8-skill";
        String skillContent = "---\n" + "name: utf8-skill\n" + "---\n" + "한글 테스트\n" + "日本語\n" + "中文\n" + "Emoji: 🚀";

        createSkill(skillName, skillContent);

        // Act
        Optional<String> result = repository.findByName(skillName);

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get()).contains("한글 테스트");
        assertThat(result.get()).contains("日本語");
        assertThat(result.get()).contains("中文");
        assertThat(result.get()).contains("🚀");
    }

    @Test
    void testFindAllNames_EmptySkillsDirectory_ReturnsEmptyList() throws IOException {
        // Arrange - create empty skills directory
        Files.createDirectories(tempDir.resolve(SKILLS_BASE_PATH));

        // Act
        List<String> skillNames = repository.findAllNames();

        // Assert
        assertThat(skillNames).isEmpty();
    }

    @Test
    void testFindAllNames_NonExistentSkillsDirectory_ReturnsEmptyList() {
        // Arrange - repository with non-existent base path
        VfsSkillRepository badRepository = new VfsSkillRepository(fileSystem, "nonexistent-directory");

        // Act
        List<String> skillNames = badRepository.findAllNames();

        // Assert - should return empty list instead of throwing exception
        assertThat(skillNames).isEmpty();
    }

    @Test
    void testFindByName_LargeSkillFile_HandlesCorrectly() throws IOException {
        // Arrange - create large skill file
        String skillName = "large-skill";
        StringBuilder largeContent = new StringBuilder();
        largeContent.append("---\nname: large-skill\n---\n\n");

        // Add 1000 lines of content
        for (int i = 0; i < 1000; i++) {
            largeContent.append("Line ").append(i).append(": This is test content.\n");
        }

        createSkill(skillName, largeContent.toString());

        // Act
        Optional<String> result = repository.findByName(skillName);

        // Assert
        assertThat(result).isPresent();
        String content = result.get();
        assertThat(content).contains("Line 0: This is test content.");
        assertThat(content).contains("Line 999: This is test content.");
        assertThat(content.split("\n")).hasSizeGreaterThan(1000);
    }

    @Test
    void testFindRootFiles_RootFilesExist_ReturnsRootFilesMap() throws IOException {
        // Arrange
        String skillName = "test-skill";
        createSkill(skillName, "---\nname: test-skill\n---\nContent");
        createRootFile(skillName, "config.yaml", "key: value");
        createRootFile(skillName, "helper.py", "def helper(): pass");

        // Act
        java.util.Map<String, String> rootFiles = repository.findRootFiles(skillName);

        // Assert
        assertThat(rootFiles).hasSize(2);
        assertThat(rootFiles).containsKey("config.yaml");
        assertThat(rootFiles).containsKey("helper.py");
        assertThat(rootFiles.get("config.yaml")).isEqualTo("skills/test-skill/config.yaml");
        assertThat(rootFiles.get("helper.py")).isEqualTo("skills/test-skill/helper.py");
    }

    @Test
    void testFindRootFiles_NoRootFiles_ReturnsEmptyMap() throws IOException {
        // Arrange
        String skillName = "test-skill";
        createSkill(skillName, "---\nname: test-skill\n---\nContent");

        // Act
        java.util.Map<String, String> rootFiles = repository.findRootFiles(skillName);

        // Assert
        assertThat(rootFiles).isEmpty();
    }

    @Test
    void testFindRootFiles_ExcludesSKILLMD() throws IOException {
        // Arrange
        String skillName = "test-skill";
        createSkill(skillName, "---\nname: test-skill\n---\nContent");
        createRootFile(skillName, "config.yaml", "key: value");

        // Act
        java.util.Map<String, String> rootFiles = repository.findRootFiles(skillName);

        // Assert
        assertThat(rootFiles).hasSize(1);
        assertThat(rootFiles).containsKey("config.yaml");
        assertThat(rootFiles).doesNotContainKey("SKILL.md");
    }

    @Test
    void testFindRootFiles_ExcludesSubdirectories() throws IOException {
        // Arrange
        String skillName = "test-skill";
        createSkill(skillName, "---\nname: test-skill\n---\nContent");
        createRootFile(skillName, "config.yaml", "key: value");
        createSkillFile(skillName, "scripts", "script.py", "print('hello')");

        // Act
        java.util.Map<String, String> rootFiles = repository.findRootFiles(skillName);

        // Assert
        assertThat(rootFiles).hasSize(1);
        assertThat(rootFiles).containsKey("config.yaml");
        // Should not include files from subdirectories
    }

    @Test
    void testFindRootFiles_NullSkillName_ThrowsException() {
        assertThatThrownBy(() -> repository.findRootFiles(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill name cannot be null");
    }

    @Test
    void testFindRootFiles_NonExistentSkill_ReturnsEmptyMap() {
        // Arrange
        String nonExistentSkill = "nonexistent-skill";

        // Act
        java.util.Map<String, String> rootFiles = repository.findRootFiles(nonExistentSkill);

        // Assert
        assertThat(rootFiles).isEmpty();
    }

    @Test
    void testFindScripts_ScriptsExist_ReturnsScriptMap() throws IOException {
        // Arrange
        String skillName = "test-skill";
        createSkill(skillName, "---\nname: test-skill\n---\nContent");
        createSkillFile(skillName, "scripts", "script1.py", "print('hello')");
        createSkillFile(skillName, "scripts", "script2.sh", "echo hello");

        // Act
        java.util.Map<String, String> scripts = repository.findScripts(skillName);

        // Assert
        assertThat(scripts).hasSize(2);
        assertThat(scripts).containsKey("script1.py");
        assertThat(scripts).containsKey("script2.sh");
    }

    @Test
    void testFindScripts_NoScriptsDirectory_ReturnsEmptyMap() throws IOException {
        // Arrange
        String skillName = "test-skill";
        createSkill(skillName, "---\nname: test-skill\n---\nContent");

        // Act
        java.util.Map<String, String> scripts = repository.findScripts(skillName);

        // Assert
        assertThat(scripts).isEmpty();
    }

    @Test
    void testFindReferences_ReferencesExist_ReturnsReferenceMap() throws IOException {
        // Arrange
        String skillName = "test-skill";
        createSkill(skillName, "---\nname: test-skill\n---\nContent");
        createSkillFile(skillName, "references", "api.md", "# API Reference");
        createSkillFile(skillName, "references", "guide.md", "# Guide");

        // Act
        java.util.Map<String, String> references = repository.findReferences(skillName);

        // Assert
        assertThat(references).hasSize(2);
        assertThat(references).containsKey("api.md");
        assertThat(references).containsKey("guide.md");
    }

    @Test
    void testFindReferences_NoReferencesDirectory_ReturnsEmptyMap() throws IOException {
        // Arrange
        String skillName = "test-skill";
        createSkill(skillName, "---\nname: test-skill\n---\nContent");

        // Act
        java.util.Map<String, String> references = repository.findReferences(skillName);

        // Assert
        assertThat(references).isEmpty();
    }

    @Test
    void testFindAssets_AssetsExist_ReturnsAssetMap() throws IOException {
        // Arrange
        String skillName = "test-skill";
        createSkill(skillName, "---\nname: test-skill\n---\nContent");
        createSkillFile(skillName, "assets", "loader.json", "{\"key\": \"value\"}");
        createSkillFile(skillName, "assets", "config.yaml", "key: value");

        // Act
        java.util.Map<String, String> assets = repository.findAssets(skillName);

        // Assert
        assertThat(assets).hasSize(2);
        assertThat(assets).containsKey("loader.json");
        assertThat(assets).containsKey("config.yaml");
    }

    @Test
    void testFindAssets_NoAssetsDirectory_ReturnsEmptyMap() throws IOException {
        // Arrange
        String skillName = "test-skill";
        createSkill(skillName, "---\nname: test-skill\n---\nContent");

        // Act
        java.util.Map<String, String> assets = repository.findAssets(skillName);

        // Assert
        assertThat(assets).isEmpty();
    }

    @Test
    void testFindScripts_NullSkillName_ThrowsException() {
        assertThatThrownBy(() -> repository.findScripts(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill name cannot be null");
    }

    @Test
    void testFindReferences_NullSkillName_ThrowsException() {
        assertThatThrownBy(() -> repository.findReferences(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill name cannot be null");
    }

    @Test
    void testFindAssets_NullSkillName_ThrowsException() {
        assertThatThrownBy(() -> repository.findAssets(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill name cannot be null");
    }

    // --- resolveBaseDir tests ---

    @Test
    void testResolveBaseDir_SkillDirExists_ReturnsSkillsSlashName() throws IOException {
        // Arrange
        String skillName = "my-skill";
        createSkill(skillName, "---\nname: my-skill\n---\nContent");

        // Act
        Optional<String> result = repository.resolveBaseDir(skillName);

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("skills/my-skill");
    }

    @Test
    void testResolveBaseDir_NonExistentSkill_ReturnsEmpty() {
        // Act
        Optional<String> result = repository.resolveBaseDir("does-not-exist");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void testResolveBaseDir_NullSkillName_ThrowsException() {
        assertThatThrownBy(() -> repository.resolveBaseDir(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill name cannot be null");
    }

    // --- findAllFiles tests ---

    @Test
    void testFindAllFiles_SkillWithSubdirFiles_ReturnsRelativeKeysAndFullPaths() throws IOException {
        // Arrange
        String skillName = "full-skill";
        createSkill(skillName, "---\nname: full-skill\n---\nContent");
        createRootFile(skillName, "config.yaml", "key: value");
        createSkillFile(skillName, "templates", "report.md", "# Report");
        createSkillFile(skillName, "scripts", "run.py", "print('run')");

        // Act
        java.util.Map<String, String> files = repository.findAllFiles(skillName);

        // Assert – three files, SKILL.md excluded
        assertThat(files).hasSize(3);

        assertThat(files).containsKey("config.yaml");
        assertThat(files.get("config.yaml")).isEqualTo("skills/full-skill/config.yaml");

        assertThat(files).containsKey("templates/report.md");
        assertThat(files.get("templates/report.md")).isEqualTo("skills/full-skill/templates/report.md");

        assertThat(files).containsKey("scripts/run.py");
        assertThat(files.get("scripts/run.py")).isEqualTo("skills/full-skill/scripts/run.py");

        assertThat(files).doesNotContainKey("SKILL.md");
    }

    @Test
    void testFindAllFiles_ExcludesSKILLMD() throws IOException {
        // Arrange
        String skillName = "exclude-skill-md";
        createSkill(skillName, "---\nname: exclude-skill-md\n---\nContent");

        // Act
        java.util.Map<String, String> files = repository.findAllFiles(skillName);

        // Assert – only SKILL.md exists, so the map must be empty
        assertThat(files).isEmpty();
        assertThat(files).doesNotContainKey("SKILL.md");
    }

    @Test
    void testFindAllFiles_IncludesArbitrarySubdirectories() throws IOException {
        // Arrange
        String skillName = "arbitrary-dirs-skill";
        createSkill(skillName, "---\nname: arbitrary-dirs-skill\n---\nContent");
        createSkillFile(skillName, "templates", "report.md", "# Report");
        createSkillFile(skillName, "references", "schema.md", "# Schema");

        // Act
        java.util.Map<String, String> files = repository.findAllFiles(skillName);

        // Assert
        assertThat(files).containsKey("templates/report.md");
        assertThat(files).containsKey("references/schema.md");
        assertThat(files).doesNotContainKey("SKILL.md");
    }

    @Test
    void testFindAllFiles_NonExistentSkill_ReturnsEmptyMap() {
        // Act
        java.util.Map<String, String> files = repository.findAllFiles("nonexistent-skill");

        // Assert
        assertThat(files).isEmpty();
    }

    @Test
    void testFindAllFiles_NullSkillName_ThrowsException() {
        assertThatThrownBy(() -> repository.findAllFiles(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill name cannot be null");
    }

    @Test
    void testFindAllFiles_SkillWithOnlyRootFiles_ReturnsRootFilesWithSimpleKeys() throws IOException {
        // Arrange
        String skillName = "root-only-skill";
        createSkill(skillName, "---\nname: root-only-skill\n---\nContent");
        createRootFile(skillName, "config.yaml", "key: value");
        createRootFile(skillName, "README.txt", "readme");

        // Act
        java.util.Map<String, String> files = repository.findAllFiles(skillName);

        // Assert
        assertThat(files).hasSize(2);
        assertThat(files).containsKey("config.yaml");
        assertThat(files).containsKey("README.txt");
        assertThat(files.get("config.yaml")).isEqualTo("skills/root-only-skill/config.yaml");
        assertThat(files.get("README.txt")).isEqualTo("skills/root-only-skill/README.txt");
        assertThat(files).doesNotContainKey("SKILL.md");
    }

    /**
     * Helper method to create a skill in the test filesystem.
     *
     * @param skillName
     *            The name of the skill
     * @param content
     *            The content of SKILL.md file
     * @throws IOException
     *             if file creation fails
     */
    private void createSkill(String skillName, String content) throws IOException {
        Path skillDir = tempDir.resolve(SKILLS_BASE_PATH).resolve(skillName);
        Files.createDirectories(skillDir);

        byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String skillFilePath = SKILLS_BASE_PATH + "/" + skillName + "/SKILL.md";
        fileSystem.write(skillFilePath, new ByteArrayInputStream(contentBytes), contentBytes.length);
    }

    /**
     * Helper method to create a file in a skill's subdirectory.
     *
     * @param skillName
     *            The name of the skill
     * @param directory
     *            The subdirectory (e.g., "scripts", "references", "assets")
     * @param filename
     *            The filename
     * @param content
     *            The file content
     * @throws IOException
     *             if file creation fails
     */
    private void createSkillFile(String skillName, String directory, String filename, String content)
            throws IOException {
        Path fileDir = tempDir.resolve(SKILLS_BASE_PATH).resolve(skillName).resolve(directory);
        Files.createDirectories(fileDir);

        byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String filePath = SKILLS_BASE_PATH + "/" + skillName + "/" + directory + "/" + filename;
        fileSystem.write(filePath, new ByteArrayInputStream(contentBytes), contentBytes.length);
    }

    /**
     * Helper method to create a file in a skill's root directory (same level as SKILL.md).
     *
     * @param skillName
     *            The name of the skill
     * @param filename
     *            The filename
     * @param content
     *            The file content
     * @throws IOException
     *             if file creation fails
     */
    private void createRootFile(String skillName, String filename, String content) throws IOException {
        Path skillDir = tempDir.resolve(SKILLS_BASE_PATH).resolve(skillName);
        Files.createDirectories(skillDir);

        byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String filePath = SKILLS_BASE_PATH + "/" + skillName + "/" + filename;
        fileSystem.write(filePath, new ByteArrayInputStream(contentBytes), contentBytes.length);
    }
}
