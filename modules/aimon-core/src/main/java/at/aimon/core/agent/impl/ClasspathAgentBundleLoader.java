package at.aimon.core.agent.impl;

import java.io.IOException;
import java.io.InputStream;
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
import at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.parser.SkillParser;
import at.aimon.core.skill.repository.ClasspathResourceTreeWalker;
import at.aimon.core.skill.repository.ClasspathSkillRepository;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.parser.MarkdownSubagentParser;
import at.aimon.core.subagent.parser.SubagentContentParser;
import at.aimon.core.subagent.repository.ClasspathSubagentRepository;

/**
 * Agent bundle loader that loads bundles from classpath resources using a directory-based structure.
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
 * The subagent and skill directories use the same format as {@link ClasspathSubagentRepository} and
 * {@link ClasspathSkillRepository}, including index files for enumeration.
 *
 * <p>
 * <b>An index file is mandatory for bundled subagents and skills.</b> The loader does not auto-discover them: the index
 * is the declared enumeration contract shared with
 * {@link at.aimon.core.skill.repository.BundledSkillMaterializer BundledSkillMaterializer}, and making enumeration
 * implicit would silently change <em>which</em> resources load. A directory that ships content but no index is
 * therefore not registered — but it is never silent either: the loader probes the directory and logs a {@code WARN}
 * naming the agent, the directory and the missing index. Only a genuinely absent directory is reported at
 * {@code DEBUG}. Loading always continues; a missing index is never fatal.
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
 *     AgentBundleLoader loader = new ClasspathAgentBundleLoader();
 *
 *     // Loads from agents/default/ directory on classpath
 *     AgentBundle bundle = loader.load("default");
 * }
 * </pre>
 *
 * @see AgentBundle
 * @see AgentBundleLoader
 */
public final class ClasspathAgentBundleLoader implements AgentBundleLoader {

    private static final Logger log = LoggerFactory.getLogger(ClasspathAgentBundleLoader.class);

    private static final String DEFAULT_BASE_PATH = "agents";
    private static final String AGENT_DEFINITION_FILE = "agent.md";
    private static final String AGENTS_SUBDIRECTORY = "agents";
    private static final String SKILLS_SUBDIRECTORY = "skills";
    private static final String INDEX_FILE = "index";

    private final String basePath;
    private final AgentDefinitionParser parser;
    private final ClassLoader classLoader;
    private final SkillParser skillParser;
    private final ClasspathResourceTreeWalker resourceTreeWalker;
    private final boolean reportMissingIndex;

    /** ClasspathAgentBundleLoader를 생성한다. */
    public ClasspathAgentBundleLoader() {
        this(DEFAULT_BASE_PATH);
    }

    /**
     * Creates a new ClasspathAgentBundleLoader with a custom base path.
     *
     * @param basePath
     *            the classpath base path for agent directories (must not be null)
     * @throws NullPointerException
     *             if basePath is null
     */
    public ClasspathAgentBundleLoader(String basePath) {
        this(basePath, new MarkdownAgentDefinitionParser(), Thread.currentThread().getContextClassLoader());
    }

    /**
     * Creates a new ClasspathAgentBundleLoader with custom dependencies and a default {@link MarkdownSkillParser}.
     * Frontmatter {@code shell} hook actions on bundled skills will fail at parse time; use
     * {@link #ClasspathAgentBundleLoader(String, AgentDefinitionParser, ClassLoader, SkillParser)} to inject a parser
     * wired with a {@link at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor
     * DefaultShellActionExecutor}.
     *
     * @param basePath
     *            the classpath base path for agent directories (must not be null)
     * @param parser
     *            the agent definition parser (must not be null)
     * @param classLoader
     *            the class loader to use for resource loading (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public ClasspathAgentBundleLoader(String basePath, AgentDefinitionParser parser, ClassLoader classLoader) {
        this(basePath, parser, classLoader, new MarkdownSkillParser());
    }

    /**
     * Creates a new ClasspathAgentBundleLoader with custom dependencies and an injected skill parser.
     *
     * @param basePath
     *            the classpath base path for agent directories (must not be null)
     * @param parser
     *            the agent definition parser (must not be null)
     * @param classLoader
     *            the class loader to use for resource loading (must not be null)
     * @param skillParser
     *            the skill parser used by the bundled skill registry (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public ClasspathAgentBundleLoader(String basePath, AgentDefinitionParser parser, ClassLoader classLoader,
            SkillParser skillParser) {
        this(basePath, parser, classLoader, skillParser, true);
    }

    private ClasspathAgentBundleLoader(String basePath, AgentDefinitionParser parser, ClassLoader classLoader,
            SkillParser skillParser, boolean reportMissingIndex) {
        this.basePath = Objects.requireNonNull(basePath, "Base path cannot be null");
        this.parser = Objects.requireNonNull(parser, "Parser cannot be null");
        this.classLoader = Objects.requireNonNull(classLoader, "ClassLoader cannot be null");
        this.skillParser = Objects.requireNonNull(skillParser, "Skill parser cannot be null");
        this.resourceTreeWalker = new ClasspathResourceTreeWalker(this.classLoader);
        this.reportMissingIndex = reportMissingIndex;
    }

    /**
     * Creates a loader for use <em>underneath</em> another loader, which stays quiet about an absent index file.
     *
     * <p>
     * The missing-index warning says "content is bundled here but will not be registered". Only the loader that has
     * the last word on a directory can know that. When {@link AdaptiveAgentBundleLoader} layers this loader under a
     * {@link FileSystemAgentBundleLoader}, the directory on disk is a class path root <em>and</em> the filesystem
     * root — so an index-less skill directory there is the supported local-authoring shape, already loaded by the
     * layer above, and warning about it would be false. Everything else this loader does is unchanged: it still
     * reads every class path root and still contributes what it finds.
     *
     * @param basePath
     *            the classpath base path for agent directories (must not be null)
     * @param parser
     *            the agent definition parser (must not be null)
     * @param classLoader
     *            the class loader to use for resource loading (must not be null)
     * @param skillParser
     *            the skill parser used by the bundled skill registry (must not be null)
     * @return a loader that reports an absent index at DEBUG rather than WARN
     */
    static ClasspathAgentBundleLoader asUnderlay(String basePath, AgentDefinitionParser parser, ClassLoader classLoader,
            SkillParser skillParser) {
        return new ClasspathAgentBundleLoader(basePath, parser, classLoader, skillParser, false);
    }

    /**
     * Loads an agent bundle by name from the classpath.
     *
     * <p>
     * Loads the agent definition from {@code {basePath}/{name}/agent.md} and optionally loads bundled subagents from
     * {@code {basePath}/{name}/agents/} and bundled skills from {@code {basePath}/{name}/skills/}.
     *
     * @param name
     *            the agent name (must not be null)
     * @return the loaded agent bundle (never null)
     * @throws AgentDefinitionNotFoundException
     *             if the agent definition is not found
     * @throws AgentDefinitionLoadException
     *             if loading or parsing fails
     * @throws NullPointerException
     *             if name is null
     */
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
        final String definitionPath = basePath + "/" + name + "/" + AGENT_DEFINITION_FILE;
        try (InputStream inputStream = classLoader.getResourceAsStream(definitionPath)) {
            if (inputStream == null) {
                throw new AgentDefinitionNotFoundException("Definition not found on classpath: " + definitionPath);
            }

            final AgentDefinition definition = parser.parse(inputStream);
            return createAgent(definition);
        } catch (AgentDefinitionNotFoundException e) {
            throw e;
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
        final String agentsPath = basePath + "/" + name + "/" + AGENTS_SUBDIRECTORY;
        final String indexPath = agentsPath + "/" + INDEX_FILE;

        if (classLoader.getResource(indexPath) == null) {
            reportMissingIndex(name, agentsPath, indexPath, "subagents");
            return null;
        }

        final ClasspathSubagentRepository repository = new ClasspathSubagentRepository(agentsPath, classLoader);
        final MarkdownSubagentParser subagentParser = new MarkdownSubagentParser(new SubagentContentParser());
        return new DefaultSubagentRegistry(repository, subagentParser);
    }

    private SkillRegistry loadSkillRegistry(String name) {
        final String skillsPath = basePath + "/" + name + "/" + SKILLS_SUBDIRECTORY;
        final String indexPath = skillsPath + "/" + INDEX_FILE;

        if (classLoader.getResource(indexPath) == null) {
            reportMissingIndex(name, skillsPath, indexPath, "skills");
            return null;
        }

        final ClasspathSkillRepository repository = new ClasspathSkillRepository(skillsPath, classLoader);
        return new DefaultSkillRegistry(repository, skillParser);
    }

    /**
     * Reports an absent index file, distinguishing "nothing is bundled" from "content is bundled but unreachable".
     *
     * <p>
     * The latter is a packaging mistake that used to be invisible — the agent loaded with zero skills and a single
     * DEBUG line. It is reported at WARN so it cannot pass unnoticed, while loading still continues.
     */
    private void reportMissingIndex(String agentName, String directoryPath, String indexPath, String kind) {
        if (reportMissingIndex && hasBundledContent(agentName, directoryPath)) {
            log.warn(
                    "Agent '{}' bundles {} under '{}' but no index file exists at '{}' — they will NOT be registered. "
                            + "Add an index file listing one name per line.",
                    agentName, kind, directoryPath, indexPath);
            return;
        }
        log.debug("No bundled {} found for agent '{}' (no index file at '{}')", kind, agentName, indexPath);
    }

    private boolean hasBundledContent(String agentName, String directoryPath) {
        try {
            if (classLoader.getResources(directoryPath).hasMoreElements()) {
                return true;
            }
        } catch (IOException e) {
            log.debug("Failed to probe classpath directory '{}': {}", directoryPath, e.getMessage());
        }

        // Fat/uber JARs repackaged without directory entries make the probe above blind. Enumerate the owning archive
        // through the agent definition we just loaded successfully, which is guaranteed to live in the same archive.
        final String anchor = basePath + "/" + agentName + "/" + AGENT_DEFINITION_FILE;
        try {
            // A layout that cannot be enumerated lands here as false, same as "nothing found". That is deliberate: this
            // probe only picks between two log levels, and the walker has already warned about the layout itself.
            return !resourceTreeWalker.list(directoryPath, anchor).getFiles().isEmpty();
        } catch (RuntimeException e) {
            log.debug("Failed to probe archive for classpath directory '{}': {}", directoryPath, e.getMessage());
            return false;
        }
    }

}
