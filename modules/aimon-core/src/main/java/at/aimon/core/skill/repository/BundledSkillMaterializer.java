package at.aimon.core.skill.repository;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.skill.exception.SkillRepositoryException;

/**
 * Copies bundled (class path) skills onto a {@link VirtualFileSystem} so that their supplementary files become real,
 * resolvable workspace files.
 *
 * <p>
 * Bundled skills ship inside the application's class path (an exploded {@code build/resources} tree in development, or
 * a
 * packaged JAR in production). Neither layout is reachable through the agent's {@code Read}/{@code Bash} tools, which
 * operate on the workspace {@link VirtualFileSystem}: JAR entries are not files at all, and exploded resources resolve
 * to absolute OS paths outside the workspace sandbox. Materializing each bundled skill tree into a workspace directory
 * (for example {@code .aimon/bundled-skills/<name>/}) makes its {@code scripts/}, {@code references/}, {@code assets/},
 * and arbitrary directories such as {@code templates/} both readable and executable, and gives the skill a stable
 * {@code ${AIMON_SKILL_DIR}} anchor.
 *
 * <p>
 * Materialization is overwrite-on-bootstrap: each skill's destination directory is cleared and rewritten so the
 * workspace copy matches the shipped class path content, and skills no longer present in the index are pruned from the
 * target directory. Each skill's resources are read in full before its destination is cleared, so a read failure leaves
 * the previously materialized copy intact. Failures for a single skill — and a failure to read the index itself — are
 * logged and degrade gracefully so materialization never aborts agent bootstrap.
 *
 * <h2>Multiple class path roots</h2>
 *
 * <p>
 * Skill names are enumerated through {@link ClasspathIndexReader}, which merges <em>every</em> index file reachable at
 * {@code classpathSkillsBase + "/index"} — the same set {@link ClasspathSkillRepository#findAllNames()} advertises, and
 * with the same first-wins conflict policy. That agreement is load-bearing: the class path registry is layered
 * <em>beneath</em> the materialized one, so a skill advertised by the registry but skipped here would resolve its
 * {@code SKILL.md} text from the class path layer and silently have no {@code scripts/}, {@code references/},
 * {@code assets/} or base directory.
 *
 * <p>
 * Per-skill resolution is unaffected by which root declared the name: the tree walk and every resource read go through
 * {@link ClassLoader#getResource(String)} / {@link ClassLoader#getResourceAsStream(String)}, which search all roots in
 * order and resolve against the first one carrying that path. A skill declared only in the second JAR is therefore
 * materialized from that second JAR. When two roots both carry the same skill directory, the first root's copy wins —
 * matching the index merge's first-wins policy and {@link ClasspathSkillRepository#findByName(String)}.
 *
 * <p>
 * Thread-safe: holds only immutable collaborators.
 */
public final class BundledSkillMaterializer {

    private static final Logger log = LoggerFactory.getLogger(BundledSkillMaterializer.class);

    private static final String INDEX_FILE_NAME = "index";
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final ClassLoader classLoader;
    private final ClasspathResourceTreeWalker walker;
    private final ClasspathIndexReader indexReader;

    /**
     * Creates a materializer that reads bundled skills from the given class loader.
     *
     * @param classLoader
     *            The class loader used to read bundled skill resources (must not be null)
     * @throws NullPointerException
     *             if classLoader is null
     */
    public BundledSkillMaterializer(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "ClassLoader cannot be null");
        this.walker = new ClasspathResourceTreeWalker(this.classLoader);
        this.indexReader = new ClasspathIndexReader(this.classLoader, log);
    }

    /**
     * Copies every bundled skill found under {@code classpathSkillsBase} into {@code targetDir} on {@code fileSystem},
     * overwriting any previously materialized copy.
     *
     * <p>
     * Skill names are enumerated from <em>every</em> {@code index} file reachable under {@code classpathSkillsBase},
     * merged exactly as {@link ClasspathSkillRepository#findAllNames()} merges them. When no root carries an index (no
     * bundled skills), this is a no-op apart from pruning.
     *
     * @param classpathSkillsBase
     *            The class path base holding bundled skills (e.g. {@code "agents/default/skills"}); must not be null
     * @param fileSystem
     *            The destination virtual filesystem (must not be null)
     * @param targetDir
     *            The destination directory on {@code fileSystem} (e.g. {@code ".aimon/bundled-skills"}); must not be
     *            null
     * @return The names of the skills that were materialized (i.e. that contained a {@code SKILL.md})
     * @throws NullPointerException
     *             if any argument is null
     */
    public List<String> materialize(String classpathSkillsBase, VirtualFileSystem fileSystem, String targetDir) {
        Objects.requireNonNull(classpathSkillsBase, "Classpath skills base cannot be null");
        Objects.requireNonNull(fileSystem, "File system cannot be null");
        Objects.requireNonNull(targetDir, "Target directory cannot be null");

        final List<String> skillNames;
        try {
            skillNames = readIndex(classpathSkillsBase);
        } catch (RuntimeException e) {
            // Honour the "never aborts bootstrap" contract: a failure to read the index degrades to a no-op.
            log.warn("Failed to read bundled skill index under '{}'; skipping materialization: {}", classpathSkillsBase,
                    e.getMessage());
            return List.of();
        }
        if (skillNames.isEmpty()) {
            log.debug("No bundled skills to materialize under classpath base '{}'", classpathSkillsBase);
            // An empty or absent index is an authoritative "ships nothing" signal (distinct from a transient index-read
            // failure, handled above): prune every previously materialized skill so the target tree matches.
            pruneStaleSkills(fileSystem, targetDir, new HashSet<>());
            return List.of();
        }

        final List<String> materialized = new ArrayList<>();
        for (String skillName : skillNames) {
            try {
                if (materializeSkill(classpathSkillsBase, skillName, fileSystem, targetDir)) {
                    materialized.add(skillName);
                }
            } catch (RuntimeException e) {
                // A single malformed bundled skill must never abort agent bootstrap.
                log.warn("Failed to materialize bundled skill '{}': {}", skillName, e.getMessage(), e);
            }
        }

        // Remove materialized copies of skills that are no longer in the index so the target tree matches the shipped
        // content (the VFS-backed bundled registry serves by directory scan, not by index). The keep-set is the MERGED
        // name set, so a skill contributed by a second class path root is never pruned right after being materialized.
        pruneStaleSkills(fileSystem, targetDir, new HashSet<>(skillNames));

        log.info("Materialized {} bundled skill(s) from '{}' into '{}'", materialized.size(), classpathSkillsBase,
                targetDir);
        return materialized;
    }

    private boolean materializeSkill(String classpathSkillsBase, String skillName, VirtualFileSystem fileSystem,
            String targetDir) {
        final String sourceDir = classpathSkillsBase + '/' + skillName;
        final ResourceTreeListing listing = walker.list(sourceDir, sourceDir + '/' + SKILL_FILE_NAME);
        if (!listing.isEnumerated()) {
            // Say what actually happened. The files are probably right there — this deployment layout is simply outside
            // the supported set, and claiming the directory is empty would send the reader looking for missing files.
            log.warn(
                    "Bundled skill '{}' cannot be materialized from '{}': class path layout '{}' cannot be enumerated"
                            + " (its files may well be present). See the supported deployment shapes in"
                            + " docs/getting-started/embedding-agent-in-application.md §2.4",
                    skillName, sourceDir, listing.getUnsupportedProtocol().orElse("unknown"));
            return false;
        }
        final List<String> relativeFiles = listing.getFiles();
        if (relativeFiles.isEmpty()) {
            log.warn("Bundled skill '{}' has no files under '{}'; skipping", skillName, sourceDir);
            return false;
        }

        // Read every resource up-front so that a read failure leaves the previously materialized copy intact (the
        // destination directory is not cleared until all reads have succeeded).
        final Map<String, byte[]> contents = new LinkedHashMap<>();
        boolean hasSkillFile = false;
        for (String relativePath : relativeFiles) {
            final byte[] bytes = readResource(sourceDir + '/' + relativePath);
            if (bytes == null) {
                log.warn("Skipping unreadable bundled resource '{}'", sourceDir + '/' + relativePath);
                continue;
            }
            contents.put(relativePath, bytes);
            if (SKILL_FILE_NAME.equals(relativePath)) {
                hasSkillFile = true;
            }
        }
        if (contents.isEmpty()) {
            log.warn("Bundled skill '{}' had no readable files under '{}'; skipping", skillName, sourceDir);
            return false;
        }

        final String destinationDir = targetDir + '/' + skillName;
        clearExisting(fileSystem, destinationDir);
        try {
            contents.forEach((relativePath, bytes) -> fileSystem.write(destinationDir + '/' + relativePath, bytes));
        } catch (RuntimeException e) {
            // A write failed partway through. Fail closed: remove the half-written copy so the skill is simply absent
            // (and served by the class path fallback layer) rather than served in a corrupt, partially-written state.
            clearExisting(fileSystem, destinationDir);
            throw e;
        }

        if (!hasSkillFile) {
            log.warn("Bundled skill '{}' did not contain a {} file under '{}'", skillName, SKILL_FILE_NAME, sourceDir);
        }
        return hasSkillFile;
    }

    private void pruneStaleSkills(VirtualFileSystem fileSystem, String targetDir, Set<String> keepNames) {
        try {
            if (!fileSystem.exists(targetDir) || !fileSystem.isDirectory(targetDir)) {
                return;
            }
            for (String childPath : fileSystem.list(targetDir)) {
                if (!fileSystem.isDirectory(childPath)) {
                    continue;
                }
                final String name = lastSegment(childPath);
                if (keepNames.contains(name)) {
                    continue;
                }
                try {
                    fileSystem.deleteRecursive(childPath);
                    log.info("Pruned stale materialized skill '{}'", name);
                } catch (UnsupportedOperationException e) {
                    log.debug("deleteRecursive unsupported by backend; cannot prune stale materialized skill '{}'",
                            name);
                } catch (RuntimeException e) {
                    log.warn("Failed to prune stale materialized skill '{}': {}", name, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to prune stale materialized skills under '{}': {}", targetDir, e.getMessage());
        }
    }

    private static String lastSegment(String path) {
        final String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        final int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private void clearExisting(VirtualFileSystem fileSystem, String destinationDir) {
        try {
            if (fileSystem.exists(destinationDir)) {
                fileSystem.deleteRecursive(destinationDir);
            }
        } catch (UnsupportedOperationException e) {
            // Backend cannot recursively delete; per-file overwrite still refreshes content, though stale files that no
            // longer exist in the bundle may linger.
            log.debug("deleteRecursive unsupported by backend; relying on per-file overwrite for '{}'", destinationDir);
        } catch (RuntimeException e) {
            log.warn("Failed to clear existing materialized skill directory '{}': {}", destinationDir, e.getMessage());
        }
    }

    private List<String> readIndex(String classpathSkillsBase) {
        // Merged across every class path root — see the class javadoc for why this must match ClasspathSkillRepository.
        return indexReader.readMergedNames(classpathSkillsBase + '/' + INDEX_FILE_NAME);
    }

    private byte[] readResource(String resourcePath) {
        try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            return is.readAllBytes();
        } catch (IOException e) {
            throw new SkillRepositoryException("Failed to read bundled resource: " + resourcePath, e);
        }
    }
}
