package at.aimon.core.agent.tool;

import java.util.List;

import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.ToolUse;

/**
 * The single entrance through which a tool call reaches the gates in front of a tool — the allow-list, the side-effect
 * ceiling, the PermissionRequest hooks, the approval gate, the PreTool hooks, the tool itself and the PostTool hooks.
 *
 * <p>
 * An execution loop builds one and binds it into the {@link ToolContext} it runs its tools with, under
 * {@code ToolContextKeys.TOOL_CALL_DISPATCHER}. Everything that is fixed for the loop — the registries, the allow-list,
 * the hooks, the interrupt coordinator, the invoker identity, the programmatic settings published to each call — is
 * fixed by the binding; what varies per call is an argument. Batching, streaming and parallelism stay in the loop: the
 * loop splits the batch and calls {@link #dispatch} once per call.
 *
 * <p>
 * A consumer that needs one and finds none bound refuses rather than falling back to calling the tool directly. A test
 * or an embedder that wants a call without the gates picks {@code ToolCallDispatchers.ungated(...)} explicitly, so the
 * choice is visible in the name.
 *
 * <p>
 * Implementations are thread-safe: a loop may dispatch several calls of one batch concurrently.
 */
public interface ToolCallDispatcher {

    /**
     * Runs one tool call through the gates. Never throws: a denial, an unknown tool and a failure all come back as an
     * error result.
     *
     * @param toolUse
     *            the call (must not be null)
     * @param origin
     *            the path the call arrived by, handed to the hooks (must not be null)
     * @param context
     *            the context the tool runs with (must not be null)
     * @return the result (never null)
     */
    ToolDispatchResult dispatch(ToolUse toolUse, ToolCallOrigin origin, ToolContext context);

    /**
     * Returns a dispatcher that can only do less than this one: every tool in {@code registry} must be a tool of this
     * dispatcher's registry, and the allow-list becomes the intersection of this dispatcher's and {@code allowedTools}
     * ({@code AllowedTools.intersect}). An empty allow-list on this dispatcher means unrestricted for the allow-list,
     * but never for the registry: a registry holding a tool this one does not is refused either way.
     *
     * <p>
     * Its programmatic settings — published to every call as {@code ToolContextKeys.CALLER_PROGRAMMATIC}, and so the
     * ceiling of any fork a call spawns — become {@code programmatic.narrowedBy(<this dispatcher's settings>)}
     * ({@link ProgrammaticToolSettings#narrowedBy(ProgrammaticToolSettings)}). An empty result is
     * {@link ProgrammaticToolSettings#none()}, not an error: unlike an empty allow-list, {@code none()} reads as
     * "nothing", never as "unrestricted". An implementation must never publish settings wider than its own.
     *
     * @param registry
     *            the narrower registry (must not be null)
     * @param allowedTools
     *            the allow-list to intersect with; empty means no further restriction (must not be null)
     * @param programmatic
     *            the programmatic settings of the loop the narrowed dispatcher serves, to intersect with this
     *            dispatcher's; pass {@link ProgrammaticToolSettings#none()} to open nothing to code (must not be null)
     * @return the narrowed dispatcher (never null)
     * @throws IllegalArgumentException
     *             if {@code registry} holds a tool this dispatcher's registry does not, or the two allow-lists have
     *             nothing in common
     */
    ToolCallDispatcher narrow(ToolRegistry registry, List<AllowedTool> allowedTools,
            ProgrammaticToolSettings programmatic);
}
