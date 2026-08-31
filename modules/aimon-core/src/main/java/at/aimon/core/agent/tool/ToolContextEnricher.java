package at.aimon.core.agent.tool;

/**
 * Strategy for adding entries to the {@link ToolContext} that a whole execution
 * shares.
 *
 * <p>
 * Enrichers are registered on the agent runtime and invoked <strong>once per
 * execution</strong> — once per main-agent turn, and once per subagent
 * execution — while the {@code ToolContext} is assembled, after the executor
 * has populated the framework keys (environment, artifact collector, knowledge
 * store, etc.) and <em>before</em> the ReAct iteration loop begins. They let
 * modules outside the executor — typically integration modules wired by the
 * application — push typed keys into the context without leaking dependencies
 * into the executor itself.
 *
 * <p>
 * Because the resulting context is built once and then shared, a value an
 * enricher writes is seen unchanged by every tool call in every iteration of
 * that execution. An enricher that mints a per-tool-call value (a fresh span, a
 * per-call id) will not get one — it gets one value reused for the whole
 * execution.
 *
 * <p>
 * Enrichers themselves are never invoked concurrently: they run on the
 * execution's own thread before any tool is dispatched. Any <em>value</em> they
 * write, however, must be safe for concurrent reads and mutation by tools
 * running under {@code ParallelToolDispatcher} — which is why the executor
 * injects {@code ConcurrentHashMap.newKeySet()} for
 * {@code ReadTool.READ_FILES_KEY}. Enrichers should write only via the supplied
 * {@link ToolContext.Builder}; they must not retain a reference to the builder
 * after returning.
 */
public interface ToolContextEnricher {

    /**
     * Pushes additional entries into {@code builder} for the upcoming
     * execution.
     *
     * @param builder
     *            the context builder being assembled (never null)
     * @param info
     *            metadata about the execution (never null)
     */
    void enrich(ToolContext.Builder builder, ToolContextEnrichmentInfo info);
}
