package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.environment.DelegatingFileSystem;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.skill.exception.SkillRepositoryException;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.repository.PathSkillRepository;
import at.aimon.core.skill.repository.SkillRepository;
import at.aimon.core.skill.repository.SkillSource;
import at.aimon.core.tools.skill.SkillTool;

/**
 * The link rule for staging a host-path skill (execution-environment design §4.4): a symbolic link is followed when
 * its real path lies inside the skills directory or an allowed link root, refused otherwise, and a skill whose
 * directory scans to zero files does not load.
 */
@DisplayName("Staging a host-path skill through symbolic links")
class SkillLinkStagingTest {

    private static final String SKILL_MD = "---\nname: foo\ndescription: Linked skill\n---\n\nBody.";

    @TempDir
    Path tempDir;

    private Path skills;
    private LocalExecutionEnvironmentProvider provider;
    private ExecutionEnvironment environment;

    @BeforeEach
    void setUp() throws IOException {
        skills = Files.createDirectories(tempDir.resolve("skills"));
        provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(Files.createDirectories(tempDir.resolve("workspace"))).contentSearch(false).build();
        environment = provider
                .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.of("agent:default")).build());
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    @DisplayName("a skill directory linked to a directory inside the skills root stages with its files")
    void linkedSkillDirectoryInsideRoot() throws IOException {
        final Path real = writeSkill(skills.resolve(".shared/foo"));
        link(skills.resolve("foo"), real);

        final StagedResource resource = stagedResource(new PathSkillRepository(skills));
        assertThat(resource.getFiles()).containsExactly("SKILL.md", "scripts/run.sh");

        final String stagedDir = environment.stage(resource);
        assertThat(readText(stagedDir + "/scripts/run.sh")).isEqualTo("echo hi");
    }

    @Test
    @DisplayName("a linked subdirectory inside a real skill directory is followed")
    void linkedSubdirectoryInsideSkill() throws IOException {
        Files.createDirectories(skills.resolve("foo"));
        Files.writeString(skills.resolve("foo/SKILL.md"), SKILL_MD);
        final Path shared = Files.createDirectories(skills.resolve(".shared/scripts"));
        Files.writeString(shared.resolve("common.sh"), "echo common");
        link(skills.resolve("foo/scripts"), shared);

        final StagedResource resource = stagedResource(new PathSkillRepository(skills));
        assertThat(resource.getFiles()).containsExactly("SKILL.md", "scripts/common.sh");

        final String stagedDir = environment.stage(resource);
        assertThat(readText(stagedDir + "/scripts/common.sh")).isEqualTo("echo common");
    }

    @Test
    @DisplayName("a link that resolves outside the skills root is refused with an error naming it")
    void linkOutsideRootRefused() throws IOException {
        final Path real = writeSkill(tempDir.resolve("elsewhere/foo"));
        link(skills.resolve("foo"), real);

        final DefaultSkillRegistry registry = new DefaultSkillRegistry(new PathSkillRepository(skills),
                new MarkdownSkillParser());

        assertThatThrownBy(() -> registry.getSkill("foo")).isInstanceOf(SkillRepositoryException.class)
                .hasMessageContaining("'foo'").hasMessageContaining("outside the root")
                .hasMessageContaining("allowed link roots");
    }

    @Test
    @DisplayName("a link into an allowed link root is followed")
    void allowedRootPermitsLink() throws IOException {
        final Path elsewhere = tempDir.resolve("elsewhere");
        final Path real = writeSkill(elsewhere.resolve("foo"));
        link(skills.resolve("foo"), real);

        final StagedResource resource = stagedResource(
                PathSkillRepository.builder(skills).allowedLinkRoots(List.of(elsewhere)).build());
        assertThat(resource.getFiles()).containsExactly("SKILL.md", "scripts/run.sh");

        final String stagedDir = environment.stage(resource);
        assertThat(readText(stagedDir + "/scripts/run.sh")).isEqualTo("echo hi");
    }

    @Test
    @DisplayName("a skill whose directory scans to zero files does not load")
    void zeroFilesFails() throws IOException {
        writeSkill(skills.resolve("foo"));
        // A source that cannot see into the directory — what the unfollowed linked directory used to look like.
        final SkillRepository blind = new PathSkillRepository(skills) {
            @Override
            public Optional<SkillSource> resolveSource(String skillName) {
                final SkillSource source = super.resolveSource(skillName).orElseThrow();
                final VirtualFileSystem listsNothing = new DelegatingFileSystem(source.getFileSystem()) {
                    @Override
                    public List<String> listRecursive(String directory) {
                        return List.of();
                    }
                };
                return Optional.of(SkillSource.of(listsNothing, source.getDirectory()));
            }
        };
        final DefaultSkillRegistry registry = new DefaultSkillRegistry(blind, new MarkdownSkillParser());

        assertThatThrownBy(() -> registry.getSkill("foo")).isInstanceOf(SkillRepositoryException.class)
                .hasMessageContaining("'foo'").hasMessageContaining("zero files");
    }

    @Test
    @DisplayName("a refused skill does not take the others, or the Skill tool's definition, down with it")
    void refusedSkillSkippedInListings() throws IOException {
        writeSkill(skills.resolve("good"), "good");
        final Path real = writeSkill(tempDir.resolve("elsewhere/bad"), "bad");
        link(skills.resolve("bad"), real);
        final DefaultSkillRegistry registry = new DefaultSkillRegistry(new PathSkillRepository(skills),
                new MarkdownSkillParser());

        assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactly("good");
        final String description = new SkillTool(registry).getDefinition().getDescription();
        assertThat(description).contains("good").doesNotContain("bad-skill");

        registry.reloadAll();
        assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactly("good");
        // Asked for by name, the refused skill still says why.
        assertThatThrownBy(() -> registry.getSkill("bad")).isInstanceOf(SkillRepositoryException.class)
                .hasMessageContaining("'bad'").hasMessageContaining("outside the root");
        assertThat(registry.getSkill("good")).isPresent();
    }

    @Test
    @DisplayName("a skill whose .stageignore excludes every file loads, and stages empty")
    void stageIgnoreExcludingEverythingLoads() throws IOException {
        writeSkill(skills.resolve("foo"));
        Files.writeString(skills.resolve("foo/.stageignore"), "*\n");

        final StagedResource resource = stagedResource(new PathSkillRepository(skills));
        assertThat(resource.getFiles()).isEmpty();
    }

    private StagedResource stagedResource(SkillRepository repository) {
        return new DefaultSkillRegistry(repository, new MarkdownSkillParser()).getSkill("foo").orElseThrow()
                .getStagedResource().orElseThrow();
    }

    private String readText(String path) throws IOException {
        try (InputStream in = environment.fileSystem().read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path writeSkill(Path dir) throws IOException {
        return writeSkill(dir, "foo");
    }

    private static Path writeSkill(Path dir, String name) throws IOException {
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("SKILL.md"),
                SKILL_MD.replace("name: foo", "name: " + name).replace("Linked skill", name + "-skill"));
        Files.writeString(dir.resolve("scripts/run.sh"), "echo hi");
        return dir;
    }

    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not supported here: " + e.getMessage());
        }
    }
}
