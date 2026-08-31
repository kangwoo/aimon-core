package at.aimon.core.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.definition.exception.AgentDefinitionLoadException;
import at.aimon.core.agent.definition.exception.AgentDefinitionNotFoundException;
import at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser;
import at.aimon.core.skill.Skill;
import at.aimon.core.subagent.Subagent;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@DisplayName("ClasspathAgentBundleLoader Tests")
class ClasspathAgentBundleLoaderTest {

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create loader with default base path")
        void shouldCreateWithDefaultBasePath() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader();
            assertThat(loader).isNotNull();
        }

        @Test
        @DisplayName("Should create loader with custom base path")
        void shouldCreateWithCustomBasePath() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");
            assertThat(loader).isNotNull();
        }

        @Test
        @DisplayName("Should throw when base path is null")
        void shouldThrowWhenBasePathIsNull() {
            assertThatThrownBy(() -> new ClasspathAgentBundleLoader(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Base path cannot be null");
        }
    }

    @Nested
    @DisplayName("load - full bundle")
    class LoadFullBundleTests {

        @Test
        @DisplayName("Should load agent with both subagents and skills")
        void shouldLoadAgentWithBothSubagentsAndSkills() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            AgentBundle bundle = loader.load("test-agent");

            assertThat(bundle).isNotNull();
            assertThat(bundle.getAgent()).isNotNull();
            assertThat(bundle.getAgent().getMetadata().getName()).isEqualTo("test-agent");
            assertThat(bundle.getSubagentRegistry()).isPresent();
            assertThat(bundle.getSkillRegistry()).isPresent();
        }

        @Test
        @DisplayName("Should load bundled subagent from agent directory")
        void shouldLoadBundledSubagent() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            AgentBundle bundle = loader.load("test-agent");

            Optional<Subagent> explore = bundle.getSubagentRegistry().get().getSubagent("explore");
            assertThat(explore).isPresent();
            assertThat(explore.get().getName()).isEqualTo("explore");
            assertThat(explore.get().getMetadata().getDescription()).contains("exploring codebases");
        }

        @Test
        @DisplayName("Should load bundled skill from agent directory")
        void shouldLoadBundledSkill() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            AgentBundle bundle = loader.load("test-agent");

            Optional<Skill> commit = bundle.getSkillRegistry().get().getSkill("commit");
            assertThat(commit).isPresent();
            assertThat(commit.get().getName()).isEqualTo("commit");
            assertThat(commit.get().getMetadata().getDescription()).contains("commit message");
        }
    }

    @Nested
    @DisplayName("load - no bundled resources")
    class LoadNoBundledTests {

        @Test
        @DisplayName("Should load agent without bundled subagents or skills")
        void shouldLoadAgentWithoutBundled() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            AgentBundle bundle = loader.load("agent-no-bundled");

            assertThat(bundle).isNotNull();
            assertThat(bundle.getAgent()).isNotNull();
            assertThat(bundle.getAgent().getMetadata().getName()).isEqualTo("agent-no-bundled");
            assertThat(bundle.getSubagentRegistry()).isEmpty();
            assertThat(bundle.getSkillRegistry()).isEmpty();
        }
    }

    @Nested
    @DisplayName("load - partial bundle")
    class LoadPartialBundleTests {

        @Test
        @DisplayName("Should load agent with subagents only")
        void shouldLoadAgentWithSubagentsOnly() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            AgentBundle bundle = loader.load("agent-subagents-only");

            assertThat(bundle).isNotNull();
            assertThat(bundle.getSubagentRegistry()).isPresent();
            assertThat(bundle.getSkillRegistry()).isEmpty();

            Optional<Subagent> explore = bundle.getSubagentRegistry().get().getSubagent("explore");
            assertThat(explore).isPresent();
        }

        @Test
        @DisplayName("Should load agent with skills only")
        void shouldLoadAgentWithSkillsOnly() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            AgentBundle bundle = loader.load("agent-skills-only");

            assertThat(bundle).isNotNull();
            assertThat(bundle.getSubagentRegistry()).isEmpty();
            assertThat(bundle.getSkillRegistry()).isPresent();

            Optional<Skill> commit = bundle.getSkillRegistry().get().getSkill("commit");
            assertThat(commit).isPresent();
        }
    }

    @Nested
    @DisplayName("load - error handling")
    class LoadErrorTests {

        @Test
        @DisplayName("Should throw when agent name is null")
        void shouldThrowWhenNameIsNull() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            assertThatThrownBy(() -> loader.load(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Agent name cannot be null");
        }

        @Test
        @DisplayName("Should throw when agent definition not found")
        void shouldThrowWhenDefinitionNotFound() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            assertThatThrownBy(() -> loader.load("non-existent")).isInstanceOf(AgentDefinitionNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw AgentDefinitionLoadException for malformed agent definition")
        void shouldThrowForMalformedDefinition() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            assertThatThrownBy(() -> loader.load("agent-malformed")).isInstanceOf(AgentDefinitionLoadException.class)
                    .hasMessageContaining("Failed to load agent bundle: agent-malformed");
        }

        @Test
        @DisplayName("Should throw AgentDefinitionLoadException for empty agent definition")
        void shouldThrowForEmptyDefinition() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            assertThatThrownBy(() -> loader.load("agent-empty")).isInstanceOf(AgentDefinitionLoadException.class)
                    .hasMessageContaining("Failed to load agent bundle: agent-empty");
        }
    }

    @Nested
    @DisplayName("load - bundled resources without an index file")
    class MissingIndexTests {

        private Logger loaderLogger;
        private ListAppender<ILoggingEvent> logAppender;

        @BeforeEach
        void attachLogAppender() {
            loaderLogger = (Logger) LoggerFactory.getLogger(ClasspathAgentBundleLoader.class);
            logAppender = new ListAppender<>();
            logAppender.start();
            loaderLogger.addAppender(logAppender);
        }

        @AfterEach
        void detachLogAppender() {
            loaderLogger.detachAppender(logAppender);
            logAppender.stop();
        }

        @Test
        @DisplayName("Should load the agent but warn loudly for each directory that ships content without an index")
        void shouldWarnWhenBundledContentHasNoIndex() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            AgentBundle bundle = loader.load("agent-bundled-no-index");

            // Non-fatal: the agent still loads, but neither directory is registered.
            assertThat(bundle.getAgent().getMetadata().getName()).isEqualTo("agent-bundled-no-index");
            assertThat(bundle.getSubagentRegistry()).isEmpty();
            assertThat(bundle.getSkillRegistry()).isEmpty();

            assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage).hasSize(2)
                    .anySatisfy(message -> assertThat(message).contains("agent-bundled-no-index").contains("subagents")
                            .contains("agents/agent-bundled-no-index/agents/index"))
                    .anySatisfy(message -> assertThat(message).contains("agent-bundled-no-index").contains("skills")
                            .contains("agents/agent-bundled-no-index/skills/index"));
        }

        @Test
        @DisplayName("Should stay quiet when nothing is bundled at all")
        void shouldNotWarnWhenNothingIsBundled() {
            ClasspathAgentBundleLoader loader = new ClasspathAgentBundleLoader("agents");

            AgentBundle bundle = loader.load("agent-no-bundled");

            assertThat(bundle.getSubagentRegistry()).isEmpty();
            assertThat(bundle.getSkillRegistry()).isEmpty();
            assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.WARN).isEmpty();
        }
    }

    /**
     * End-to-end proof: an agent whose bundled skills and subagents are contributed by two different class
     * path roots must expose all of them, not only those of the first root.
     */
    @Nested
    @DisplayName("load - bundled resources spread across multiple class path roots")
    class MultiRootBundleTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("Should register bundled skills contributed by every class path root")
        void shouldRegisterSkillsFromAllRoots() throws Exception {
            try (URLClassLoader classLoader = multiRootClassLoader()) {
                AgentBundle bundle = new ClasspathAgentBundleLoader("agents", new MarkdownAgentDefinitionParser(),
                        classLoader).load("multi");

                assertThat(bundle.getSkillRegistry()).isPresent();
                assertThat(bundle.getSkillRegistry().get().getAllSkills()).extracting(Skill::getName)
                        .containsExactly("commit", "deploy");
            }
        }

        @Test
        @DisplayName("Should register bundled subagents contributed by every class path root")
        void shouldRegisterSubagentsFromAllRoots() throws Exception {
            try (URLClassLoader classLoader = multiRootClassLoader()) {
                AgentBundle bundle = new ClasspathAgentBundleLoader("agents", new MarkdownAgentDefinitionParser(),
                        classLoader).load("multi");

                assertThat(bundle.getSubagentRegistry()).isPresent();
                assertThat(bundle.getSubagentRegistry().get().getAllSubagents()).extracting(Subagent::getName)
                        .containsExactlyInAnyOrder("explore", "auditor");
            }
        }

        private URLClassLoader multiRootClassLoader() throws IOException {
            final URL dirRoot = explodedRoot("root-a",
                    Map.of("agents/multi/agent.md", agentDefinition(), "agents/multi/skills/index", "commit\n",
                            "agents/multi/skills/commit/SKILL.md", skill("commit"), "agents/multi/agents/index",
                            "explore\n", "agents/multi/agents/explore.md", subagent("explore")));
            final URL jarRoot = jarRoot("root-b.jar",
                    Map.of("agents/multi/skills/index", "deploy\n", "agents/multi/skills/deploy/SKILL.md",
                            skill("deploy"), "agents/multi/agents/index", "auditor\n", "agents/multi/agents/auditor.md",
                            subagent("auditor")));
            return new URLClassLoader(new URL[]{dirRoot, jarRoot}, null);
        }

        private String agentDefinition() {
            return "---\nname: multi\nmaxIterations: 5\nmodel:\n  name: gpt-test\n  temperature: 0.5\n---\n"
                    + "You are an agent whose bundle spans two class path roots.\n";
        }

        private String skill(String name) {
            return "---\nname: " + name + "\ndescription: \"Bundled skill " + name + ".\"\n---\n# Skill " + name + "\n";
        }

        private String subagent(String name) {
            return "---\nname: " + name + "\ndescription: \"Bundled subagent " + name + ".\"\n---\nYou are " + name
                    + ".\n";
        }

        private URL explodedRoot(String name, Map<String, String> resources) throws IOException {
            final Path root = Files.createDirectories(tempDir.resolve(name));
            for (Map.Entry<String, String> resource : resources.entrySet()) {
                final Path file = root.resolve(resource.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, resource.getValue(), StandardCharsets.UTF_8);
            }
            final String uri = root.toUri().toString();
            // URLClassLoader only treats a URL as a directory root when it ends with a slash.
            return URI.create(uri.endsWith("/") ? uri : uri + "/").toURL();
        }

        private URL jarRoot(String fileName, Map<String, String> resources) throws IOException {
            final Path jarPath = tempDir.resolve(fileName);
            try (OutputStream out = Files.newOutputStream(jarPath); JarOutputStream jar = new JarOutputStream(out)) {
                for (Map.Entry<String, String> resource : resources.entrySet()) {
                    jar.putNextEntry(new JarEntry(resource.getKey()));
                    jar.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                    jar.closeEntry();
                }
            }
            return jarPath.toUri().toURL();
        }
    }

}
