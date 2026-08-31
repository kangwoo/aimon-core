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
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;

/**
 * Tool catalog that manages both eager and deferred tools.
 *
 * <p>
 * This class lives at the {@code ExecutionContext} level (long-lived) and is shared across sessions. It implements
 * {@link ToolRegistry} so it can be injected transparently into existing code.
 *
 * <p>
 * Key behaviours:
 *
 * <ul>
 * <li>{@link #register(Tool)} registers a tool as {@link at.aimon.core.agent.tool.ToolLoadingMode#EAGER EAGER}
 * (backward compatible).
 * <li>{@link #register(SearchableTool)} registers with the specified loading mode.
 * <li>{@link #findAll()} returns <b>eager tools only</b> (ToolRegistry contract for LLM exposure).
 * <li>{@link #findByName(String)} searches <b>all</b> tools regardless of loading mode.
 * <li>Search methods ({@link #search}, {@link #select}, {@link #searchByRequired}) are pure — they do not modify
 * activation state.
 * <li>{@link #createRegistry()} produces a per-session {@link ToolSearchRegistry}.
 * </ul>
 *
 * <p>
 * Thread-safe via {@link ConcurrentHashMap}.
 */
public final class ToolSearchCatalog implements ToolRegistry {

    private static final String FUNCTIONS_PREFIX = "functions.";

    private final ConcurrentHashMap<String, SearchableTool> allTools = new ConcurrentHashMap<>();
    private final ToolSearchStrategy searchStrategy;
    private final RequiredKeywordToolSearchStrategy requiredStrategy;
    private final boolean stripFunctionsPrefix;

    /**
     * Creates a new catalog with the given search strategy.
     *
     * @param searchStrategy
     *            the keyword search strategy (must not be null)
     */
    public ToolSearchCatalog(ToolSearchStrategy searchStrategy) {
        this(searchStrategy, true);
    }

    /**
     * Creates a new catalog with the given search strategy and prefix-stripping configuration.
     *
     * @param searchStrategy
     *            the keyword search strategy (must not be null)
     * @param stripFunctionsPrefix
     *            whether to strip the "functions." prefix from tool name lookups
     */
    public ToolSearchCatalog(ToolSearchStrategy searchStrategy, boolean stripFunctionsPrefix) {
        this.searchStrategy = Objects.requireNonNull(searchStrategy, "Search strategy cannot be null");
        if (searchStrategy instanceof KeywordToolSearchStrategy keywordStrategy) {
            this.requiredStrategy = new RequiredKeywordToolSearchStrategy(keywordStrategy);
        } else {
            this.requiredStrategy = new RequiredKeywordToolSearchStrategy(new KeywordToolSearchStrategy());
        }
        this.stripFunctionsPrefix = stripFunctionsPrefix;
    }

    // ── ToolRegistry methods (backward compatible) ──────────────────────────────

    /**
     * Registers a tool as {@link at.aimon.core.agent.tool.ToolLoadingMode#EAGER EAGER}.
     */
    @Override
    public void register(Tool tool) {
        register(SearchableTool.eager(tool));
    }

    /**
     * Registers a searchable tool with the specified loading mode.
     *
     * @param searchableTool
     *            the tool to register (must not be null)
     */
    public void register(SearchableTool searchableTool) {
        Objects.requireNonNull(searchableTool, "SearchableTool cannot be null");
        allTools.put(searchableTool.getName(), searchableTool);
    }

    @Override
    public void unregister(String toolName) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        allTools.remove(toolName);
    }

    /**
     * Finds a tool by name across <b>all</b> tools (eager and deferred).
     */
    @Override
    public Optional<Tool> findByName(String toolName) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        final String normalized = normalizeName(toolName);
        final SearchableTool st = allTools.get(normalized);
        return st == null ? Optional.empty() : Optional.of(st.getTool());
    }

    /**
     * Returns <b>eager tools only</b>. This is the ToolRegistry contract: tools exposed to the LLM at session
     * start.
     */
    @Override
    public List<Tool> findAll() {
        return allTools.values().stream().filter(SearchableTool::isEager).map(SearchableTool::getTool).toList();
    }

    @Override
    public List<Tool> findAllExcept(String... excludedToolNames) {
        Objects.requireNonNull(excludedToolNames, "Excluded tool names cannot be null");
        final Set<String> excluded = Set.of(excludedToolNames);
        return allTools.values().stream().filter(SearchableTool::isEager).filter(st -> !excluded.contains(st.getName()))
                .map(SearchableTool::getTool).toList();
    }

    @Override
    public Set<String> findCategories() {
        final Set<String> categories = new LinkedHashSet<>();
        for (SearchableTool st : allTools.values()) {
            if (st.isEager()) {
                categories.add(st.getTool().getDefinition().getCategory());
            }
        }
        return Collections.unmodifiableSet(categories);
    }

    @Override
    public List<Tool> findAllByCategory(String category) {
        Objects.requireNonNull(category, "Category cannot be null");
        return allTools.values().stream().filter(SearchableTool::isEager).map(SearchableTool::getTool)
                .filter(tool -> category.equals(tool.getDefinition().getCategory())).toList();
    }

    @Override
    public Map<String, List<Tool>> findAllGroupedByCategory() {
        final Map<String, List<Tool>> grouped = new LinkedHashMap<>();
        for (SearchableTool st : allTools.values()) {
            if (!st.isEager()) {
                continue;
            }
            final Tool tool = st.getTool();
            grouped.computeIfAbsent(tool.getDefinition().getCategory(), k -> new ArrayList<>()).add(tool);
        }
        final Map<String, List<Tool>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Tool>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public int size() {
        return allTools.size();
    }

    @Override
    public boolean isEmpty() {
        return allTools.isEmpty();
    }

    @Override
    public void clear() {
        allTools.clear();
    }

    // ── Catalog-specific methods ────────────────────────────────────────────────

    /**
     * Returns all eager tools.
     *
     * @return immutable list of eager tools
     */
    public List<Tool> getEagerTools() {
        return findAll();
    }

    /**
     * Returns all deferred tools (search candidates).
     *
     * @return immutable list of deferred searchable tools
     */
    public List<SearchableTool> getDeferredTools() {
        return allTools.values().stream().filter(SearchableTool::isDeferred).toList();
    }

    /**
     * Searches deferred tools by keyword query. Pure search — does not change activation state.
     *
     * @param query
     *            the search query
     * @param maxResults
     *            maximum results
     * @return matching deferred tools
     */
    public List<SearchableTool> search(String query, int maxResults) {
        return searchStrategy.search(query, getDeferredTools(), maxResults);
    }

    /**
     * Selects deferred tools by exact name match. Pure lookup — does not change activation state.
     *
     * @param toolNames
     *            the tool names to select
     * @return matching deferred tools (non-existent names are silently ignored)
     */
    public List<SearchableTool> select(List<String> toolNames) {
        Objects.requireNonNull(toolNames, "Tool names cannot be null");
        return toolNames.stream().map(this::normalizeName).map(allTools::get).filter(Objects::nonNull)
                .filter(SearchableTool::isDeferred).toList();
    }

    /**
     * Searches deferred tools with a required keyword filter on names, then ranks by ranking keywords. Pure search —
     * does not change activation state.
     *
     * @param requiredKeyword
     *            keyword that must appear in tool names
     * @param rankingKeywords
     *            additional keywords for ranking
     * @param maxResults
     *            maximum results
     * @return matching deferred tools
     */
    public List<SearchableTool> searchByRequired(String requiredKeyword, List<String> rankingKeywords, int maxResults) {
        final String rankingQuery = String.join(" ", rankingKeywords);
        return requiredStrategy.search(rankingQuery, getDeferredTools(), requiredKeyword, maxResults);
    }

    /**
     * Creates a new per-session {@link ToolSearchRegistry} backed by this catalog.
     *
     * @return a new registry with empty activation state
     */
    public ToolSearchRegistry createRegistry() {
        return new ToolSearchRegistry(this, new ToolActivationState());
    }

    private String normalizeName(String toolName) {
        if (stripFunctionsPrefix && toolName.startsWith(FUNCTIONS_PREFIX)) {
            return toolName.substring(FUNCTIONS_PREFIX.length());
        }
        return toolName;
    }

}
