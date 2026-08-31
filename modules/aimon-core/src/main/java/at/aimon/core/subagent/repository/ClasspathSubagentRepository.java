package at.aimon.core.subagent.repository;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.subagent.exception.SubagentRepositoryException;

/**
 * Classpath resource-based implementation of SubagentRepository.
 *
 * <p>
 * Loads subagent definitions from classpath resources (e.g., JAR-internal files). Used to provide built-in subagents
 * that ship with the framework.
 *
 * <p>
 * Uses an index file ({@code basePath + "/index"}) to enumerate available subagent names, since classpath directory
 * listing is not reliably supported across all environments.
 *
 * <h2>Index merging and conflicts</h2>
 *
 * <p>
 * The same {@code basePath} may be contributed by several artifacts at once — a Spring Boot application that assembles
 * multiple AIMON modules is exactly that situation. {@link #findAllNames()} therefore returns the <em>union</em> of
 * every index file reachable through {@link ClassLoader#getResources(String)}, not just the first one:
 *
 * <ul>
 * <li><b>Source order</b> follows the class loader's resource enumeration order; <b>name order within a source</b> is
 * the declaration order of that index file. The result is those per-source lists concatenated and de-duplicated, so a
 * single-source class path behaves exactly as it always did.
 * <li><b>Duplicate names across sources are first-wins.</b> The later declaration is dropped and a {@code WARN} is
 * logged naming the subagent and <em>both</em> index URLs — a conflict is never resolved silently.
 * <li><b>Identical URLs are de-duplicated first</b> (the same JAR listed twice on the class path), so they cannot
 * produce a spurious conflict warning.
 * <li><b>The merged list is deliberately not sorted.</b> An index file's declaration order is meaningful and is fully
 * independent of class path enumeration; only the order <em>between</em> sources follows the class loader. Anchoring to
 * that order is intentional: {@link #findByName(String)} and {@link #exists(String)} resolve a subagent body through
 * {@link ClassLoader#getResource(String)}, which uses the same search order, so "first index wins" and "first body
 * wins" designate the same root. Those two methods are unchanged — a subagent declared in one root whose {@code .md}
 * file lives in another still resolves.
 * </ul>
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SubagentRepository repository = new ClasspathSubagentRepository("builtin/agents");
 *     Optional<String> content = repository.findByName("explore");
 *     // → loads classpath:/builtin/agents/explore.md
 * }
 * </pre>
 */
public class ClasspathSubagentRepository implements SubagentRepository {

    private static final Logger log = LoggerFactory.getLogger(ClasspathSubagentRepository.class);

    private static final String INDEX_FILE_NAME = "index";

    private final String basePath;
    private final ClassLoader classLoader;

    /**
     * Creates a new ClasspathSubagentRepository using the current thread's context class loader.
     *
     * @param basePath
     *            The classpath base path for agents directory (must not be null)
     * @throws NullPointerException
     *             if basePath is null
     */
    public ClasspathSubagentRepository(String basePath) {
        this(basePath, Thread.currentThread().getContextClassLoader());
    }

    /**
     * Creates a new ClasspathSubagentRepository with a custom ClassLoader.
     *
     * @param basePath
     *            The classpath base path for agents directory (must not be null)
     * @param classLoader
     *            The ClassLoader to use for resource loading (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public ClasspathSubagentRepository(String basePath, ClassLoader classLoader) {
        this.basePath = Objects.requireNonNull(basePath, "Base path cannot be null");
        this.classLoader = Objects.requireNonNull(classLoader, "ClassLoader cannot be null");
    }

    @Override
    public Optional<String> findByName(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");
        final String resourcePath = basePath + "/" + subagentName + ".md";
        return readResource(resourcePath);
    }

    /**
     * Finds every subagent name declared by any index file on the class path.
     *
     * <p>
     * Merges all index files reachable at {@code basePath + "/index"} using the first-wins conflict policy described in
     * the class javadoc.
     *
     * @return the merged subagent names in declaration order (never null, may be empty)
     * @throws SubagentRepositoryException
     *             if an index file exists but cannot be read
     */
    @Override
    public List<String> findAllNames() {
        final String indexPath = basePath + "/" + INDEX_FILE_NAME;
        final Map<String, String> nameToSource = new LinkedHashMap<>();

        for (URL indexUrl : findIndexResources(indexPath)) {
            final String source = indexUrl.toExternalForm();
            for (String name : readIndexNames(indexUrl)) {
                final String previousSource = nameToSource.putIfAbsent(name, source);
                if (previousSource != null && !previousSource.equals(source)) {
                    log.warn(
                            "Duplicate subagent name '{}' declared by multiple classpath index files. "
                                    + "Keeping the declaration from '{}' and ignoring '{}'.",
                            name, previousSource, source);
                }
            }
        }

        return List.copyOf(nameToSource.keySet());
    }

    @Override
    public boolean exists(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");
        final String resourcePath = basePath + "/" + subagentName + ".md";
        return classLoader.getResource(resourcePath) != null;
    }

    private List<URL> findIndexResources(String indexPath) {
        final Map<String, URL> distinct = new LinkedHashMap<>();
        try {
            final Enumeration<URL> resources = classLoader.getResources(indexPath);
            while (resources.hasMoreElements()) {
                final URL resource = resources.nextElement();
                distinct.putIfAbsent(resource.toExternalForm(), resource);
            }
        } catch (IOException e) {
            log.warn("Failed to enumerate classpath index files at '{}': {}. Falling back to the first match only — "
                    + "subagents contributed by other artifacts may be missing.", indexPath, e.getMessage());
            final URL single = classLoader.getResource(indexPath);
            if (single != null) {
                distinct.putIfAbsent(single.toExternalForm(), single);
            }
        }
        return List.copyOf(distinct.values());
    }

    private List<String> readIndexNames(URL indexUrl) {
        try (InputStream is = indexUrl.openStream()) {
            final String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return content.lines().map(String::trim).filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#")).toList();
        } catch (IOException e) {
            throw new SubagentRepositoryException("Failed to read classpath resource: " + indexUrl, e);
        }
    }

    private Optional<String> readResource(String resourcePath) {
        try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return Optional.empty();
            }
            return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SubagentRepositoryException("Failed to read classpath resource: " + resourcePath, e);
        }
    }
}
