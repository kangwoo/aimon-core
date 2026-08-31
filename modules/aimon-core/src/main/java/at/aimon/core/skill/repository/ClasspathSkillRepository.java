package at.aimon.core.skill.repository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.skill.exception.SkillRepositoryException;

/**
 * Classpath resource-based implementation of SkillRepository.
 *
 * <p>
 * Loads skill definitions from classpath resources (e.g., JAR-internal files). Used to provide built-in skills that
 * ship with the framework.
 *
 * <p>
 * Uses an index file ({@code basePath + "/index"}) to enumerate available skill names, since classpath directory
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
 * logged naming the skill and <em>both</em> index URLs — a conflict is never resolved silently.
 * <li><b>Identical URLs are de-duplicated first</b> (the same JAR listed twice on the class path), so they cannot
 * produce a spurious conflict warning.
 * <li><b>The merged list is deliberately not sorted.</b> An index file's declaration order is meaningful and is fully
 * independent of class path enumeration; only the order <em>between</em> sources follows the class loader. Anchoring to
 * that order is intentional: {@link #findByName(String)} and {@link #exists(String)} resolve a skill body through
 * {@link ClassLoader#getResource(String)}, which uses the same search order, so "first index wins" and "first body
 * wins" designate the same root. Those two methods are unchanged — a skill declared in one root whose {@code SKILL.md}
 * lives in another still resolves.
 * </ul>
 *
 * <p>
 * The merge itself lives in {@link ClasspathIndexReader} because {@link BundledSkillMaterializer} must enumerate the
 * exact same set of names: this repository <em>advertises</em> bundled skills while the materializer <em>copies</em>
 * them onto the workspace, and a skill advertised here but not materialized there would lose its supplementary files.
 *
 * <p>
 * Supplementary files (scripts, references, assets) are not supported for classpath-based skills. These methods return
 * empty maps. Skills requiring supplementary files should be provided as user-defined (file-based) skills.
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
 *     SkillRepository repository = new ClasspathSkillRepository("builtin/skills");
 *     Optional<String> content = repository.findByName("commit");
 *     // → loads classpath:/builtin/skills/commit/SKILL.md
 * }
 * </pre>
 */
public class ClasspathSkillRepository implements SkillRepository {

    private static final Logger log = LoggerFactory.getLogger(ClasspathSkillRepository.class);

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String INDEX_FILE_NAME = "index";

    private final String basePath;
    private final ClassLoader classLoader;
    private final ClasspathIndexReader indexReader;

    /**
     * Creates a new ClasspathSkillRepository using the current thread's context class loader.
     *
     * @param basePath
     *            The classpath base path for skills directory (must not be null)
     * @throws NullPointerException
     *             if basePath is null
     */
    public ClasspathSkillRepository(String basePath) {
        this(basePath, Thread.currentThread().getContextClassLoader());
    }

    /**
     * Creates a new ClasspathSkillRepository with a custom ClassLoader.
     *
     * @param basePath
     *            The classpath base path for skills directory (must not be null)
     * @param classLoader
     *            The ClassLoader to use for resource loading (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public ClasspathSkillRepository(String basePath, ClassLoader classLoader) {
        this.basePath = Objects.requireNonNull(basePath, "Base path cannot be null");
        this.classLoader = Objects.requireNonNull(classLoader, "ClassLoader cannot be null");
        this.indexReader = new ClasspathIndexReader(this.classLoader, log);
    }

    @Override
    public Optional<String> findByName(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");
        final String resourcePath = basePath + "/" + skillName + "/" + SKILL_FILE_NAME;
        return readResource(resourcePath);
    }

    /**
     * Finds every skill name declared by any index file on the class path.
     *
     * <p>
     * Merges all index files reachable at {@code basePath + "/index"} using the first-wins conflict policy described in
     * the class javadoc.
     *
     * @return the merged skill names in declaration order (never null, may be empty)
     * @throws SkillRepositoryException
     *             if an index file exists but cannot be read
     */
    @Override
    public List<String> findAllNames() {
        return indexReader.readMergedNames(basePath + "/" + INDEX_FILE_NAME);
    }

    @Override
    public boolean exists(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");
        final String resourcePath = basePath + "/" + skillName + "/" + SKILL_FILE_NAME;
        return classLoader.getResource(resourcePath) != null;
    }

    @Override
    public Map<String, String> findRootFiles(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");
        return Map.of();
    }

    @Override
    public Map<String, String> findScripts(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");
        return Map.of();
    }

    @Override
    public Map<String, String> findReferences(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");
        return Map.of();
    }

    @Override
    public Map<String, String> findAssets(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");
        return Map.of();
    }

    private Optional<String> readResource(String resourcePath) {
        try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return Optional.empty();
            }
            return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SkillRepositoryException("Failed to read classpath resource: " + resourcePath, e);
        }
    }
}
