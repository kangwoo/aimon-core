package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.core.skill.Skill;

/**
 * EE-35 on the starter surface: {@code aimon.skill.allowed-link-roots} binds, is refused by property and index when
 * the link rule cannot use an entry, and reaches the loader the stack reads a named agent's bundle with.
 */
@DisplayName("EE-35: aimon.skill.allowed-link-roots")
@DisabledOnOs(OS.WINDOWS)
class AimonSkillLinkRootsTest {

    private static final String AGENT = "test-agent";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(AimonLlmAutoConfiguration.class, AimonFileSystemAutoConfiguration.class,
                    AimonSessionAutoConfiguration.class, AimonSchedulingAutoConfiguration.class,
                    AimonKnowledgeAutoConfiguration.class, AimonMemoryAutoConfiguration.class,
                    AimonObservabilityAutoConfiguration.class, AimonAutoConfiguration.class));

    private ApplicationContextRunner minimal(Path workspace, String agent) {
        return runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.llm.api-key=test-key",
                "aimon.agent-defaults.default-agent=" + agent);
    }

    private static List<Path> rootsOf(ApplicationContext ctx) {
        return ctx.getBean(AimonStackSpec.class).getAllowedSkillLinkRoots();
    }

    @Test
    @DisplayName("unset, the list is empty — links must stay inside skills/")
    void defaultIsEmpty(@TempDir Path workspace) {
        minimal(workspace, AGENT).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(rootsOf(ctx)).isEmpty();
        });
        minimal(workspace, AGENT).withPropertyValues("aimon.skill.allowed-link-roots=").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(rootsOf(ctx)).isEmpty();
        });
    }

    @Test
    @DisplayName("absolute paths bind in order and normalised, comma-separated or indexed; a missing one is accepted")
    void bindsAbsolutePaths(@TempDir Path workspace) {
        minimal(workspace, AGENT)
                .withPropertyValues("aimon.skill.allowed-link-roots=/opt/shared-skills, /srv/skills/../helpers")
                .run(ctx -> assertThat(rootsOf(ctx)).containsExactly(Path.of("/opt/shared-skills"),
                        Path.of("/srv/helpers")));
        minimal(workspace, AGENT).withPropertyValues("aimon.skill.allowed-link-roots[0]=/no/such/directory",
                "aimon.skill.allowed-link-roots[1]=/opt/shared-skills").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(rootsOf(ctx)).containsExactly(Path.of("/no/such/directory"),
                            Path.of("/opt/shared-skills"));
                });
    }

    @Test
    @DisplayName("a relative entry fails startup by property and index")
    void relativeEntryIsRefused(@TempDir Path workspace) {
        for (final String relative : List.of("shared-skills", "./shared", "../shared", "~/shared-skills")) {
            minimal(workspace, AGENT).withPropertyValues("aimon.skill.allowed-link-roots=/opt/ok," + relative)
                    .run(ctx -> assertThat(ctx).as(relative).hasFailed().getFailure()
                            .hasStackTraceContaining(AimonProperties.SKILL_ALLOWED_LINK_ROOTS + "[1]")
                            .hasStackTraceContaining("must be an absolute path"));
        }
    }

    @Test
    @DisplayName("a blank entry fails startup by index instead of allowing nothing in silence")
    void blankEntryIsRefused(@TempDir Path workspace) {
        minimal(workspace, AGENT).withPropertyValues("aimon.skill.allowed-link-roots=/opt/ok, ")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SKILL_ALLOWED_LINK_ROOTS + "[1] is blank"));
    }

    @Test
    @DisplayName("a filesystem root, or a path that normalises to one, fails startup")
    void filesystemRootIsRefused(@TempDir Path workspace) {
        for (final String root : List.of("/", "/opt/..", "//")) {
            minimal(workspace, AGENT).withPropertyValues("aimon.skill.allowed-link-roots[0]=" + root)
                    .run(ctx -> assertThat(ctx).as(root).hasFailed().getFailure()
                            .hasStackTraceContaining(AimonProperties.SKILL_ALLOWED_LINK_ROOTS + "[0]")
                            .hasStackTraceContaining("must not be a filesystem root"));
        }
    }

    @Test
    @DisplayName("the configured root admits a linked skill of a bundle on disk; without it only that skill is dropped")
    void configuredRootAdmitsALinkedSkill(@TempDir Path tempDir) throws IOException {
        final Path bundle = Files.createDirectories(tempDir.resolve("classes/agents/link-probe/skills"));
        final Path shared = Files.createDirectories(tempDir.resolve("opt/shared"));
        Files.writeString(bundle.resolveSibling("agent.md"), "---\nname: link-probe\n---\nYou are a probe.\n");
        writeSkill(bundle.resolve("local"), "local");
        writeSkill(shared.resolve("foo"), "foo");
        Files.createSymbolicLink(bundle.resolve("foo"), shared.resolve("foo"));
        final Path workspace = tempDir.resolve("workspace");

        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{tempDir.resolve("classes").toUri().toURL()},
                getClass().getClassLoader())) {
            minimal(workspace, "link-probe").withClassLoader(classLoader).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(skillNames(ctx)).contains("local").doesNotContain("foo");
            });
            minimal(workspace, "link-probe").withClassLoader(classLoader)
                    .withPropertyValues("aimon.skill.allowed-link-roots=" + shared).run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        assertThat(skillNames(ctx)).contains("local", "foo");
                    });
        }
    }

    private static List<String> skillNames(ApplicationContext ctx) {
        final AimonStack stack = ctx.getBean(AimonStack.class);
        return stack.runtime(stack.primaryRuntimeId()).orElseThrow().getSkillRegistry().getAllSkills().stream()
                .map(Skill::getName).toList();
    }

    private static void writeSkill(Path dir, String name) throws IOException {
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + name + "\n---\n\nBody.");
        Files.writeString(dir.resolve("scripts/run.sh"), "echo " + name);
    }
}
