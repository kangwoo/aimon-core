package at.aimon.core.tools.workflow;

import java.util.Objects;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.tools.CallerAllowedTools;
import at.aimon.core.tools.CallerProgrammatic;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.workflow.BackgroundSubmission;

/**
 * Builds the {@link BackgroundSubmission} a tool hands {@code WorkflowRunner#runInBackground} from the tool call's
 * context: the execution's quota guard and the ceiling of the loop that made the call (register TC-25).
 *
 * <p>
 * The ceiling is read as every spawn site reads it — {@link CallerAllowedTools#of} and {@link CallerProgrammatic#of},
 * published per call by the dispatcher that ran it — so inside a slash skill, a fork or a turn an inline skill has
 * narrowed, a background run is bounded by that loop, not by the agent alone. It lives here rather than beside
 * {@link BackgroundSubmission} because those accessors are in {@code at.aimon.core.tools}, which the workflow package
 * may not depend on. Shared by {@code Workflow} and {@code WorkflowJs}.
 *
 * <p>
 * Stateless; all methods are static.
 */
public final class BackgroundSubmissions {

    private BackgroundSubmissions() {
    }

    /**
     * Returns the submission for a background run started by a tool call made with {@code context}.
     *
     * @param context
     *            the tool call's context (must not be null)
     * @return the submission (never null)
     */
    public static BackgroundSubmission of(ToolContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        return BackgroundSubmission.builder().quotaGuard(context.get(ToolContextKeys.QUOTA_GUARD).orElse(null))
                .callerAllowedTools(CallerAllowedTools.of(context)).callerProgrammatic(CallerProgrammatic.of(context))
                .build();
    }
}
