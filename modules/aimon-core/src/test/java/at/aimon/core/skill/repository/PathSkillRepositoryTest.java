package at.aimon.core.skill.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class PathSkillRepositoryTest {

    @TempDir
    Path tempDir;

    private PathSkillRepository repository;
    private Logger repositoryLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws IOException {
        // Create skill directory structure:
        // tempDir/
        // commit/
        // SKILL.md
        // config.yaml (root file)
        // scripts/
        // run.sh
        // references/
        // guide.md
        // assets/
        // icon.png
        // deploy/
        // SKILL.md

        final Path commitDir = Files.createDirectories(tempDir.resolve("commit"));
        Files.writeString(commitDir.resolve("SKILL.md"), "# Commit Skill");
        Files.writeString(commitDir.resolve("config.yaml"), "key: value");

        final Path scriptsDir = Files.createDirectories(commitDir.resolve("scripts"));
        Files.writeString(scriptsDir.resolve("run.sh"), "#!/bin/bash\necho hello");

        final Path refsDir = Files.createDirectories(commitDir.resolve("references"));
        Files.writeString(refsDir.resolve("guide.md"), "# Reference Guide");

        final Path assetsDir = Files.createDirectories(commitDir.resolve("assets"));
        Files.writeString(assetsDir.resolve("icon.png"), "fake-png-data");

        final Path deployDir = Files.createDirectories(tempDir.resolve("deploy"));
        Files.writeString(deployDir.resolve("SKILL.md"), "# Deploy Skill");

        repository = new PathSkillRepository(tempDir);

        repositoryLogger = (Logger) LoggerFactory.getLogger(PathSkillRepository.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        repositoryLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (repositoryLogger != null && logAppender != null) {
            repositoryLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    @Test
    void findByName_returnsContentWhenExists() {
        final Optional<String> result = repository.findByName("commit");

        assertTrue(result.isPresent());
        assertEquals("# Commit Skill", result.get());
    }

    @Test
    void findByName_returnsEmptyWhenNotExists() {
        final Optional<String> result = repository.findByName("nonexistent");

        assertTrue(result.isEmpty());
    }

    @Test
    void findByName_throwsOnNullName() {
        assertThrows(NullPointerException.class, () -> repository.findByName(null));
    }

    @Test
    void findAllNames_returnsAllSkillsWithSkillMd() {
        final List<String> names = repository.findAllNames();

        assertEquals(2, names.size());
        assertTrue(names.contains("commit"));
        assertTrue(names.contains("deploy"));
    }

    @Test
    void findAllNames_excludesDirectoriesWithoutSkillMd() throws IOException {
        Files.createDirectories(tempDir.resolve("incomplete"));

        final List<String> names = repository.findAllNames();

        assertEquals(2, names.size());
        assertFalse(names.contains("incomplete"));
    }

    @Test
    void findAllNames_returnsEmptyForNonexistentDirectory() {
        final PathSkillRepository emptyRepo = new PathSkillRepository(tempDir.resolve("nonexistent"));

        final List<String> names = emptyRepo.findAllNames();

        assertTrue(names.isEmpty());
    }

    @Test
    void exists_returnsTrueWhenSkillExists() {
        assertTrue(repository.exists("commit"));
    }

    @Test
    void exists_returnsFalseWhenSkillDoesNotExist() {
        assertFalse(repository.exists("nonexistent"));
    }

    @Test
    void exists_throwsOnNullName() {
        assertThrows(NullPointerException.class, () -> repository.exists(null));
    }

    @Test
    void findRootFiles_returnsDirectChildFilesExcludingSkillMd() {
        final Map<String, String> rootFiles = repository.findRootFiles("commit");

        assertEquals(1, rootFiles.size());
        assertTrue(rootFiles.containsKey("config.yaml"));
        assertTrue(rootFiles.get("config.yaml").endsWith("config.yaml"));
    }

    @Test
    void findRootFiles_returnsEmptyForNonexistentSkill() {
        final Map<String, String> rootFiles = repository.findRootFiles("nonexistent");

        assertTrue(rootFiles.isEmpty());
    }

    @Test
    void findRootFiles_returnsEmptyWhenNoRootFiles() {
        final Map<String, String> rootFiles = repository.findRootFiles("deploy");

        assertTrue(rootFiles.isEmpty());
    }

    @Test
    void findScripts_returnsScriptFiles() {
        final Map<String, String> scripts = repository.findScripts("commit");

        assertEquals(1, scripts.size());
        assertTrue(scripts.containsKey("run.sh"));
        assertTrue(scripts.get("run.sh").endsWith("run.sh"));
    }

    @Test
    void findScripts_returnsEmptyWhenNoScriptsDirectory() {
        final Map<String, String> scripts = repository.findScripts("deploy");

        assertTrue(scripts.isEmpty());
    }

    @Test
    void findReferences_returnsReferenceFiles() {
        final Map<String, String> references = repository.findReferences("commit");

        assertEquals(1, references.size());
        assertTrue(references.containsKey("guide.md"));
    }

    @Test
    void findReferences_returnsEmptyWhenNoReferencesDirectory() {
        final Map<String, String> references = repository.findReferences("deploy");

        assertTrue(references.isEmpty());
    }

    @Test
    void findAssets_returnsAssetFiles() {
        final Map<String, String> assets = repository.findAssets("commit");

        assertEquals(1, assets.size());
        assertTrue(assets.containsKey("icon.png"));
    }

    @Test
    void findAssets_returnsEmptyWhenNoAssetsDirectory() {
        final Map<String, String> assets = repository.findAssets("deploy");

        assertTrue(assets.isEmpty());
    }

    @Test
    void findScripts_recursivelyFindsNestedFiles() throws IOException {
        final Path nestedDir = Files.createDirectories(tempDir.resolve("commit").resolve("scripts").resolve("sub"));
        Files.writeString(nestedDir.resolve("nested.sh"), "#!/bin/bash\necho nested");

        final Map<String, String> scripts = repository.findScripts("commit");

        assertEquals(2, scripts.size());
        assertTrue(scripts.containsKey("run.sh"));
        assertTrue(scripts.containsKey("sub/nested.sh"));
    }

    @Test
    void findScripts_usesRelativePathAsKeyToAvoidCollision() throws IOException {
        final Path nestedDir = Files.createDirectories(tempDir.resolve("commit").resolve("scripts").resolve("sub"));
        Files.writeString(nestedDir.resolve("run.sh"), "#!/bin/bash\necho nested-run");

        final Map<String, String> scripts = repository.findScripts("commit");

        assertEquals(2, scripts.size());
        assertTrue(scripts.containsKey("run.sh"));
        assertTrue(scripts.containsKey("sub/run.sh"));
    }

    @Test
    void constructor_throwsOnNullBasePath() {
        assertThrows(NullPointerException.class, () -> new PathSkillRepository(null));
    }

    @Test
    void findByName_returnsEmptyForPathTraversal() {
        final Optional<String> result = repository.findByName("../../etc/passwd");

        assertTrue(result.isEmpty());
    }

    @Test
    void exists_returnsFalseForPathTraversal() {
        assertFalse(repository.exists("../../../etc/passwd"));
    }

    @Test
    void findRootFiles_returnsEmptyForPathTraversal() {
        final Map<String, String> rootFiles = repository.findRootFiles("../../etc");

        assertTrue(rootFiles.isEmpty());
    }

    @Test
    void findScripts_returnsEmptyForPathTraversal() {
        final Map<String, String> scripts = repository.findScripts("../../etc");

        assertTrue(scripts.isEmpty());
    }

    @Test
    void findAllNames_ignoresStrayMarkdownFileAndWarns() throws IOException {
        Files.writeString(tempDir.resolve("legacy.md"), "---\nname: legacy\n---\nbody");

        final List<String> names = repository.findAllNames();

        assertEquals(2, names.size());
        assertTrue(names.contains("commit"));
        assertTrue(names.contains("deploy"));
        assertFalse(names.contains("legacy"));
        assertFalse(names.contains("legacy.md"));

        final boolean warned = logAppender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("legacy.md") && e.getFormattedMessage().contains("SKILL.md"));
        assertTrue(warned, "Expected a WARN log mentioning the stray markdown filename and SKILL.md");
    }

    @Test
    void findAllNames_doesNotWarnForDirectoriesOrNonMarkdownFiles() throws IOException {
        Files.writeString(tempDir.resolve("README.txt"), "hi");
        Files.createDirectories(tempDir.resolve("incomplete"));

        repository.findAllNames();

        final boolean anyWarn = logAppender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN);
        assertFalse(anyWarn, "Plain files and incomplete directories must not trigger the stray-markdown warning");
    }

    // -----------------------------------------------------------------------
    // resolveSource (staging source, execution-environment design §4.4)
    // -----------------------------------------------------------------------

    @Test
    void resolveSource_readsTheSkillFilesThroughAReadOnlyFileSystem() throws IOException {
        final SkillSource source = repository.resolveSource("commit").orElseThrow();

        assertThat(source.getDirectory()).isEqualTo("commit");
        assertThat(source.getFileSystem().listRecursive(source.getDirectory())).containsExactlyInAnyOrder(
                "commit/SKILL.md", "commit/config.yaml", "commit/scripts/run.sh", "commit/references/guide.md",
                "commit/assets/icon.png");
        try (InputStream in = source.getFileSystem().read("commit/scripts/run.sh")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("#!/bin/bash\necho hello");
        }
        assertThatThrownBy(() -> source.getFileSystem().write("commit/x.txt", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> source.getFileSystem().delete("commit/SKILL.md"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(tempDir.resolve("commit/x.txt")).doesNotExist();
    }

    @Test
    void resolveSource_returnsEmptyForNonexistentSkill() {
        assertThat(repository.resolveSource("nonexistent")).isEmpty();
    }

    @Test
    void resolveSource_returnsEmptyForPathTraversal() {
        assertThat(repository.resolveSource("../../etc/passwd")).isEmpty();
        assertThat(repository.resolveSource("../x")).isEmpty();
    }

    @Test
    void resolveSource_throwsOnNullSkillName() {
        assertThatThrownBy(() -> repository.resolveSource(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void resolveSource_presentForSkillWithOnlySkillMd() {
        final SkillSource source = repository.resolveSource("deploy").orElseThrow();

        assertThat(source.getFileSystem().listRecursive(source.getDirectory())).containsExactly("deploy/SKILL.md");
    }

    /**
     * A read-only skills directory (a ConfigMap volume, a read-only root filesystem) keeps working: the source neither
     * creates nor checks writability, the registry loads and hashes the skill, and a local environment stages it.
     */
    @Test
    void readOnlyRoot_loadsScansAndStages(@TempDir Path workspace) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
        assumeFalse("root".equals(System.getProperty("user.name")), "root ignores permission bits");
        final Path readOnly = Files.createDirectories(workspace.resolve("ro-skills"));
        final Path skillDir = Files.createDirectories(readOnly.resolve("ro"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: ro\ndescription: read-only\n---\nBody");
        Files.writeString(Files.createDirectories(skillDir.resolve("scripts")).resolve("x.sh"), "echo ro");
        makeReadOnly(readOnly);
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace.resolve("work")).contentSearch(false).build()) {
            final PathSkillRepository readOnlyRepository = new PathSkillRepository(readOnly);
            final Skill skill = new DefaultSkillRegistry(readOnlyRepository, new MarkdownSkillParser()).getSkill("ro")
                    .orElseThrow();
            final StagedResource resource = skill.getStagedResource().orElseThrow();

            assertThat(resource.getFiles()).containsExactly("SKILL.md", "scripts/x.sh");
            final String staged = provider
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("ro")).build())
                    .stage(resource);
            assertThat(Path.of(staged, "scripts", "x.sh")).hasContent("echo ro");
        } finally {
            makeWritable(readOnly);
        }
    }

    @Test
    void missingRoot_constructsWithoutCreatingTheDirectory(@TempDir Path parent) {
        final Path missing = parent.resolve("no-such-skills");

        final PathSkillRepository missingRepository = new PathSkillRepository(missing);

        assertThat(missingRepository.findAllNames()).isEmpty();
        assertThat(missingRepository.resolveSource("any")).isEmpty();
        assertThat(missing).doesNotExist();
    }

    private static void makeReadOnly(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                Files.setPosixFilePermissions(path,
                        Files.isDirectory(path)
                                ? PosixFilePermissions.fromString("r-xr-xr-x")
                                : PosixFilePermissions.fromString("r--r--r--"));
            }
        }
    }

    private static void makeWritable(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
    }

    // -----------------------------------------------------------------------
    // findAllFiles
    // -----------------------------------------------------------------------

    @Test
    void findAllFiles_returnsRelativeKeysAndAbsoluteValues() {
        final Map<String, String> files = repository.findAllFiles("commit");

        // SKILL.md is excluded; config.yaml, scripts/run.sh, references/guide.md, assets/icon.png remain
        assertThat(files).containsKey("config.yaml");
        assertThat(files).containsKey("scripts/run.sh");
        assertThat(files).containsKey("references/guide.md");
        assertThat(files).containsKey("assets/icon.png");
        assertThat(files).doesNotContainKey("SKILL.md");
    }

    @Test
    void findAllFiles_valuesAreOsAbsolutePaths() {
        final Map<String, String> files = repository.findAllFiles("commit");

        assertThat(files.get("config.yaml"))
                .isEqualTo(tempDir.resolve("commit").resolve("config.yaml").toAbsolutePath().toString());
        assertThat(files.get("scripts/run.sh"))
                .isEqualTo(tempDir.resolve("commit").resolve("scripts").resolve("run.sh").toAbsolutePath().toString());
        assertThat(files.get("references/guide.md")).isEqualTo(
                tempDir.resolve("commit").resolve("references").resolve("guide.md").toAbsolutePath().toString());
        assertThat(files.get("assets/icon.png"))
                .isEqualTo(tempDir.resolve("commit").resolve("assets").resolve("icon.png").toAbsolutePath().toString());
    }

    @Test
    void findAllFiles_excludesSkillMdAtRoot() {
        final Map<String, String> files = repository.findAllFiles("commit");

        assertThat(files).doesNotContainKey("SKILL.md");
    }

    @Test
    void findAllFiles_returnsEmptyForSkillWithOnlySkillMd() {
        // deploy skill has only SKILL.md
        final Map<String, String> files = repository.findAllFiles("deploy");

        assertThat(files).isEmpty();
    }

    @Test
    void findAllFiles_returnsEmptyForNonexistentSkill() {
        final Map<String, String> files = repository.findAllFiles("nonexistent");

        assertThat(files).isEmpty();
    }

    @Test
    void findAllFiles_returnsEmptyForPathTraversal() {
        final Map<String, String> files = repository.findAllFiles("../../etc");

        assertThat(files).isEmpty();
    }

    @Test
    void findAllFiles_throwsOnNullSkillName() {
        assertThatThrownBy(() -> repository.findAllFiles(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void findAllFiles_includesArbitraryNestedSubdirectories() throws IOException {
        // Add a "templates" directory not covered by findScripts/findReferences/findAssets
        final Path templatesDir = Files.createDirectories(tempDir.resolve("commit").resolve("templates"));
        Files.writeString(templatesDir.resolve("report.md"), "# Report Template");

        final Map<String, String> files = repository.findAllFiles("commit");

        assertThat(files).containsKey("templates/report.md");
        assertThat(files.get("templates/report.md")).endsWith("report.md");
    }

    @Test
    void findAllFiles_slashSeparatedKeysOnAllPlatforms() {
        // Verify that keys use '/' regardless of OS path separator
        final Map<String, String> files = repository.findAllFiles("commit");

        assertThat(files.keySet()).allSatisfy(key -> assertThat(key).doesNotContain("\\"));
    }

    @Test
    void findAllFiles_totalCountMatchesExpectedFilesForCommitSkill() {
        final Map<String, String> files = repository.findAllFiles("commit");

        // config.yaml + scripts/run.sh + references/guide.md + assets/icon.png = 4
        assertThat(files).hasSize(4);
    }
}
