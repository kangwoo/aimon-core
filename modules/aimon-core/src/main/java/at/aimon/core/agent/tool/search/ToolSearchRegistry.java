package at.aimon.core.agent.tool.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;

/**
 * Per-session view that combines a shared {@link ToolSearchCatalog} with a session-scoped
 * {@link ToolActivationState}.
 *
 * <p>
 * Implements {@link ToolRegistry} so it can be used transparently in existing code. The key difference from
 * {@link ToolSearchCatalog} is that {@link #findAll()} returns eager tools <b>plus</b> any deferred tools that have
 * been activated in this session.
 *
 * <p>
 * This is a <b>read-only view</b>: {@link #register(Tool)} and {@link #unregister(String)} throw
 * {@link UnsupportedOperationException} because modifying the shared catalog from a session-scoped view would
 * cause unintended side effects across sessions.
 */
public final class ToolSearchRegistry implements ToolRegistry {

    private final ToolSearchCatalog catalog;
    private final ToolActivationState activationState;

    /**
     * Creates a new per-session registry.
     *
     * @param catalog
     *            the shared tool catalog (must not be null)
     * @param activationState
     *            the session-scoped activation state (must not be null)
     */
    ToolSearchRegistry(ToolSearchCatalog catalog, ToolActivationState activationState) {
        this.catalog = Objects.requireNonNull(catalog, "Catalog cannot be null");
        this.activationState = Objects.requireNonNull(activationState, "Activation state cannot be null");
    }

    // ── ToolRegistry methods ────────────────────────────────────────────────────

    /**
     * Not supported. Use {@link ToolSearchCatalog#register(Tool)} instead.
     *
     * @throws UnsupportedOperationException
     *             always
     */
    @Override
    public void register(Tool tool) {
        throw new UnsupportedOperationException(
                "Cannot register tools through a session-scoped registry. Use ToolSearchCatalog instead.");
    }

    /**
     * Not supported. Use {@link ToolSearchCatalog#unregister(String)} instead.
     *
     * @throws UnsupportedOperationException
     *             always
     */
    @Override
    public void unregister(String toolName) {
        throw new UnsupportedOperationException(
                "Cannot unregister tools through a session-scoped registry. Use ToolSearchCatalog instead.");
    }

    /**
     * Finds a tool by name across <b>all</b> tools in the catalog, regardless of loading mode or activation state.
     *
     * <p>
     * This is needed for tool execution: once the LLM requests a tool by name, we must be able to find it.
     */
    @Override
    public Optional<Tool> findByName(String toolName) {
        return catalog.findByName(toolName);
    }

    /**
     * Returns eager tools <b>plus</b> deferred tools that have been activated in this session.
     */
    @Override
    public List<Tool> findAll() {
        final List<Tool> result = new ArrayList<>(catalog.getEagerTools());
        final Set<String> activated = activationState.getActivatedToolNames();
        if (!activated.isEmpty()) {
            for (SearchableTool st : catalog.getDeferredTools()) {
                if (activated.contains(st.getName())) {
                    result.add(st.getTool());
                }
            }
        }
        return List.copyOf(result);
    }

    @Override
    public List<Tool> findAllExcept(String... excludedToolNames) {
        Objects.requireNonNull(excludedToolNames, "Excluded tool names cannot be null");
        final Set<String> excluded = Set.of(excludedToolNames);
        return findAll().stream().filter(tool -> !excluded.contains(tool.getDefinition().getName())).toList();
    }

    @Override
    public Set<String> findCategories() {
        final Set<String> categories = new LinkedHashSet<>();
        for (Tool tool : findAll()) {
            categories.add(tool.getDefinition().getCategory());
        }
        return Collections.unmodifiableSet(categories);
    }

    @Override
    public List<Tool> findAllByCategory(String category) {
        Objects.requireNonNull(category, "Category cannot be null");
        return findAll().stream().filter(tool -> category.equals(tool.getDefinition().getCategory())).toList();
    }

    @Override
    public Map<String, List<Tool>> findAllGroupedByCategory() {
        final Map<String, List<Tool>> grouped = new LinkedHashMap<>();
        for (Tool tool : findAll()) {
            grouped.computeIfAbsent(tool.getDefinition().getCategory(), k -> new ArrayList<>()).add(tool);
        }
        final Map<String, List<Tool>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Tool>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the number of tools currently visible in this session (eager + activated deferred).
     */
    @Override
    public int size() {
        return findAll().size();
    }

    /**
     * Returns whether no tools are visible in this session.
     */
    @Override
    public boolean isEmpty() {
        return findAll().isEmpty();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException(
                "Cannot clear tools through a session-scoped registry. Use ToolSearchCatalog instead.");
    }

    // ── Search & Activation ─────────────────────────────────────────────────────

    /**
     * Parses the query, searches the catalog, and activates matching tools in this session.
     *
     * @param query
     *            the search query (must not be null or empty)
     * @param maxResults
     *            the maximum number of results
     * @return the list of matched (and now activated) tools
     */
    public List<SearchableTool> searchAndActivate(String query, int maxResults) {
        Objects.requireNonNull(query, "Query cannot be null");

        final ToolSearchQueryParser.ParsedQuery parsed = ToolSearchQueryParser.parse(query);
        final List<SearchableTool> results = switch (parsed.getMode()) {
            case SELECT -> catalog.select(parsed.getToolNames());
            case REQUIRED ->
                catalog.searchByRequired(parsed.getRequiredKeyword(), parsed.getRankingKeywords(), maxResults);
            case KEYWORD -> catalog.search(parsed.getKeywords(), maxResults);
        };

        // Activate all found tools in this session
        final List<String> names = results.stream().map(SearchableTool::getName).toList();
        activationState.activateAll(names);

        return results;
    }

    /**
     * Returns the session-scoped activation state.
     *
     * @return the activation state (never null)
     */
    public ToolActivationState getActivationState() {
        return activationState;
    }

}
