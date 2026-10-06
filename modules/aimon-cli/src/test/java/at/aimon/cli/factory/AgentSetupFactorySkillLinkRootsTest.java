package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.cli.config.AgentConfig;
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.factory.AgentSetupFactory.AgentSetup;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.skill.Skill;

/**
 * EE-35 on the CLI surface, end to end: {@code agent.allowedSkillLinkRoots} reaches the loader the CLI reads its
 * agent bundle with. The bundle is a directory on the (context) class path whose {@code skills/foo} is a symbolic
 * link to a shared directory outside it.
 */
@DisplayName("AgentSetupFactory — agent.allowedSkillLinkRoots")
@DisabledOnOs(OS.WINDOWS)
class AgentSetupFactorySkillLinkRootsTest {

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final List<LlmClient> created = new ArrayList<>();
    private final LlmClientFactory llmClientFactory = new LlmClientFactory() {
        @Override
        public LlmClient create(LlmProviderConfig config) {
            final LlmClient client = super.create(config);
            created.add(client);
            return client;
        }
    };

    private Path shared;
    private ClassLoader previous;
    private URLClassLoader bundleClassLoader;

    @BeforeEach
    void setUp() throws IOException {
        final Path bundle = Files.createDirectories(tempDir.resolve("classes/agents/link-probe/skills"));
        shared = Files.createDirectories(tempDir.resolve("opt/shared"));
        Files.writeString(bundle.resolveSibling("agent.md"),
                "---\nname: link-probe\nmodel:\n  name: claude-sonnet-4-5\n---\nYou are a probe.\n");
        writeSkill(bundle.resolve("local"), "local");
        writeSkill(shared.resolve("foo"), "foo");
        Files.createSymbolicLink(bundle.resolve("foo"), shared.resolve("foo"));

        previous = Thread.currentThread().getContextClassLoader();
        bundleClassLoader = new URLClassLoader(new URL[]{tempDir.resolve("classes").toUri().toURL()}, previous);
        Thread.currentThread().setContextClassLoader(bundleClassLoader);
        System.setOut(new PrintStream(stdout));
    }

    @AfterEach
    void tearDown() throws Exception {
        System.setOut(originalOut);
        Thread.currentThread().setContextClassLoader(previous);
        bundleClassLoader.close();
        for (LlmClient client : created) {
            if (client instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private List<String> bundleSkills(List<String> allowedSkillLinkRoots) {
        final LlmProviderConfig llm = new LlmProviderConfig();
        llm.setProvider("anthropic");
        // A placeholder: building the client sends nothing, and no turn runs here.
        llm.setApiKey("sk-ant-placeholder");
        llm.setModel("claude-sonnet-4-5");
        final AgentConfig agent = new AgentConfig();
        agent.setName("link-probe");
        agent.setAllowedSkillLinkRoots(allowedSkillLinkRoots);
        final CliSettings settings = new CliSettings();
        settings.setColorOutput(false);
        final CliConfig config = new CliConfig();
        config.setLlmConfig(llm);
        config.setAgentConfig(agent);
        config.setCliSettings(settings);

        final AgentSetup setup = new AgentSetupFactory(llmClientFactory, null).create(config);
        try {
            return setup.getAgentRuntime().getSkillRegistry().getAllSkills().stream().map(Skill::getName).toList();
        } finally {
            setup.close();
        }
    }

    @Test
    @DisplayName("without the key a skill linked outside the bundle's skills/ is dropped, and only that one")
    void defaultDropsTheLinkedSkill() {
        assertThat(bundleSkills(List.of())).contains("local").doesNotContain("foo");
    }

    @Test
    @DisplayName("with the key the linked skill of the CLI's own bundle loads")
    void configuredRootAdmitsTheLinkedSkill() {
        assertThat(bundleSkills(List.of(shared.toString()))).contains("local", "foo");
    }

    private static void writeSkill(Path dir, String name) throws IOException {
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + name + "\n---\n\nBody.");
        Files.writeString(dir.resolve("scripts/run.sh"), "echo " + name);
    }
}
