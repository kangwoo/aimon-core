package at.aimon.core.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.skill.exception.SkillNotFoundException;

/**
 * Composite SkillRegistry that merges multiple registries into a single interface.
 *
 * <p>
 * Combines skills from multiple sources (e.g., built-in and user-defined). Registries later in the list have higher
 * priority: when the same skill name exists in multiple registries, the definition from the later registry wins.
 *
 * <p>
 * Recommended usage: {@code List.of(builtinRegistry, userRegistry)} — the user registry is last, so user-defined skills
 * override built-in ones.
 *
 * <p>
 * Thread-safe if all underlying registries are thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SkillRegistry builtinRegistry = new DefaultSkillRegistry(new ClasspathSkillRepository("builtin/skills"),
 *             new MarkdownSkillParser());
 *     SkillRegistry userRegistry = new DefaultSkillRegistry(fileSystem, skillsDirectory);
 *
 *     SkillRegistry registry = new CompositeSkillRegistry(List.of(builtinRegistry, userRegistry));
 *
 *     // userRegistry's "commit" overrides builtinRegistry's "commit"
 *     Optional<Skill> commit = registry.getSkill("commit");
 * }
 * </pre>
 */
public class CompositeSkillRegistry implements SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(CompositeSkillRegistry.class);

    private final List<SkillRegistry> registries;

    /**
     * Creates a new CompositeSkillRegistry.
     *
     * <p>
     * Registries later in the list have higher priority.
     *
     * @param registries
     *            The registries to compose (must not be null, must not be empty)
     * @throws NullPointerException
     *             if registries is null
     * @throws IllegalArgumentException
     *             if registries is empty
     */
    public CompositeSkillRegistry(List<SkillRegistry> registries) {
        Objects.requireNonNull(registries, "Registries cannot be null");
        if (registries.isEmpty()) {
            throw new IllegalArgumentException("Registries cannot be empty");
        }
        this.registries = List.copyOf(registries);
    }

    @Override
    public Optional<Skill> getSkill(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        for (int i = registries.size() - 1; i >= 0; i--) {
            final Optional<Skill> result = registries.get(i).getSkill(skillName);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Skill> getAllSkills() {
        final Map<String, Skill> merged = new LinkedHashMap<>();

        for (SkillRegistry registry : registries) {
            for (Skill skill : registry.getAllSkills()) {
                if (merged.containsKey(skill.getName())) {
                    log.debug("Skill '{}' overridden by higher-priority registry", skill.getName());
                }
                merged.put(skill.getName(), skill);
            }
        }

        return List.copyOf(merged.values());
    }

    /**
     * Reloads a skill by attempting reload in each underlying registry.
     *
     * <p>
     * Unlike {@link at.aimon.core.subagent.CompositeSubagentRegistry#reloadSubagent(String)}, this method catches
     * {@link SkillNotFoundException} from each registry and throws only if the skill was not found in any registry.
     * This is because the {@link SkillRegistry} contract specifies that {@code reloadSkill} throws
     * {@link SkillNotFoundException} when the skill does not exist, rather than silently ignoring it.
     *
     * @param skillName
     *            The skill name to reload (must not be null)
     * @throws NullPointerException
     *             if skillName is null
     * @throws SkillNotFoundException
     *             if the skill was not found in any registry
     */
    @Override
    public void reloadSkill(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        boolean reloaded = false;
        for (SkillRegistry registry : registries) {
            try {
                registry.reloadSkill(skillName);
                reloaded = true;
            } catch (SkillNotFoundException e) {
                // This registry does not have the skill — continue to next
            }
        }

        if (!reloaded) {
            throw new SkillNotFoundException(skillName);
        }
    }

    @Override
    public void reloadAll() {
        for (SkillRegistry registry : registries) {
            registry.reloadAll();
        }
    }
}
