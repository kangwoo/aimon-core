package at.aimon.core.tools;

import java.util.Objects;

import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ToolContext;

/**
 * Static accessor for the programmatic tool calling settings of the run that is making the current tool call.
 *
 * <p>
 * The counterpart of {@link CallerAllowedTools}, with the opposite reading of absence.
 * {@link ToolContextKeys#CALLER_PROGRAMMATIC} is absent on any path that does not run through a dispatcher &mdash; an
 * embedder driving a tool directly, a test &mdash; and an absent key means {@link ProgrammaticToolSettings#none()}: a
 * fork spawned from there is offered no {@code ExecuteCode}, whatever its own definition opts into. Fail-closed,
 * because opening code to a fork is a widening, and only a caller that has it may hand it on.
 *
 * <p>
 * This is a propagation accessor: a spawner reads it to bound the run it is about to start.
 */
public final class CallerProgrammatic {

    private CallerProgrammatic() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns the programmatic settings the calling run is bound by, to be imposed on a run spawned from it.
     *
     * @param context
     *            the tool context (must not be null)
     * @return the caller's settings, or {@link ProgrammaticToolSettings#none()} when there are none &mdash; never null
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static ProgrammaticToolSettings of(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return context.get(ToolContextKeys.CALLER_PROGRAMMATIC).orElse(ProgrammaticToolSettings.none());
    }
}
