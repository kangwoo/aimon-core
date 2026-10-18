package at.aimon.core.subagent.execution;

import at.aimon.core.environment.ExecutionEnvironment;

/**
 * Renders a fork's goal in the fork's own execution environment (aimon-core #273, EE-81).
 *
 * <p>
 * A fork-mode skill's body is rendered by the spawner, before the fork exists, so its {@code ${AIMON_SKILL_DIR}} is a
 * path staged where the <em>spawner</em> runs. A fork whose provider places it elsewhere cannot read that path. The
 * spawner therefore hands this over with the goal, and {@code DefaultSubagentExecutor} calls it once the fork's
 * environment is resolved &mdash; and only when that environment is not the spawner's own, so under the local
 * provider, which answers a fork with its parent, nothing is rendered or staged twice.
 *
 * <p>
 * Everything else the body renders with (session id, principal) stays the spawner's: only the staged directory
 * depends on where the fork runs.
 *
 * <p>
 * Implementations must be thread-safe; one is called at most once per fork.
 */
@FunctionalInterface
public interface ForkGoalRenderer {

    /**
     * Renders the goal for a fork running in {@code forkEnvironment}.
     *
     * @param forkEnvironment
     *            the fork's resolved environment (never null)
     * @return the rendered goal (never null)
     * @throws at.aimon.core.environment.exception.StagingException
     *             if the skill cannot be staged there; the fork then fails before it starts
     * @throws at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException
     *             if the environment is unavailable
     */
    String render(ExecutionEnvironment forkEnvironment);
}
