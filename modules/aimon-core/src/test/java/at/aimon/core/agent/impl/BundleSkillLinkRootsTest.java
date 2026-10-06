package at.aimon.core.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.filesystem.VirtualFileSystems;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.exception.SkillRepositoryException;
import at.aimon.core.skill.parser.MarkdownSkillParser;

/**
 * EE-35: the allowed link roots of a bundle's {@code skills/} directory, set where a bundle is loaded from disk.
 *
 * <p>
 * The fixture is the case the item names: an agent directory on the class path whose {@code skills/foo} is a symbolic
 * link to a shared directory outside it. {@code skills/local} is an ordinary skill, there to show that whatever
 * happens to the linked one happens to it alone.
 */
@DisplayName("EE-35: allowed link roots for the skills of an agent bundle loaded from disk")
@DisabledOnOs(OS.WINDOWS)
class BundleSkillLinkRootsTest {

    @TempDir
    Path tempDir;

    private Path agents;
    private Path shared;

    @BeforeEach
    void setUp() throws IOException {
        agents = Files.createDirectories(tempDir.resolve("classes/agents"));
        shared = Files.createDirectories(tempDir.resolve("opt/shared"));
        Files.createDirectories(agents.resolve("ops/skills"));
        Files.writeString(agents.resolve("ops/agent.md"), "---\nname: ops\n---\nYou are a test agent.\n");
        writeSkill(agents.resolve("ops/skills/local"), "local");
        writeSkill(shared.resolve("foo"), "foo");
        Files.createSymbolicLink(agents.resolve("ops/skills/foo"), shared.resolve("foo"));
    }

    @Test
    @DisplayName("as shipped: a skill linked outside the skills directory is dropped, and only that one")
    void linkedSkillIsDroppedByDefault() throws IOException {
        try (URLClassLoader classLoader = classLoaderOver(tempDir.resolve("classes"))) {
            final SkillRegistry registry = new AdaptiveAgentBundleLoader("agents", new MarkdownAgentDefinitionParser(),
                    classLoader, new MarkdownSkillParser()).load("ops").getSkillRegistry().orElseThrow();

            assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactly("local");
            assertThatThrownBy(() -> registry.getSkill("foo")).isInstanceOf(SkillRepositoryException.class)
                    .hasMessageContaining("outside the root");
        }
    }

    @Test
    @DisplayName("a skill linked into an allowed root loads, and its files can be read and staged")
    void linkedSkillUnderAnAllowedRootLoadsAndStages() throws IOException {
        final SkillRegistry registry = skillsWith(List.of(shared));

        assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactlyInAnyOrder("local", "foo");
        final StagedResource resource = registry.getSkill("foo").orElseThrow().getStagedResource().orElseThrow();
        assertThat(resource.getFiles()).containsExactly("SKILL.md", "scripts/run.sh");
        try (InputStream in = resource.getSourceFileSystem().read(resource.sourcePath("scripts/run.sh"))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("echo foo");
        }

        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(Files.createDirectories(tempDir.resolve("workspace"))).contentSearch(false).build()) {
            final ExecutionEnvironment environment = provider
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.of("agent:ops")).build());
            final String staged = environment.stage(resource);
            try (InputStream in = environment.fileSystem().read(staged + "/scripts/run.sh")) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("echo foo");
            }
        }
    }

    @Test
    @DisplayName("the adaptive loader hands the roots to the on-disk loader")
    void adaptiveLoaderPassesTheRootsOn() throws IOException {
        try (URLClassLoader classLoader = classLoaderOver(tempDir.resolve("classes"))) {
            final SkillRegistry registry = new AdaptiveAgentBundleLoader("agents", new MarkdownAgentDefinitionParser(),
                    classLoader, new MarkdownSkillParser(), List.of(shared)).load("ops").getSkillRegistry()
                    .orElseThrow();

            assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactlyInAnyOrder("local", "foo");
        }
    }

    @Test
    @DisplayName("a link outside every allowed root still drops that skill, and only that one")
    void linkOutsideEveryAllowedRootStillDropsOnlyThatSkill() throws IOException {
        final Path other = Files.createDirectories(tempDir.resolve("opt/other"));

        final SkillRegistry registry = skillsWith(List.of(other));

        assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactly("local");
        assertThatThrownBy(() -> registry.getSkill("foo")).isInstanceOf(SkillRepositoryException.class)
                .hasMessageContaining("outside the root").hasMessageContaining(other.toString());
    }

    @Test
    @DisplayName("a sibling whose name merely starts with an allowed root's is not under it")
    void allowedRootIsMatchedBySegment() throws IOException {
        // opt/shared-private is not inside opt/shared.
        writeSkill(tempDir.resolve("opt/shared-private/bar"), "bar");
        Files.createSymbolicLink(agents.resolve("ops/skills/bar"), tempDir.resolve("opt/shared-private/bar"));

        final SkillRegistry registry = skillsWith(List.of(shared));

        assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactlyInAnyOrder("local", "foo");
    }

    @Test
    @DisplayName("an allowed root that does not exist is accepted and matches nothing; loading goes on")
    void missingAllowedRootDoesNotBreakLoading() {
        final SkillRegistry registry = skillsWith(List.of(tempDir.resolve("opt/not-there"), shared));

        assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactlyInAnyOrder("local", "foo");
        assertThat(skillsWith(List.of(tempDir.resolve("opt/not-there"))).getAllSkills()).extracting(Skill::getName)
                .containsExactly("local");
    }

    @Test
    @DisplayName("an allowed root is normalised, and one written through a link of its own still matches")
    void allowedRootIsNormalisedAndResolved() throws IOException {
        assertThat(skillsWith(List.of(shared.resolve("../shared/."))).getAllSkills()).extracting(Skill::getName)
                .containsExactlyInAnyOrder("local", "foo");

        // The operator's spelling goes through a link (as /var does on macOS); the skill's real path does not.
        final Path alias = Files.createSymbolicLink(tempDir.resolve("alias"), tempDir.resolve("opt"));
        assertThat(skillsWith(List.of(alias.resolve("shared"))).getAllSkills()).extracting(Skill::getName)
                .containsExactlyInAnyOrder("local", "foo");
    }

    @Test
    @DisplayName("on a disk that ignores letter case, an allowed root written in another case names the same directory")
    void allowedRootInAnotherCaseOnACaseInsensitiveDisk() {
        final Path upper = tempDir.resolve("opt/SHARED");
        assumeTrue(Files.exists(upper), "the disk is case-sensitive: opt/SHARED is another directory");

        assertThat(skillsWith(List.of(upper)).getAllSkills()).extracting(Skill::getName)
                .containsExactlyInAnyOrder("local", "foo");
    }

    @Test
    @DisplayName("a relative allowed root, the empty string included, is refused when the loader is built")
    void relativeAllowedRootIsRefused() {
        for (final String relative : List.of("shared", "./shared", "../shared", "", " ", "~/shared")) {
            assertThatThrownBy(() -> loaderWith(List.of(Path.of(relative)))).as("'" + relative + "'")
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be an absolute path")
                    .hasMessageContaining("'" + relative + "'");
        }
    }

    @Test
    @DisplayName("a filesystem root, or a path that normalises to one, is refused when the loader is built")
    void filesystemRootIsRefused() {
        for (final String root : List.of("/", "//", "/.", "/opt/..", "/opt/../..")) {
            assertThatThrownBy(() -> loaderWith(List.of(Path.of(root)))).as(root)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be a filesystem root");
        }
        // The same rule wherever a list is taken, also below the loaders.
        assertThatThrownBy(() -> VirtualFileSystems.readOnlyLocal(agents, List.of(Path.of("/"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdaptiveAgentBundleLoader("agents", new MarkdownAgentDefinitionParser(),
                getClass().getClassLoader(), new MarkdownSkillParser(), List.of(Path.of("/"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an allowed root that is a link to the filesystem root allows nothing")
    void allowedRootLinkedToTheFilesystemRootAllowsNothing() throws IOException {
        final Path everything = Files.createSymbolicLink(tempDir.resolve("everything"), Path.of("/"));

        final SkillRegistry registry = skillsWith(List.of(everything));

        assertThat(registry.getAllSkills()).extracting(Skill::getName).containsExactly("local");
    }

    @Test
    @DisplayName("a null list or a null entry is refused")
    void nullsAreRefused() {
        assertThatThrownBy(() -> loaderWith(null)).isInstanceOf(NullPointerException.class);
        final List<Path> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> loaderWith(withNull)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("checkedLinkRoots returns the normalised roots in order and does not touch the disk")
    void checkedLinkRootsNormalises() {
        final Path missing = Path.of("/no/such/dir/../place");

        assertThat(VirtualFileSystems.checkedLinkRoots(List.of(shared.resolve("x/.."), missing)))
                .containsExactly(shared, Path.of("/no/such/place"));
        assertThat(VirtualFileSystems.checkedLinkRoots(List.of())).isEmpty();
    }

    private SkillRegistry skillsWith(Collection<Path> allowedSkillLinkRoots) {
        return loaderWith(allowedSkillLinkRoots).load("ops").getSkillRegistry().orElseThrow();
    }

    private FileSystemAgentBundleLoader loaderWith(Collection<Path> allowedSkillLinkRoots) {
        return new FileSystemAgentBundleLoader(agents, new MarkdownAgentDefinitionParser(), new MarkdownSkillParser(),
                allowedSkillLinkRoots);
    }

    private static void writeSkill(Path dir, String name) throws IOException {
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + name + "\n---\n\nBody.");
        Files.writeString(dir.resolve("scripts/run.sh"), "echo " + name);
    }

    private static URLClassLoader classLoaderOver(Path root) throws IOException {
        return new URLClassLoader(new URL[]{root.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }
}
