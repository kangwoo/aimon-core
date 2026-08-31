package at.aimon.core.agent.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.definition.exception.AgentDefinitionLoadException;
import at.aimon.core.agent.definition.exception.AgentDefinitionNotFoundException;
import at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser;
import at.aimon.core.shell.impl.local.LocalShell;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.exception.SkillParseException;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.parser.SkillHookSetParser;
import at.aimon.core.skill.parser.SkillParser;
import at.aimon.core.skill.render.ShellArgumentTokenizer;

class FileSystemAgentBundleLoaderTest {

    @TempDir
    Path tempDir;

    private FileSystemAgentBundleLoader loader;

    @BeforeEach
    void setUp() {
        loader = new FileSystemAgentBundleLoader(tempDir, new MarkdownAgentDefinitionParser());
    }

    @Test
    void load_fullBundle() throws IOException {
        createFullAgentBundle("test-agent");

        final AgentBundle bundle = loader.load("test-agent");

        assertNotNull(bundle.getAgent());
        assertEquals("test-agent", bundle.getAgent().getName());
        assertTrue(bundle.getSubagentRegistry().isPresent());
        assertTrue(bundle.getSkillRegistry().isPresent());
    }

    @Test
    void load_subagentsOnly() throws IOException {
        createAgentDefinition("agent-subagents-only");
        createSubagent("agent-subagents-only", "explore");

        final AgentBundle bundle = loader.load("agent-subagents-only");

        assertNotNull(bundle.getAgent());
        assertTrue(bundle.getSubagentRegistry().isPresent());
        assertTrue(bundle.getSkillRegistry().isEmpty());
    }

    @Test
    void load_skillsOnly() throws IOException {
        createAgentDefinition("agent-skills-only");
        createSkill("agent-skills-only", "commit");

        final AgentBundle bundle = loader.load("agent-skills-only");

        assertNotNull(bundle.getAgent());
        assertTrue(bundle.getSubagentRegistry().isEmpty());
        assertTrue(bundle.getSkillRegistry().isPresent());
    }

    @Test
    void load_noBundled() throws IOException {
        createAgentDefinition("agent-no-bundled");

        final AgentBundle bundle = loader.load("agent-no-bundled");

        assertNotNull(bundle.getAgent());
        assertTrue(bundle.getSubagentRegistry().isEmpty());
        assertTrue(bundle.getSkillRegistry().isEmpty());
    }

    @Test
    void load_throwsWhenAgentNotFound() {
        assertThrows(AgentDefinitionNotFoundException.class, () -> loader.load("nonexistent"));
    }

    @Test
    void load_throwsWhenMalformed() throws IOException {
        final Path agentDir = Files.createDirectories(tempDir.resolve("malformed"));
        Files.writeString(agentDir.resolve("agent.md"),
                "---\nname: [invalid yaml\n  broken: {syntax\n---\nMalformed agent.");

        assertThrows(AgentDefinitionLoadException.class, () -> loader.load("malformed"));
    }

    @Test
    void load_throwsOnNullName() {
        assertThrows(NullPointerException.class, () -> loader.load(null));
    }

    @Test
    void load_shellHookSucceedsWhenShellAwareParserInjected() throws IOException {
        createAgentDefinition("agent-shell-hook");
        createSkillWithShellHook("agent-shell-hook", "echo-skill");

        try (LocalShell shell = new LocalShell()) {
            final SkillParser shellAwareParser = new MarkdownSkillParser(new ShellArgumentTokenizer(),
                    new SkillHookSetParser(new DefaultShellActionExecutor(shell)));
            final FileSystemAgentBundleLoader shellAwareLoader = new FileSystemAgentBundleLoader(tempDir,
                    new MarkdownAgentDefinitionParser(), shellAwareParser);

            final AgentBundle bundle = shellAwareLoader.load("agent-shell-hook");

            assertTrue(bundle.getSkillRegistry().isPresent());
            final Skill skill = bundle.getSkillRegistry().get().getSkill("echo-skill").orElseThrow();
            assertEquals("echo-skill", skill.getName());
            assertEquals(1, skill.getMetadata().getHooks().getOnStartHooks().size());
        }
    }

    @Test
    void load_shellHookFailsParseWithDefaultParser() throws IOException {
        createAgentDefinition("agent-shell-hook-default");
        createSkillWithShellHook("agent-shell-hook-default", "echo-skill");

        final AgentBundle bundle = loader.load("agent-shell-hook-default");

        assertTrue(bundle.getSkillRegistry().isPresent());
        assertThrows(SkillParseException.class, () -> bundle.getSkillRegistry().get().getSkill("echo-skill"));
    }

    @Test
    void constructor_throwsOnNullBasePath() {
        assertThrows(NullPointerException.class,
                () -> new FileSystemAgentBundleLoader(null, new MarkdownAgentDefinitionParser()));
    }

    @Test
    void constructor_throwsOnNullParser() {
        assertThrows(NullPointerException.class, () -> new FileSystemAgentBundleLoader(tempDir, null));
    }

    private void createFullAgentBundle(String agentName) throws IOException {
        createAgentDefinition(agentName);
        createSubagent(agentName, "explore");
        createSkill(agentName, "commit");
    }

    private void createAgentDefinition(String agentName) throws IOException {
        final Path agentDir = Files.createDirectories(tempDir.resolve(agentName));
        Files.writeString(agentDir.resolve("agent.md"), "---\nname: " + agentName
                + "\nmaxIterations: 5\nmodel:\n  name: gpt-test\n  temperature: 0.5\n---\nYou are a test agent.\n");
    }

    private void createSubagent(String agentName, String subagentName) throws IOException {
        final Path agentsDir = Files.createDirectories(tempDir.resolve(agentName).resolve("agents"));
        Files.writeString(agentsDir.resolve(subagentName + ".md"),
                "---\nname: " + subagentName
                        + "\ndescription: \"A test subagent.\"\nallowed-tools: Read, Grep\nmodel: haiku\n---\n"
                        + "You are a test subagent.\n");
    }

    private void createSkill(String agentName, String skillName) throws IOException {
        final Path skillDir = Files.createDirectories(tempDir.resolve(agentName).resolve("skills").resolve(skillName));
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: " + skillName + "\ndescription: \"A test skill.\"\n---\n# Test Skill\n");
    }

    private void createSkillWithShellHook(String agentName, String skillName) throws IOException {
        final Path skillDir = Files.createDirectories(tempDir.resolve(agentName).resolve("skills").resolve(skillName));
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: " + skillName + "\ndescription: \"A skill with a shell hook.\"\n"
                        + "hooks:\n  onStart:\n    - action: { type: shell, command: \"echo hello\" }\n"
                        + "---\n# Test Skill\n");
    }
}
