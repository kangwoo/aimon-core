package at.aimon.core.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.skill.exception.SkillNotFoundException;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.parser.SkillParser;
import at.aimon.core.skill.repository.SkillRepository;
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
 * empty state that a clear-then-refill would expose, and a load failure part-way through leaves the previous cache
 * serving rather than an emptied one.
 * <li>{@link #reloadSkill(String)} and {@link #reloadAll()} exclude each other.
 * </ul>
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
     * Loads a skill and enriches it with all of its bundled files and base directory.
     *
     * <p>
     * Returns empty when the repository has no SKILL.md for {@code skillName}. The returned skill carries the
     * three conventional file categories (rootFiles, scripts, references, assets), the comprehensive {@code files} map
     * (covering arbitrary sub-directories such as {@code templates/}), and the base directory when the repository can
     * resolve one.
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

        // Build complete skill with all files and the resolved base directory
        final Skill.Builder builder = Skill.builder().name(skill.getName()).metadata(skill.getMetadata())
                .content(skill.getContent()).rootFiles(rootFiles).scripts(scripts).references(references).assets(assets)
                .files(files);
        repository.resolveBaseDir(skillName).ifPresent(builder::baseDir);
        return Optional.of(builder.build());
    }

    @Override
    public List<Skill> getAllSkills() {
        final List<String> skillNames = repository.findAllNames();
        final List<Skill> skills = new ArrayList<>();

        for (String skillName : skillNames) {
            final Optional<Skill> skill = getSkill(skillName);
            skill.ifPresent(skills::add);
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
            loadComplete(skillName).ifPresent(skill -> reloaded.put(skillName, skill));
        }
        cache = reloaded;
    }
}
