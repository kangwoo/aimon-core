package at.aimon.core.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.definition.exception.AgentDefinitionNotFoundException;
import at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser;
import at.aimon.core.skill.Skill;
import at.aimon.core.subagent.Subagent;

class AdaptiveAgentBundleLoaderTest {

    @Test
    void load_usesFileSystemLoaderForFileProtocol() {
        // Test resources are on the filesystem (file:// protocol) in IDE/gradle run
        final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader();

        final AgentBundle bundle = loader.load("test-agent");

        assertNotNull(bundle.getAgent());
        assertEquals("test-agent", bundle.getAgent().getName());
        // FileSystemAgentBundleLoader is used, so subagent/skill registries should be present
        // (no index file needed for filesystem loader)
        assertTrue(bundle.getSubagentRegistry().isPresent());
        assertTrue(bundle.getSkillRegistry().isPresent());
    }

    @Test
    void load_noBundledAgent() {
        final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader();

        final AgentBundle bundle = loader.load("agent-no-bundled");

        assertNotNull(bundle.getAgent());
        assertTrue(bundle.getSubagentRegistry().isEmpty());
        assertTrue(bundle.getSkillRegistry().isEmpty());
    }

    @Test
    void load_subagentsOnly() {
        final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader();

        final AgentBundle bundle = loader.load("agent-subagents-only");

        assertNotNull(bundle.getAgent());
        assertTrue(bundle.getSubagentRegistry().isPresent());
        assertTrue(bundle.getSkillRegistry().isEmpty());
    }

    @Test
    void load_skillsOnly() {
        final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader();

        final AgentBundle bundle = loader.load("agent-skills-only");

        assertNotNull(bundle.getAgent());
        assertTrue(bundle.getSubagentRegistry().isEmpty());
        assertTrue(bundle.getSkillRegistry().isPresent());
    }

    @Test
    void load_throwsWhenAgentNotFound() {
        final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader();

        assertThrows(AgentDefinitionNotFoundException.class, () -> loader.load("nonexistent"));
    }

    @Test
    void load_throwsOnNullName() {
        final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader();

        assertThrows(NullPointerException.class, () -> loader.load(null));
    }

    @Test
    void constructor_throwsOnNullBasePath() {
        assertThrows(NullPointerException.class, () -> new AdaptiveAgentBundleLoader(null));
    }

    @Test
    void constructor_defaultConstructorCreatesInstance() {
        final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader();

        assertNotNull(loader);
    }

    @Test
    void constructor_customBasePathCreatesInstance() {
        final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader("agents");

        assertNotNull(loader);
    }

    @Test
    void load_fallsBackToClasspathLoaderForNonFileProtocol() {
        // Custom ClassLoader that wraps file:// URLs as jar: URLs to simulate JAR environment.
        // getResourceAsStream/getResources delegate to parent so ClasspathAgentBundleLoader works.
        final ClassLoader parent = Thread.currentThread().getContextClassLoader();
        final ClassLoader nonFileClassLoader = new ClassLoader(parent) {

            @Override
            public URL getResource(String name) {
                final URL original = getParent().getResource(name);
                if (original != null && "file".equals(original.getProtocol())) {
                    try {
                        return URI.create("jar:" + original + "!/").toURL();
                    } catch (Exception e) {
                        return original;
                    }
                }
                return original;
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                return getParent().getResourceAsStream(name);
            }

            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return getParent().getResources(name);
            }
        };

        final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader("agents",
                new MarkdownAgentDefinitionParser(), nonFileClassLoader);

        final AgentBundle bundle = loader.load("test-agent");

        assertNotNull(bundle.getAgent());
        assertEquals("test-agent", bundle.getAgent().getName());
        // ClasspathAgentBundleLoader is used — relies on index files for registries
        assertTrue(bundle.getSubagentRegistry().isPresent());
        assertTrue(bundle.getSkillRegistry().isPresent());
    }

    @Test
    void load_fallsBackToClasspathLoaderForNonFileProtocol_noBundled() {
        final ClassLoader parent = Thread.currentThread().getContextClassLoader();
        final ClassLoader nonFileClassLoader = new ClassLoader(parent) {

            @Override
            public URL getResource(String name) {
                final URL original = getParent().getResource(name);
                if (original != null && "file".equals(original.getProtocol())) {
                    try {
                        return URI.create("jar:" + original + "!/").toURL();
                    } catch (Exception e) {
                        return original;
                    }
                }
                return original;
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                return getParent().getResourceAsStream(name);
            }

            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return getParent().getResources(name);
            }
        };

        final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader("agents",
                new MarkdownAgentDefinitionParser(), nonFileClassLoader);

        final AgentBundle bundle = loader.load("agent-no-bundled");

        assertNotNull(bundle.getAgent());
        assertTrue(bundle.getSubagentRegistry().isEmpty());
        assertTrue(bundle.getSkillRegistry().isEmpty());
    }

    /**
     * The regression this guards is the asymmetry between how an application is developed and how it is deployed.
     *
     * <p>
     * An application's own {@code agent.md} is unpacked on disk while its skills and subagents come from dependency
     * jars. Choosing the filesystem loader on the strength of that one {@code file:} URL handed the whole bundle to a
     * loader that can only see one directory, so everything shipped by the jars vanished — but only when running
     * from a directory, which is to say only in development, which is the one place nobody is checking.
     */
    @Test
    void load_bundledContentFromOtherClassPathRootsSurvivesAnOnDiskDefinition(@TempDir Path root) throws Exception {
        final Path onDisk = agentDir(root.resolve("on-disk"), "layered");
        Files.writeString(onDisk.resolve("agent.md"), "---\nname: layered\n---\nA layered agent.\n");

        final Path packaged = root.resolve("packaged");
        writeSkill(packaged, "layered", "notes", "Packaged notes.");
        writeSubagent(packaged, "layered", "explorer");

        try (URLClassLoader classLoader = classLoaderOver(root.resolve("on-disk"), packaged)) {
            final AgentBundle bundle = new AdaptiveAgentBundleLoader("agents", new MarkdownAgentDefinitionParser(),
                    classLoader).load("layered");

            assertThat(bundle.getSkillRegistry()).isPresent();
            assertThat(bundle.getSkillRegistry().get().getAllSkills()).extracting(Skill::getName)
                    .containsExactly("notes");
            assertThat(bundle.getSubagentRegistry()).isPresent();
            assertThat(bundle.getSubagentRegistry().get().getAllSubagents()).extracting(Subagent::getName)
                    .containsExactly("explorer");
        }
    }

    /**
     * Composition has an order, and this is the end of it that matters day to day: editing a copy of a packaged skill
     * in the working directory has to take effect, or local iteration on a shipped skill is impossible.
     */
    @Test
    void load_aSkillOnDiskOverridesThePackagedOneOfTheSameName(@TempDir Path root) throws Exception {
        final Path onDisk = agentDir(root.resolve("on-disk"), "layered");
        Files.writeString(onDisk.resolve("agent.md"), "---\nname: layered\n---\nA layered agent.\n");
        // Deliberately no index file next to it: authoring on disk without one is the shape the filesystem loader
        // exists to support, and it has to keep working now that a second loader runs underneath.
        writeSkillFile(onDisk.resolve("skills"), "notes", "Locally edited notes.");

        final Path packaged = root.resolve("packaged");
        writeSkill(packaged, "layered", "notes", "Packaged notes.");

        try (URLClassLoader classLoader = classLoaderOver(root.resolve("on-disk"), packaged)) {
            final AgentBundle bundle = new AdaptiveAgentBundleLoader("agents", new MarkdownAgentDefinitionParser(),
                    classLoader).load("layered");

            assertThat(bundle.getSkillRegistry()).isPresent();
            assertThat(bundle.getSkillRegistry().get().getAllSkills()).extracting(Skill::getName)
                    .containsExactly("notes");
            assertThat(bundle.getSkillRegistry().get().getSkill("notes")).isPresent().get()
                    .extracting(skill -> skill.getMetadata().getDescription()).isEqualTo("Locally edited notes.");
        }
    }

    private static Path agentDir(Path root, String agentName) throws IOException {
        return Files.createDirectories(root.resolve("agents").resolve(agentName));
    }

    private static void writeSkill(Path root, String agentName, String skillName, String description)
            throws IOException {
        final Path skills = agentDir(root, agentName).resolve("skills");
        // The classpath loader registers nothing without an index, which is exactly what distinguishes a packaged
        // bundle from one being edited in place.
        Files.createDirectories(skills);
        Files.writeString(skills.resolve("index"), skillName + "\n");
        writeSkillFile(skills, skillName, description);
    }

    private static void writeSkillFile(Path skills, String skillName, String description) throws IOException {
        final Path skill = Files.createDirectories(skills.resolve(skillName));
        Files.writeString(skill.resolve("SKILL.md"),
                "---\nname: " + skillName + "\ndescription: \"" + description + "\"\n---\nBody.\n");
    }

    private static void writeSubagent(Path root, String agentName, String subagentName) throws IOException {
        final Path agents = agentDir(root, agentName).resolve("agents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("index"), subagentName + "\n");
        Files.writeString(agents.resolve(subagentName + ".md"),
                "---\nname: " + subagentName + "\ndescription: \"A " + subagentName + ".\"\n---\nYou explore.\n");
    }

    /**
     * A class loader over the given directories and nothing else — the platform loader as parent keeps this build's
     * own {@code agents/} test resources from joining in.
     */
    private static URLClassLoader classLoaderOver(Path... roots) throws IOException {
        final URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            Files.createDirectories(roots[i]);
            urls[i] = roots[i].toUri().toURL();
        }
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }
}
