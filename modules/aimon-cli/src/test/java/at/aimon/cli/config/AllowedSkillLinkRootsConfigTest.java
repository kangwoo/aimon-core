package at.aimon.cli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.cli.exception.ConfigurationException;

/**
 * EE-35 on the CLI surface: {@code agent.allowedSkillLinkRoots} binds, expands {@code ${VAR}}, and refuses an entry
 * the link rule cannot use when the file is loaded — by key and index, and without quoting the value.
 */
@DisplayName("EE-35: agent.allowedSkillLinkRoots")
@DisabledOnOs(OS.WINDOWS)
class AllowedSkillLinkRootsConfigTest {

    @TempDir
    Path tempDir;

    private final CliConfigLoader loader = new CliConfigLoader(name -> "SHARED".equals(name) ? "/srv/shared" : null);

    private CliConfig load(String agentBlock) throws IOException {
        final Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, "llm:\n  provider: openai\n  apiKey: key\n  model: gpt-5.1\n" + agentBlock);
        return loader.load(file.toString());
    }

    @Test
    @DisplayName("absent, the list is empty — links must stay inside skills/")
    void defaultIsEmpty() throws IOException {
        assertThat(load("").getAgentConfig().getAllowedSkillLinkRoots()).isEmpty();
        assertThat(load("agent:\n  name: default\n").getAgentConfig().getAllowedSkillLinkRoots()).isEmpty();
        assertThat(load("agent:\n  allowedSkillLinkRoots:\n").getAgentConfig().getAllowedSkillLinkRoots()).isEmpty();
        assertThat(load("agent:\n  allowedSkillLinkRoots: []\n").getAgentConfig().getAllowedSkillLinkRoots()).isEmpty();
        // The shipped file documents the key in a comment and leaves it unset.
        assertThat(new CliConfigLoader(name -> "stub").loadDefault().getAgentConfig().getAllowedSkillLinkRoots())
                .isEmpty();
    }

    @Test
    @DisplayName("absolute paths bind in order, normalised, with ${VAR} expanded; a missing directory is accepted")
    void bindsAbsolutePaths() throws IOException {
        final AgentConfig agent = load("""
                agent:
                  allowedSkillLinkRoots:
                    - /opt/shared-skills
                    - ${SHARED}/skills/../helpers
                    - /no/such/directory
                """).getAgentConfig();

        assertThat(agent.getAllowedSkillLinkRoots()).containsExactly("/opt/shared-skills",
                "/srv/shared/skills/../helpers", "/no/such/directory");
        assertThat(CliConfigLoader.allowedSkillLinkRoots(agent)).containsExactly(Path.of("/opt/shared-skills"),
                Path.of("/srv/shared/helpers"), Path.of("/no/such/directory"));
    }

    @Test
    @DisplayName("a relative entry, `~` included, fails the load naming the entry and not its value")
    void relativeEntryIsRefused() {
        for (final String relative : new String[]{"shared-skills", "./shared", "../shared", "~/shared-skills"}) {
            assertThatThrownBy(
                    () -> load("agent:\n  allowedSkillLinkRoots:\n    - /opt/ok\n    - \"" + relative + "\"\n"))
                    .as(relative).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("agent.allowedSkillLinkRoots[1]")
                    .hasMessageContaining("must be an absolute path").hasMessageNotContaining(relative);
        }
    }

    @Test
    @DisplayName("an empty entry fails the load naming the entry")
    void emptyEntryIsRefused() {
        for (final String empty : new String[]{"\"\"", "\"  \"", "~", ""}) {
            assertThatThrownBy(() -> load("agent:\n  allowedSkillLinkRoots:\n    - " + empty + "\n")).as(empty)
                    .isInstanceOf(ConfigurationException.class).hasMessageContaining("agent.allowedSkillLinkRoots[0]")
                    .hasMessageContaining("is empty");
        }
    }

    @Test
    @DisplayName("a filesystem root, or a path that normalises to one, fails the load")
    void filesystemRootIsRefused() {
        for (final String root : new String[]{"/", "/opt/..", "//"}) {
            assertThatThrownBy(() -> load("agent:\n  allowedSkillLinkRoots:\n    - \"" + root + "\"\n")).as(root)
                    .isInstanceOf(ConfigurationException.class).hasMessageContaining("agent.allowedSkillLinkRoots[0]")
                    .hasMessageContaining("must not be a filesystem root");
        }
    }

    @Test
    @DisplayName("a scalar instead of a list, and a misspelt key, fail the load naming the key")
    void wrongShapeIsRefused() {
        assertThatThrownBy(() -> load("agent:\n  allowedSkillLinkRoots: /opt/shared-skills\n"))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("agent.allowedSkillLinkRoots");
        assertThatThrownBy(() -> load("agent:\n  allowedSkillLinkRoot:\n    - /opt/shared-skills\n"))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("allowedSkillLinkRoot");
    }
}
