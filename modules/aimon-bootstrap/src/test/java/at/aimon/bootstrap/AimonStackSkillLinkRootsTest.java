package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.Skill;

/**
 * EE-35 on the bootstrap surface: {@code AimonStackSpec.allowedSkillLinkRoots} reaches the loader the stack reads a
 * named agent's bundle with. The bundle is a directory on the (context) class path whose {@code skills/foo} is a
 * symbolic link to a shared directory outside it.
 */
@DisplayName("EE-35: AimonStackSpec.allowedSkillLinkRoots")
@DisabledOnOs(OS.WINDOWS)
class AimonStackSkillLinkRootsTest {

    /** Never called — no test here runs a turn. */
    private static final LlmClient STUB_LLM = new LlmClient() {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

    };

    @TempDir
    Path tempDir;

    private Path shared;
    private ClassLoader previous;
    private URLClassLoader bundleClassLoader;

    @BeforeEach
    void setUp() throws IOException {
        final Path bundle = Files.createDirectories(tempDir.resolve("classes/agents/ops/skills"));
        shared = Files.createDirectories(tempDir.resolve("opt/shared"));
        Files.writeString(bundle.resolveSibling("agent.md"), "---\nname: ops\n---\nYou are a test agent.\n");
        writeSkill(bundle.resolve("local"), "local");
        writeSkill(shared.resolve("foo"), "foo");
        Files.createSymbolicLink(bundle.resolve("foo"), shared.resolve("foo"));

        previous = Thread.currentThread().getContextClassLoader();
        bundleClassLoader = new URLClassLoader(new URL[]{tempDir.resolve("classes").toUri().toURL()}, previous);
        Thread.currentThread().setContextClassLoader(bundleClassLoader);
    }

    @AfterEach
    void tearDown() throws IOException {
        Thread.currentThread().setContextClassLoader(previous);
        bundleClassLoader.close();
    }

    private AimonStackSpec.Builder spec() {
        return AimonStackSpec.builder().workspaceRoot(tempDir.resolve("workspace").toString()).llm(LlmSpec.of(STUB_LLM))
                .agent(AgentSpec.named("ops"));
    }

    private static List<String> skillNames(AimonStack stack) {
        return stack.runtime(stack.primaryRuntimeId()).orElseThrow().getSkillRegistry().getAllSkills().stream()
                .map(Skill::getName).toList();
    }

    @Test
    @DisplayName("the default is no roots: a skill linked outside the bundle's skills/ is dropped, and only that one")
    void defaultDropsTheLinkedSkill() {
        final AimonStackSpec spec = spec().build();

        assertThat(spec.getAllowedSkillLinkRoots()).isEmpty();
        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            assertThat(skillNames(stack)).contains("local").doesNotContain("foo");
        }
    }

    @Test
    @DisplayName("a configured root admits the linked skill of a bundle loaded by name")
    void configuredRootAdmitsTheLinkedSkill() {
        final AimonStackSpec spec = spec().allowedSkillLinkRoots(List.of(shared.resolve("../shared"))).build();

        assertThat(spec.getAllowedSkillLinkRoots()).containsExactly(shared);
        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            assertThat(skillNames(stack)).contains("local", "foo");
        }
    }

    @Test
    @DisplayName("a relative root or a filesystem root is refused when the spec is built")
    void badRootsAreRefusedAtBuild() {
        assertThatThrownBy(() -> spec().allowedSkillLinkRoots(List.of(Path.of("shared"))).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be an absolute path");
        assertThatThrownBy(() -> spec().allowedSkillLinkRoots(List.of(Path.of(""))).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be an absolute path");
        assertThatThrownBy(() -> spec().allowedSkillLinkRoots(List.of(Path.of("/"))).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be a filesystem root");
        assertThatThrownBy(() -> spec().allowedSkillLinkRoots(null)).isInstanceOf(NullPointerException.class);
    }

    private static void writeSkill(Path dir, String name) throws IOException {
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + name + "\n---\n\nBody.");
        Files.writeString(dir.resolve("scripts/run.sh"), "echo " + name);
    }
}
