package at.aimon.core.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.skill.exception.SkillException;
import at.aimon.core.skill.exception.SkillNotFoundException;
import at.aimon.core.skill.exception.SkillParseException;
import at.aimon.core.skill.exception.SkillRepositoryException;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.parser.SkillParser;
import at.aimon.core.skill.repository.SkillRepository;
import at.aimon.core.skill.repository.SkillSource;
import at.aimon.core.skill.repository.VfsSkillRepository;

/**
 * Default implementation of SkillRegistry with caching.
 *
 * <p>
 * Loads skills from a SkillRepository and caches them in memory. Provides thread-safe access to skills.
 *
 * <p>
 * Thread-safe, in these specific senses:
 *
 * <ul>
 * <li>Concurrent {@link #getSkill(String)} calls for the same name load it <em>once</em> — the loser of the race waits
 * for the winner rather than running its own repository I/O and parse. A miss is not cached, so an absent skill stays
 * loadable once it appears.
 * <li>{@link #reloadAll()} publishes a fully-built cache in one assignment. Readers never observe the intermediate
 * empty state that a clear-then-refill would expose, and a failure part-way through that is not one skill's own (the
 * repository listing, a parse error) leaves the previous cache serving rather than an emptied one.
 * <li>{@link #reloadSkill(String)} and {@link #reloadAll()} exclude each other.
 * </ul>
 *
 * <p>
 * One skill that cannot be loaded does not take the others down (execution-environment design §4.4: "that skill does
 * not load"). {@link #getAllSkills()} and {@link #reloadAll()} skip a skill whose load fails with a
 * {@link SkillRepositoryException} — a link the link rule refuses, a scan that fails — and log it at WARN;
 * {@link #getSkill(String)} of that skill keeps throwing the exception that says why.
 *
 * <p>
 * What is <em>not</em> promised: a {@code getSkill} that overlaps a reload may deposit its entry into the cache the
 * reload is replacing, in which case that entry is discarded and the next call loads again. This costs a repeated load,
 * never a stale or partial skill.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualFileSystem fileSystem = new LocalFileSystem(basePath);
 *     SkillRepository repository = new VfsSkillRepository(fileSystem, "skills");
 *     SkillParser parser = new MarkdownSkillParser();
 *
 *     SkillRegistry registry = new DefaultSkillRegistry(repository, parser);
 *
 *     // Get skill (loads and caches)
 *     Optional<Skill> skill = registry.getSkill("alert-analysis");
 *
 *     // Reload skill (clears cache and reloads)
 *     registry.reloadSkill("alert-analysis");
 * }
 * </pre>
 */
public class DefaultSkillRegistry implements SkillRegistry {

    /** The file whose presence makes a directory a skill. */
    private static final String SKILL_FILE = "SKILL.md";

    private static final Logger log = LoggerFactory.getLogger(DefaultSkillRegistry.class);

    private final SkillRepository repository;
    private final SkillParser parser;

    /** Replaced wholesale by {@link #reloadAll()}; readers snapshot the reference before touching the map. */
    private volatile ConcurrentMap<String, Skill> cache;

    /**
     * Creates a new DefaultSkillRegistry.
     *
     * @param repository
     *            The skill repository (must not be null)
     * @param parser
     *            The skill parser (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultSkillRegistry(SkillRepository repository, SkillParser parser) {
        this.repository = Objects.requireNonNull(repository, "Repository cannot be null");
        this.parser = Objects.requireNonNull(parser, "Parser cannot be null");
        cache = new ConcurrentHashMap<>();
    }

    /**
     * DefaultSkillRegistry를 생성한다. Frontmatter {@code shell} hook actions are disabled because the parser is built with
     * a {@link MarkdownSkillParser} that defaults to a no-op shell executor.
     *
     * @param fileSystem
     *            가상 파일 시스템 (null 불가)
     * @param skillsDirectory
     *            스킬 디렉터리 경로 (null 불가)
     */
    public DefaultSkillRegistry(VirtualFileSystem fileSystem, String skillsDirectory) {
        this(fileSystem, skillsDirectory, new MarkdownSkillParser());
    }

    /**
     * Creates a registry that loads skills from {@code skillsDirectory} on {@code fileSystem} using the supplied
     * parser.
     *
     * <p>
     * Wire a parser built with a
     * {@link at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor DefaultShellActionExecutor} here when
     * skills should be allowed to declare {@code shell} hook actions; otherwise {@code shell} actions fail at parse
     * time.
     *
     * @param fileSystem
     *            the virtual filesystem (must not be null)
     * @param skillsDirectory
     *            the directory under {@code fileSystem} that holds skill definitions (must not be null)
     * @param parser
     *            the skill parser used by the underlying repository (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultSkillRegistry(VirtualFileSystem fileSystem, String skillsDirectory, SkillParser parser) {
        this(new VfsSkillRepository(fileSystem, skillsDirectory), parser);
    }

    @Override
    public Optional<Skill> getSkill(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        // computeIfAbsent, not get-then-put: the latter lets N threads asking for the same skill each run the
        // repository I/O and the parse. Returning null for a miss leaves the entry uncached, as before.
        return Optional.ofNullable(cache.computeIfAbsent(skillName, name -> loadComplete(name).orElse(null)));
    }

    /**
     * Loads a skill and enriches it with all of its bundled files and its staging resource.
     *
     * <p>
     * Returns empty when the repository has no SKILL.md for {@code skillName}. The returned skill carries the
     * three conventional file categories (rootFiles, scripts, references, assets), the comprehensive {@code files} map
     * (covering arbitrary sub-directories such as {@code templates/}), and the {@link StagedResource} its directory is
     * staged from. The resource is scanned — listed, {@code .stageignore}d and hashed — here, once per (re)load, so
     * staging never re-reads the directory to decide whether a copy exists (execution-environment design §4.4). A
     * repository that finds the skill but gives no staging source is defective, and the skill does not load.
     *
     * @param skillName
     *            the skill name (must not be null)
     * @return the fully-enriched skill, or empty if not found
     */
    private Optional<Skill> loadComplete(String skillName) {
        final Optional<String> content = repository.findByName(skillName);
        if (content.isEmpty()) {
            return Optional.empty();
        }

        // Parse skill content
        final Skill skill = parser.parse(skillName, content.get());

        // Load additional files (rootFiles, scripts, references, assets) plus the comprehensive file map
        final Map<String, String> rootFiles = repository.findRootFiles(skillName);
        final Map<String, String> scripts = repository.findScripts(skillName);
        final Map<String, String> references = repository.findReferences(skillName);
        final Map<String, String> assets = repository.findAssets(skillName);
        final Map<String, String> files = repository.findAllFiles(skillName);

        // Build complete skill with all files and the scanned staging resource
        final Skill.Builder builder = Skill.builder().name(skill.getName()).metadata(skill.getMetadata())
                .content(skill.getContent()).rootFiles(rootFiles).scripts(scripts).references(references).assets(assets)
                .files(files).stagedResource(scanSource(skillName));
        return Optional.of(builder.build());
    }

    private StagedResource scanSource(String skillName) {
        final SkillSource source = repository.resolveSource(skillName)
                .orElseThrow(() -> new SkillRepositoryException(repository.getClass().getName()
                        + " returned no staging source for existing skill '" + skillName + "'"));
        final StagedResource resource;
        try {
            resource = StagedResource.scan(source.getFileSystem(), source.getDirectory(), skillName);
        } catch (RuntimeException e) {
            throw new SkillRepositoryException(
                    String.format("Failed to scan skill '%s' for staging: %s", skillName, e.getMessage()), e);
        }
        // A source directory that holds SKILL.md but lists no file at all could not see its own contents (a link it
        // did not follow, say). Staging it would serve an empty copy under ${AIMON_SKILL_DIR} with nothing reporting
        // it; fail here instead (design §4.4, the link rule). A directory whose .stageignore excluded every file it
        // lists is what its author asked for, and stages empty.
        if (resource.getFiles().isEmpty() && holdsSkillFile(source) && listsNothing(source)) {
            throw new SkillRepositoryException(String.format(
                    "Skill '%s' holds SKILL.md but its directory '%s' scanned to zero files; refusing to stage an"
                            + " empty copy",
                    skillName, source.getDirectory()));
        }
        return resource;
    }

    private static boolean listsNothing(SkillSource source) {
        try {
            return source.getFileSystem().listRecursive(source.getDirectory()).isEmpty();
        } catch (RuntimeException e) {
            throw new SkillRepositoryException("Failed to list " + source.getDirectory() + " of a scanned skill", e);
        }
    }

    private static boolean holdsSkillFile(SkillSource source) {
        final String directory = source.getDirectory().replaceAll("/+$", "");
        final String skillFile = directory.isEmpty() || ".".equals(directory)
                ? SKILL_FILE
                : directory + "/" + SKILL_FILE;
        try {
            return source.getFileSystem().exists(skillFile);
        } catch (RuntimeException e) {
            throw new SkillRepositoryException("Failed to check " + skillFile + " of a scanned skill", e);
        }
    }

    @Override
    public List<Skill> getAllSkills() {
        final List<String> skillNames = repository.findAllNames();
        final List<Skill> skills = new ArrayList<>();

        for (String skillName : skillNames) {
            try {
                getSkill(skillName).ifPresent(skills::add);
            } catch (SkillRepositoryException | SkillParseException e) {
                logSkipped(skillName, e);
            }
        }

        return skills;
    }

    @Override
    public synchronized void reloadSkill(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        // Remove first: a skill that has since been deleted from the repository must not survive its own failed
        // reload, and SkillNotFoundException below is the report that it is gone.
        cache.remove(skillName);

        // Load fresh
        final Skill completeSkill = loadComplete(skillName).orElseThrow(() -> new SkillNotFoundException(skillName));

        cache.put(skillName, completeSkill);
    }

    @Override
    public synchronized void reloadAll() {
        // Build first, publish once. clear-then-refill leaves a window in which another thread sees an empty
        // registry and starts loading against it, and abandons the cache emptied if a reload throws part-way.
        final ConcurrentMap<String, Skill> reloaded = new ConcurrentHashMap<>();
        for (String skillName : repository.findAllNames()) {
            try {
                loadComplete(skillName).ifPresent(skill -> reloaded.put(skillName, skill));
            } catch (SkillRepositoryException | SkillParseException e) {
                logSkipped(skillName, e);
            }
        }
        cache = reloaded;
    }

    /**
     * A skill that fails to load is left out of a listing rather than failing it: the tool definition, the command
     * list and the banner are all built from {@link #getAllSkills()}. It is not cached, so {@link #getSkill(String)}
     * reports the same error to whoever asks for it by name. A skill file that does not parse is skipped the same way
     * as one that cannot be read — a rejected frontmatter (a hook the parser refuses, say) is that one skill's
     * problem, not the listing's.
     */
    private static void logSkipped(String skillName, SkillException e) {
        log.warn("Skill '{}' is not loaded: {}", skillName, e.getMessage());
    }
}
