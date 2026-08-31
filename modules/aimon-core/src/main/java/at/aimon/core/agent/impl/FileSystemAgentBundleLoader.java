package at.aimon.core.agent.impl;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentContent;
import at.aimon.core.agent.AgentMetadata;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.definition.AgentDefinition;
import at.aimon.core.agent.definition.exception.AgentDefinitionLoadException;
import at.aimon.core.agent.definition.exception.AgentDefinitionNotFoundException;
import at.aimon.core.agent.definition.parser.AgentDefinitionParser;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.parser.SkillParser;
import at.aimon.core.skill.repository.PathSkillRepository;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.parser.MarkdownSubagentParser;
import at.aimon.core.subagent.parser.SubagentContentParser;
import at.aimon.core.subagent.repository.PathSubagentRepository;

/**
 * Agent bundle loader that loads bundles from the filesystem using NIO {@link Path}.
 *
 * <p>
 * Each agent is expected to reside in a directory at {@code {basePath}/{name}/} containing:
 *
 * <ul>
 * <li>{@code agent.md} - The agent definition file (required)
 * <li>{@code agents/} - Directory containing bundled subagent definitions (optional)
 * <li>{@code skills/} - Directory containing bundled skill definitions (optional)
 * </ul>
 *
 * <p>
 * Unlike {@link ClasspathAgentBundleLoader}, this loader uses {@link PathSubagentRepository} and
 * {@link PathSkillRepository} which do not require index files and fully support supplementary skill files (scripts,
 * references, assets).
 *
 * <p>
 * Thread-safe and can be shared across multiple threads.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     Path basePath = Path.of("/path/to/agents");
 *     AgentBundleLoader loader = new FileSystemAgentBundleLoader(basePath, new MarkdownAgentDefinitionParser(),
 *             new AgentFactory());
 *
 *     AgentBundle bundle = loader.load("default");
 * }
 * </pre>
 *
 * @see AgentBundle
 * @see AgentBundleLoader
 */
public final class FileSystemAgentBundleLoader implements AgentBundleLoader {

    private static final Logger log = LoggerFactory.getLogger(FileSystemAgentBundleLoader.class);

    private static final String AGENT_DEFINITION_FILE = "agent.md";
    private static final String AGENTS_SUBDIRECTORY = "agents";
    private static final String SKILLS_SUBDIRECTORY = "skills";

    private final Path basePath;
    private final AgentDefinitionParser parser;
    private final SkillParser skillParser;

    /**
     * Creates a new FileSystemAgentBundleLoader using a default {@link MarkdownSkillParser}. Frontmatter {@code shell}
     * hook actions on bundled skills will fail at parse time; use
     * {@link #FileSystemAgentBundleLoader(Path, AgentDefinitionParser, SkillParser)} to inject a parser wired with a
     * {@link at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor DefaultShellActionExecutor}.
     *
     * @param basePath
     *            the filesystem base path for agent directories (must not be null)
     * @param parser
     *            the agent definition parser (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public FileSystemAgentBundleLoader(Path basePath, AgentDefinitionParser parser) {
        this(basePath, parser, new MarkdownSkillParser());
    }

    /**
     * Creates a new FileSystemAgentBundleLoader using the supplied skill parser for bundled skills.
     *
     * @param basePath
     *            the filesystem base path for agent directories (must not be null)
     * @param parser
     *            the agent definition parser (must not be null)
     * @param skillParser
     *            the skill parser used by the bundled skill registry (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public FileSystemAgentBundleLoader(Path basePath, AgentDefinitionParser parser, SkillParser skillParser) {
        this.basePath = Objects.requireNonNull(basePath, "Base path cannot be null");
        this.parser = Objects.requireNonNull(parser, "Parser cannot be null");
        this.skillParser = Objects.requireNonNull(skillParser, "Skill parser cannot be null");
    }

    @Override
    public AgentBundle load(String name) {
        Objects.requireNonNull(name, "Agent name cannot be null");

        final Agent agent = loadAgent(name);
        final SubagentRegistry subagentRegistry = loadSubagentRegistry(name);
        final SkillRegistry skillRegistry = loadSkillRegistry(name);

        return AgentBundle.builder().agent(agent).subagentRegistry(subagentRegistry).skillRegistry(skillRegistry)
                .build();
    }

    private Agent loadAgent(String name) {
        final Path definitionPath = basePath.resolve(name).resolve(AGENT_DEFINITION_FILE);

        try (InputStream inputStream = Files.newInputStream(definitionPath)) {
            final AgentDefinition definition = parser.parse(inputStream);
            return createAgent(definition);
        } catch (NoSuchFileException e) {
            throw new AgentDefinitionNotFoundException("Definition not found on filesystem: " + definitionPath, e);
        } catch (Exception e) {
            throw new AgentDefinitionLoadException("Failed to load agent bundle: " + name, e);
        }
    }

    private Agent createAgent(AgentDefinition definition) {
        final AgentMetadata metadata = AgentMetadata.builder().name(definition.getName()).model(definition.getModel())
                .maxIterations(definition.getMaxIterations()).tags(definition.getTags()).build();
        final AgentContent content = AgentContent.builder().systemPrompt(definition.getSystemPrompt())
                .variables(definition.getVariables()).build();
        return DefaultAgent.builder().metadata(metadata).content(content).build();
    }

    private SubagentRegistry loadSubagentRegistry(String name) {
        final Path agentsPath = basePath.resolve(name).resolve(AGENTS_SUBDIRECTORY);

        if (!Files.isDirectory(agentsPath)) {
            log.debug("No bundled subagents found for agent '{}' (no directory at '{}')", name, agentsPath);
            return null;
        }

        final PathSubagentRepository repository = new PathSubagentRepository(agentsPath);
        final MarkdownSubagentParser subagentParser = new MarkdownSubagentParser(new SubagentContentParser());
        return new DefaultSubagentRegistry(repository, subagentParser);
    }

    private SkillRegistry loadSkillRegistry(String name) {
        final Path skillsPath = basePath.resolve(name).resolve(SKILLS_SUBDIRECTORY);

        if (!Files.isDirectory(skillsPath)) {
            log.debug("No bundled skills found for agent '{}' (no directory at '{}')", name, skillsPath);
            return null;
        }

        final PathSkillRepository repository = new PathSkillRepository(skillsPath);
        return new DefaultSkillRegistry(repository, skillParser);
    }
}
