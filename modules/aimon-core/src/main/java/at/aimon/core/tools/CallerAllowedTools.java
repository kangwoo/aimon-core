package at.aimon.core.tools;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.permission.AllowedTool;

/**
 * Static accessor for the allow-list bounding the run that is making the current tool call.
 *
 * <p>
 * Exists for the same reason {@link InvokingSessionAccess} does: the value is read at a handful of spawn sites, and
 * the one thing every site must agree on is what <b>absence</b> means. {@link ToolContextKeys#CALLER_ALLOWED_TOOLS}
 * is absent on any path that does not run through {@code SingleToolInvoker} &mdash; an embedder driving a tool
 * directly, a test &mdash; and an absent key means exactly what an empty list means: <b>unrestricted</b>. Reading the
 * key by hand invites {@code orElse(null)}, and a null allow-list is the one value the permission package cannot
 * read.
 *
 * <p>
 * This is a propagation accessor, not an authorization one. What the current call may do is decided by
 * {@code ToolExecutionManager} from the list it was handed directly; this method exists so a spawner can bound the
 * run it is about to start.
 */
public final class CallerAllowedTools {

    private CallerAllowedTools() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns the allow-list the calling run is bound by, to be imposed on a run spawned from it.
     *
     * @param context
     *            the tool context (must not be null)
     * @return the caller's allow-list, or an empty list when there is none &mdash; never null
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static List<AllowedTool> of(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return context.get(ToolContextKeys.CALLER_ALLOWED_TOOLS).map(List::copyOf).orElseGet(List::of);
    }
}
