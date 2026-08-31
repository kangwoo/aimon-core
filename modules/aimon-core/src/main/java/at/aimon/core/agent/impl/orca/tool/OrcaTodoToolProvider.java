package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.tools.todo.InMemoryTodoRepository;
import at.aimon.core.tools.todo.TodoWriteTool;

/**
 * Provides todo management tools to the Orca agent system.
 *
 * <p>
 * This provider registers the {@link TodoWriteTool} which enables agents to manage task lists and track progress during
 * execution.
 *
 * @see OrcaToolProvider
 * @see TodoWriteTool
 */
public class OrcaTodoToolProvider implements OrcaToolProvider {

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        registry.register(new TodoWriteTool(new InMemoryTodoRepository()));
    }
}
