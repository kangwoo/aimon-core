package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.search.SearchableTool;
import at.aimon.core.agent.tool.search.ToolSearchCatalog;
import at.aimon.core.tools.knowledge.KnowledgeSearchTool;

/**
 * Provides knowledge search tools to the Orca agent system.
 *
 * <p>
 * Registers {@link KnowledgeSearchTool} as an eager-loaded tool. If the registry is a {@link ToolSearchCatalog}, the
 * tool is registered as eager (always visible to the LLM) because knowledge search is a core capability for agents with
 * a configured knowledge directory. The {@link at.aimon.core.knowledge.KnowledgeStore} instance is provided to the tool
 * at runtime via {@link at.aimon.core.agent.tool.ToolContext}.
 *
 * @see OrcaToolProvider
 * @see KnowledgeSearchTool
 */
public class OrcaKnowledgeToolProvider implements OrcaToolProvider {

    /**
     * Creates an OrcaKnowledgeToolProvider.
     */
    public OrcaKnowledgeToolProvider() {
        // KnowledgeStore is injected at runtime via ToolContext, not held here
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        final KnowledgeSearchTool tool = new KnowledgeSearchTool();

        if (registry instanceof ToolSearchCatalog) {
            ((ToolSearchCatalog) registry).register(SearchableTool.eager(tool));
        } else {
            registry.register(tool);
        }
    }
}
