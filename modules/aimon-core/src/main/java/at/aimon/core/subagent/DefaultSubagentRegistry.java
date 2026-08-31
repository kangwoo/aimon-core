package at.aimon.core.subagent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.subagent.parser.MarkdownSubagentParser;
import at.aimon.core.subagent.parser.SubagentContentParser;
import at.aimon.core.subagent.parser.SubagentParser;
import at.aimon.core.subagent.repository.SubagentRepository;
import at.aimon.core.subagent.repository.VfsSubagentRepository;

/**
 * Default implementation of SubagentRegistry.
 *
 * <p>
 * Focuses on caching and registry operations. Delegates filesystem operations to SubagentRepository.
 *
 * <p>
 * Responsibilities (SRP):
 *
 * <ul>
 * <li>Caching parsed subagents (ConcurrentHashMap)
 * <li>Registry interface implementation (lookup, reload)
 * <li>Coordination between repository and parser
 * </ul>
 *
 * <p>
 * Does NOT handle:
 *
 * <ul>
 * <li>File I/O (delegated to SubagentRepository)
 * <li>Parsing (delegated to SubagentParser)
 * </ul>
 *
 * <p>
 * Thread-safe with concurrent access support via ConcurrentHashMap.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualFileSystem fileSystem = new LocalFileSystem(projectRoot);
 *     SubagentRepository repository = new VfsSubagentRepository(fileSystem, "agents");
 *     SubagentParser parser = new MarkdownSubagentParser();
 *     SubagentRegistry registry = new DefaultSubagentRegistry(repository, parser);
 *
 *     Optional<Subagent> subagent = registry.getSubagent("code-reviewer");
 * }
 * </pre>
 */
public class DefaultSubagentRegistry implements SubagentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubagentRegistry.class);

    private final SubagentRepository repository;
    private final SubagentParser subagentParser;
    private volatile Map<String, Subagent> subagents;

    /**
     * Creates a new DefaultSubagentRegistry.
     *
     * @param repository
     *            The repository to use for file operations (must not be null)
     * @param subagentParser
     *            The parser to use for subagent files (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultSubagentRegistry(SubagentRepository repository, SubagentParser subagentParser) {
        this.repository = Objects.requireNonNull(repository, "Repository cannot be null");
        this.subagentParser = Objects.requireNonNull(subagentParser, "Subagent parser cannot be null");
        subagents = loadAllSubagentsToMap();
    }

    /** DefaultSubagentRegistry를 생성한다. */
    public DefaultSubagentRegistry(VirtualFileSystem fileSystem, String agentsDirectory) {
        this(new VfsSubagentRepository(fileSystem, agentsDirectory),
                new MarkdownSubagentParser(new SubagentContentParser()));
    }

    @Override
    public Optional<Subagent> getSubagent(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");
        return Optional.ofNullable(subagents.get(subagentName));
    }

    @Override
    public List<Subagent> getAllSubagents() {
        return List.copyOf(subagents.values());
    }

    @Override
    public synchronized void reloadSubagent(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");

        final Optional<String> content = repository.findByName(subagentName);

        if (content.isPresent()) {
            final Subagent subagent = subagentParser.parse(subagentName, content.get());
            subagents.put(subagentName, subagent);
        } else {
            subagents.remove(subagentName);
        }
    }

    @Override
    public synchronized void reloadAll() {
        subagents = loadAllSubagentsToMap();
    }

    private Map<String, Subagent> loadAllSubagentsToMap() {
        final Map<String, Subagent> result = new ConcurrentHashMap<>();
        final List<String> subagentNames = repository.findAllNames();

        for (String subagentName : subagentNames) {
            try {
                final Optional<String> content = repository.findByName(subagentName);
                if (content.isPresent()) {
                    final Subagent subagent = subagentParser.parse(subagentName, content.get());
                    result.put(subagentName, subagent);
                }
            } catch (Exception e) {
                log.warn("Failed to load subagent '{}': {}", subagentName, e.getMessage());
            }
        }

        return result;
    }
}
