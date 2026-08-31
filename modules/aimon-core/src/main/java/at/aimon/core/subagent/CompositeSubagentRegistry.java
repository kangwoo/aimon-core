package at.aimon.core.subagent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composite SubagentRegistry that merges multiple registries into a single interface.
 *
 * <p>
 * Combines subagents from multiple sources (e.g., built-in and user-defined). Registries later in the list have higher
 * priority: when the same subagent name exists in multiple registries, the definition from the later registry wins.
 *
 * <p>
 * Recommended usage: {@code List.of(builtinRegistry, userRegistry)} — the user registry is last, so user-defined
 * subagents override built-in ones.
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
 *     SubagentRegistry builtinRegistry = new DefaultSubagentRegistry(new ClasspathSubagentRepository("builtin/agents"),
 *             new MarkdownSubagentParser(new SubagentContentParser()));
 *     SubagentRegistry userRegistry = new DefaultSubagentRegistry(fileSystem, agentsDirectory);
 *
 *     SubagentRegistry registry = new CompositeSubagentRegistry(List.of(builtinRegistry, userRegistry));
 *
 *     // userRegistry's "explore" overrides builtinRegistry's "explore"
 *     Optional<Subagent> explore = registry.getSubagent("explore");
 * }
 * </pre>
 */
public class CompositeSubagentRegistry implements SubagentRegistry {

    private static final Logger log = LoggerFactory.getLogger(CompositeSubagentRegistry.class);

    private final List<SubagentRegistry> registries;

    /**
     * Creates a new CompositeSubagentRegistry.
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
    public CompositeSubagentRegistry(List<SubagentRegistry> registries) {
        Objects.requireNonNull(registries, "Registries cannot be null");
        if (registries.isEmpty()) {
            throw new IllegalArgumentException("Registries cannot be empty");
        }
        this.registries = List.copyOf(registries);
    }

    @Override
    public Optional<Subagent> getSubagent(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");

        for (int i = registries.size() - 1; i >= 0; i--) {
            final Optional<Subagent> result = registries.get(i).getSubagent(subagentName);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Subagent> getAllSubagents() {
        final Map<String, Subagent> merged = new LinkedHashMap<>();

        for (SubagentRegistry registry : registries) {
            for (Subagent subagent : registry.getAllSubagents()) {
                if (merged.containsKey(subagent.getName())) {
                    log.debug("Subagent '{}' overridden by higher-priority registry", subagent.getName());
                }
                merged.put(subagent.getName(), subagent);
            }
        }

        return List.copyOf(merged.values());
    }

    /**
     * Reloads a subagent by propagating the reload to all underlying registries unconditionally.
     *
     * <p>
     * Unlike {@link at.aimon.core.skill.CompositeSkillRegistry#reloadSkill(String)}, this method does not throw an
     * exception when the subagent is not found in any registry. This is because the {@link SubagentRegistry} contract
     * specifies that {@code reloadSubagent} silently removes the subagent if it no longer exists, rather than throwing
     * an exception.
     *
     * @param subagentName
     *            The subagent name to reload (must not be null)
     * @throws NullPointerException
     *             if subagentName is null
     */
    @Override
    public void reloadSubagent(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");

        log.debug("Propagating reload of subagent '{}' to {} registries", subagentName, registries.size());
        for (SubagentRegistry registry : registries) {
            registry.reloadSubagent(subagentName);
        }
    }

    @Override
    public void reloadAll() {
        for (SubagentRegistry registry : registries) {
            registry.reloadAll();
        }
    }
}
