package at.aimon.core.agent.impl;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.definition.exception.AgentDefinitionNotFoundException;
import at.aimon.core.agent.definition.parser.AgentDefinitionParser;
import at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser;
import at.aimon.core.skill.CompositeSkillRegistry;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.parser.SkillParser;
import at.aimon.core.subagent.CompositeSubagentRegistry;

/**
 * Agent bundle loader that automatically detects the resource protocol and delegates to the appropriate loader.
 *
 * <p>
 * On each {@link #load(String)} call, this loader resolves the agent definition resource URL via the ClassLoader.
 * When the URL is a JAR resource — the deployed shape — it uses {@link ClasspathAgentBundleLoader}, which reads every
 * class path root.
 *
 * <p>
 * When the URL uses the {@code file://} protocol — the IDE and {@code gradle run} shape — it additionally uses
 * {@link FileSystemAgentBundleLoader} over the directory the definition was found in, which is what makes local
 * authoring work without an index file. That directory is only one place, though, and a real application's skills and
 * subagents usually arrive from elsewhere on the class path, so the two are <em>composed</em> rather than chosen
 * between: class path underneath, working directory on top. An agent therefore has the same skills and the same
 * subagents whether it was started from a jar or from a directory, and a locally edited copy still wins over the
 * packaged one it shadows.
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
 *     AgentBundleLoader loader = new AdaptiveAgentBundleLoader();
 *     AgentBundle bundle = loader.load("default");
 *     // Automatically uses filesystem loader if resources are on disk,
 *     // or classpath loader if packaged in a JAR.
 * }
 * </pre>
 *
 * @see FileSystemAgentBundleLoader
 * @see ClasspathAgentBundleLoader
 */
public final class AdaptiveAgentBundleLoader implements AgentBundleLoader {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveAgentBundleLoader.class);

    private static final String DEFAULT_BASE_PATH = "agents";
    private static final String AGENT_DEFINITION_FILE = "agent.md";

    private final String basePath;
    private final AgentDefinitionParser parser;
    private final ClassLoader classLoader;
    private final SkillParser skillParser;

    /**
     * Creates a new AdaptiveAgentBundleLoader with default settings.
     */
    public AdaptiveAgentBundleLoader() {
        this(DEFAULT_BASE_PATH);
    }

    /**
     * Creates a new AdaptiveAgentBundleLoader with a custom base path.
     *
     * @param basePath
     *            the classpath base path for agent directories (must not be null)
     * @throws NullPointerException
     *             if basePath is null
     */
    public AdaptiveAgentBundleLoader(String basePath) {
        this(basePath, new MarkdownAgentDefinitionParser(), Thread.currentThread().getContextClassLoader());
    }

    /**
     * Creates a new AdaptiveAgentBundleLoader with custom dependencies and a default {@link MarkdownSkillParser}.
     * Frontmatter {@code shell} hook actions on bundled skills will fail at parse time; use
     * {@link #AdaptiveAgentBundleLoader(String, AgentDefinitionParser, ClassLoader, SkillParser)} to inject a parser
     * wired with a {@link at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor
     * DefaultShellActionExecutor}.
     *
     * @param basePath
     *            the classpath base path for agent directories (must not be null)
     * @param parser
     *            the agent definition parser (must not be null)
     * @param classLoader
     *            the class loader to use for resource lookup (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public AdaptiveAgentBundleLoader(String basePath, AgentDefinitionParser parser, ClassLoader classLoader) {
        this(basePath, parser, classLoader, new MarkdownSkillParser());
    }

    /**
     * Creates a new AdaptiveAgentBundleLoader with custom dependencies and an injected skill parser.
     *
     * @param basePath
     *            the classpath base path for agent directories (must not be null)
     * @param parser
     *            the agent definition parser (must not be null)
     * @param classLoader
     *            the class loader to use for resource lookup (must not be null)
     * @param skillParser
     *            the skill parser used by the delegate loaders for bundled skills (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public AdaptiveAgentBundleLoader(String basePath, AgentDefinitionParser parser, ClassLoader classLoader,
            SkillParser skillParser) {
        this.basePath = Objects.requireNonNull(basePath, "Base path cannot be null");
        this.parser = Objects.requireNonNull(parser, "Parser cannot be null");
        this.classLoader = Objects.requireNonNull(classLoader, "ClassLoader cannot be null");
        this.skillParser = Objects.requireNonNull(skillParser, "Skill parser cannot be null");
    }

    /**
     * Loads an agent bundle by name, automatically selecting the appropriate loading strategy.
     *
     * <p>
     * Detects the resource protocol by looking up the agent definition URL. If the resource is on the local filesystem
     * ({@code file://} protocol), composes {@link FileSystemAgentBundleLoader} over
     * {@link ClasspathAgentBundleLoader}. Otherwise, uses {@link ClasspathAgentBundleLoader} alone.
     *
     * @param name
     *            the agent name (must not be null)
     * @return the loaded agent bundle (never null)
     * @throws AgentDefinitionNotFoundException
     *             if the agent definition is not found
     * @throws NullPointerException
     *             if name is null
     */
    @Override
    public AgentBundle load(String name) {
        Objects.requireNonNull(name, "Agent name cannot be null");

        final String resourcePath = basePath + "/" + name + "/" + AGENT_DEFINITION_FILE;
        final URL url = classLoader.getResource(resourcePath);

        if (url == null) {
            throw new AgentDefinitionNotFoundException("Definition not found on classpath: " + resourcePath);
        }

        if ("file".equals(url.getProtocol())) {
            try {
                final Path agentMdPath = Paths.get(url.toURI());
                // agent.md -> {name}/ -> basePath/
                final Path resolvedBasePath = agentMdPath.getParent().getParent();

                log.debug("Using filesystem loader for agent '{}' (resource at '{}')", name, agentMdPath);
                final AgentBundle onDisk = new FileSystemAgentBundleLoader(resolvedBasePath, parser, skillParser)
                        .load(name);
                return layerOverClasspath(name, onDisk);
            } catch (URISyntaxException e) {
                log.warn(
                        "Failed to convert URL to filesystem path for agent '{}', falling back to classpath loader: {}",
                        name, e.getMessage());
            }
        }

        log.debug("Using classpath loader for agent '{}' (protocol: '{}')", name, url.getProtocol());
        return new ClasspathAgentBundleLoader(basePath, parser, classLoader, skillParser).load(name);
    }

    /**
     * Puts what the class path holds underneath what the working directory holds.
     *
     * <p>
     * The filesystem loader is handed <em>one</em> directory — the one the agent definition happened to be found in
     * — and can see nothing outside it. That is fine for the definition, which lives in exactly one place, and wrong
     * for everything bundled alongside it, which does not: an application's own {@code agent.md} sits on disk while
     * its skills and subagents arrive from dependency jars. Loading only from disk made those disappear whenever the
     * definition was unpacked, so an agent had a subagent when deployed and no subagent when developed — the one
     * difference that is hardest to notice, because development is where nobody is checking.
     *
     * <p>
     * Composing puts the class path first and the working directory second, so a locally edited skill still
     * overrides the packaged one of the same name (see {@link CompositeSkillRegistry}: later wins). Local authoring
     * without an index keeps working, because that is the filesystem layer's job and it still runs.
     */
    private AgentBundle layerOverClasspath(String name, AgentBundle onDisk) {
        final AgentBundle onClasspath = ClasspathAgentBundleLoader
                .asUnderlay(basePath, parser, classLoader, skillParser).load(name);

        return AgentBundle.builder().agent(onDisk.getAgent())
                .skillRegistry(
                        layer(onClasspath.getSkillRegistry(), onDisk.getSkillRegistry(), CompositeSkillRegistry::new))
                .subagentRegistry(layer(onClasspath.getSubagentRegistry(), onDisk.getSubagentRegistry(),
                        CompositeSubagentRegistry::new))
                .build();
    }

    /**
     * Merges two optional registries, or returns whichever one exists — the composites reject an empty list and a
     * one-element composite is only indirection.
     */
    private static <R> R layer(Optional<R> base, Optional<R> overlay, Function<List<R>, R> composite) {
        if (base.isEmpty()) {
            return overlay.orElse(null);
        }
        if (overlay.isEmpty()) {
            return base.get();
        }
        return composite.apply(List.of(base.get(), overlay.get()));
    }
}
