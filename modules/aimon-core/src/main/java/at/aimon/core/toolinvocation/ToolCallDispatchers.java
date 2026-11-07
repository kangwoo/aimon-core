package at.aimon.core.toolinvocation;

import static at.aimon.core.agent.tool.execution.ToolExecutionResultConverter.toToolUseResult;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolExecutionManager;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.tools.EnvironmentNotices;
import at.aimon.core.tools.ExecutionEnvironmentAccess;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Factories for {@link ToolCallDispatcher}s that are not built by an execution loop.
 *
 * <p>
 * The gated dispatcher an agent loop binds is {@link DefaultToolCallDispatcher}. This class holds the one deliberate
 * alternative: {@link #ungated}, for a test or an embedder that has no hooks and no approval gate to run and wants to
 * say so. There is no implicit fallback anywhere — a consumer that finds no dispatcher bound refuses — so a call
 * without the gates happens only where someone picked this method by name.
 */
public final class ToolCallDispatchers {

    private ToolCallDispatchers() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns a dispatcher that runs each call straight through {@code executionManager} against {@code registry}: the
     * allow-list and the manager's side-effect ceiling apply, but no PermissionRequest, PreTool or PostTool hook fires
     * and no approval is asked. The environment's notices are still drained and handed on.
     *
     * <p>
     * It publishes {@link ProgrammaticToolSettings#none()} as {@link ToolContextKeys#CALLER_PROGRAMMATIC}: an embedder
     * with no gates gets no code-running forks unless it says so through
     * {@link #ungated(ToolExecutionManager, ToolRegistry, ProgrammaticToolSettings)}.
     *
     * @param executionManager
     *            the manager that runs the tools (must not be null)
     * @param registry
     *            the registry the tools are executed against (must not be null)
     * @return an unrestricted ungated dispatcher; {@link ToolCallDispatcher#narrow} narrows it like any other
     */
    public static ToolCallDispatcher ungated(ToolExecutionManager executionManager, ToolRegistry registry) {
        return ungated(executionManager, registry, ProgrammaticToolSettings.none());
    }

    /**
     * Returns an ungated dispatcher like {@link #ungated(ToolExecutionManager, ToolRegistry)} that publishes
     * {@code programmatic} to every call as {@link ToolContextKeys#CALLER_PROGRAMMATIC}, the ceiling of any fork a
     * call spawns.
     *
     * @param executionManager
     *            the manager that runs the tools (must not be null)
     * @param registry
     *            the registry the tools are executed against (must not be null)
     * @param programmatic
     *            the programmatic settings of the loop this dispatcher serves (must not be null)
     * @return an unrestricted ungated dispatcher; {@link ToolCallDispatcher#narrow} narrows it like any other
     */
    public static ToolCallDispatcher ungated(ToolExecutionManager executionManager, ToolRegistry registry,
            ProgrammaticToolSettings programmatic) {
        return new Ungated(Objects.requireNonNull(executionManager, "executionManager cannot be null"),
                Objects.requireNonNull(registry, "registry cannot be null"), List.of(),
                Objects.requireNonNull(programmatic, "programmatic cannot be null"));
    }

    /** The dispatcher {@link #ungated} returns. */
    private static final class Ungated implements ToolCallDispatcher {

        private static final Logger log = LoggerFactory.getLogger(Ungated.class);

        private final ToolExecutionManager executionManager;
        private final ToolRegistry registry;
        private final List<AllowedTool> allowedTools;
        private final ProgrammaticToolSettings programmatic;

        Ungated(ToolExecutionManager executionManager, ToolRegistry registry, List<AllowedTool> allowedTools,
                ProgrammaticToolSettings programmatic) {
            this.executionManager = executionManager;
            this.registry = registry;
            this.allowedTools = List.copyOf(allowedTools);
            this.programmatic = programmatic;
        }

        @Override
        public ToolDispatchResult dispatch(ToolUse toolUse, ToolCallOrigin origin, ToolContext context) {
            Objects.requireNonNull(toolUse, "toolUse cannot be null");
            Objects.requireNonNull(origin, "origin cannot be null");
            Objects.requireNonNull(context, "context cannot be null");
            ToolUseResult result;
            try {
                // The same keys the gated pipeline publishes, so a tool sees one shape of context either way.
                final ToolContext enriched = ToolContext.builder().putAll(context.getContext())
                        .put(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY, toolUse.getId())
                        .put(ToolContextKeys.CALLER_ALLOWED_TOOLS, allowedTools)
                        .put(ToolContextKeys.CALLER_PROGRAMMATIC, programmatic).build();
                result = toToolUseResult(executionManager.execute(toolUse, enriched, registry, allowedTools));
            } catch (RuntimeException e) {
                log.warn("Ungated tool call failed: tool={}, toolUseId={}: {}", toolUse.getName(), toolUse.getId(),
                        e.getMessage());
                result = ToolUseResult.error(toolUse.getId(), "Tool execution error: " + e.getMessage());
            }
            final List<String> notices = EnvironmentNotices.drain(ExecutionEnvironmentAccess.of(context).orElse(null),
                    toolUse.getName(), toolUse.getId());
            return ToolDispatchResults.of(result, notices, List.of());
        }

        @Override
        public ToolCallDispatcher narrow(ToolRegistry narrowed, List<AllowedTool> allowed,
                ProgrammaticToolSettings settings) {
            Objects.requireNonNull(narrowed, "registry cannot be null");
            Objects.requireNonNull(allowed, "allowedTools cannot be null");
            Objects.requireNonNull(settings, "programmatic cannot be null");
            ToolCallNarrowing.requireSubset(registry, narrowed);
            return new Ungated(executionManager, narrowed, ToolCallNarrowing.intersect(allowedTools, allowed),
                    settings.narrowedBy(programmatic));
        }
    }
}
