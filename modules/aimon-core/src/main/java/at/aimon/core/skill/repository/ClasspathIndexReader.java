package at.aimon.core.skill.repository;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;

import at.aimon.core.skill.exception.SkillRepositoryException;

/**
 * Reads and merges the skill {@code index} files reachable on a class path.
 *
 * <p>
 * The same skills base may be contributed by several artifacts at once — a Spring Boot application that assembles
 * multiple AIMON modules is exactly that situation — so an index must be read through
 * {@link ClassLoader#getResources(String)} (every root) rather than {@link ClassLoader#getResourceAsStream(String)}
 * (the first root only). Two collaborators need that merge and must agree on it exactly:
 * {@link ClasspathSkillRepository}, which <em>advertises</em> bundled skills, and {@link BundledSkillMaterializer},
 * which <em>copies</em> them onto the workspace. If they disagree, the registry advertises a skill whose supplementary
 * files were never materialized — which surfaces only as a missing {@code scripts/} file at run time. Hence one
 * implementation, shared.
 *
 * <p>
 * The merge policy is:
 *
 * <ul>
 * <li><b>Source order</b> follows the class loader's resource enumeration order; <b>name order within a source</b> is
 * the declaration order of that index file. The result is those per-source lists concatenated and de-duplicated, so a
 * single-source class path behaves exactly as a single-index read always did.
 * <li><b>Duplicate names across sources are first-wins.</b> The later declaration is dropped and a {@code WARN} is
 * logged naming the skill and <em>both</em> index URLs — a conflict is never resolved silently. First-wins matches
 * {@link ClassLoader#getResource(String)}, which is how both collaborators resolve a skill body, so "first index wins"
 * and "first body wins" designate the same root.
 * <li><b>Identical URLs are de-duplicated first</b> (the same JAR listed twice on the class path), so they cannot
 * produce a spurious conflict warning.
 * <li><b>The merged list is deliberately not sorted.</b> An index file's declaration order is meaningful and is fully
 * independent of class path enumeration order.
 * </ul>
 *
 * <p>
 * Diagnostics are emitted through the <em>owner's</em> logger, injected at construction: an operator chasing a missing
 * skill reads the log of the class they configured ({@code ClasspathSkillRepository} or
 * {@code BundledSkillMaterializer}), not of this internal helper.
 *
 * <p>
 * Package-private and thread-safe: holds only immutable collaborators.
 */
final class ClasspathIndexReader {

    private final ClassLoader classLoader;
    private final Logger log;

    /**
     * Creates a reader over the given class loader.
     *
     * @param classLoader
     *            The class loader whose roots are searched for index files (must not be null)
     * @param log
     *            The owner's logger, used for conflict and enumeration diagnostics (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    ClasspathIndexReader(ClassLoader classLoader, Logger log) {
        this.classLoader = Objects.requireNonNull(classLoader, "ClassLoader cannot be null");
        this.log = Objects.requireNonNull(log, "Logger cannot be null");
    }

    /**
     * Reads every index file reachable at {@code indexPath} and merges the declared names.
     *
     * @param indexPath
     *            The full class path resource path of the index file, e.g. {@code "agents/default/skills/index"} (must
     *            not be null)
     * @return The merged names in declaration order (never null, empty when no root carries an index)
     * @throws NullPointerException
     *             if indexPath is null
     * @throws SkillRepositoryException
     *             if an index file exists but cannot be read
     */
    List<String> readMergedNames(String indexPath) {
        Objects.requireNonNull(indexPath, "Index path cannot be null");

        final Map<String, String> nameToSource = new LinkedHashMap<>();
        for (URL indexUrl : findIndexResources(indexPath)) {
            final String source = indexUrl.toExternalForm();
            for (String name : readIndexNames(indexUrl)) {
                final String previousSource = nameToSource.putIfAbsent(name, source);
                if (previousSource != null && !previousSource.equals(source)) {
                    log.warn(
                            "Duplicate skill name '{}' declared by multiple classpath index files. "
                                    + "Keeping the declaration from '{}' and ignoring '{}'.",
                            name, previousSource, source);
                }
            }
        }
        return List.copyOf(nameToSource.keySet());
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
                    + "skills contributed by other artifacts may be missing.", indexPath, e.getMessage());
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
            throw new SkillRepositoryException("Failed to read classpath resource: " + indexUrl, e);
        }
    }
}
