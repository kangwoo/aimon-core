package at.aimon.core.tools;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.render.RenderContext;
import at.aimon.core.skill.render.SkillRenderContexts;

/**
 * Builds the {@link RenderContext} a skill body is rendered with, from the skill and the {@link ToolContext} of the run
 * invoking it.
 *
 * <p>
 * Shared by every path that renders a skill with a tool context in hand — the {@code Skill} tool (model invocation)
 * and the skill-backed slash command (user invocation) — so the two cannot drift. They used to: only the tool path
 * built a context, and a command-invoked body rendered {@code ${AIMON_SKILL_DIR}} as an empty string.
 *
 * <p>
 * Lives here rather than in {@code skill.render} for the same reason {@link InvokingSessionAccess} does: it interprets
 * {@link ToolContextKeys}, and the render package stays ignorant of the tool layer.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class SkillRenderContextAccess {

    private static final Logger log = LoggerFactory.getLogger(SkillRenderContextAccess.class);

    private SkillRenderContextAccess() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Starts a {@link RenderContext} builder for rendering the given skill in the given run.
     *
     * <p>
     * Populates the agent runtime identifier, the identity of the run doing the rendering, principal, and the skill
     * directory. Missing values are simply omitted; the
     * renderer is expected to handle absent fields gracefully. A builder is returned rather than a context so a caller
     * holding a more specific value (a command's own principal, say) can set it before building.
     *
     * <p>
     * The three ids address different lifetimes. The runtime id is <b>agent-scoped</b>: every session served by this
     * agent renders the same value, so a skill body must not treat {@code ${AIMON_AGENT_RUNTIME_ID}} as a per-run
     * uniqueness discriminator. The other two are the exclusive pair that names the run itself —
     * {@code ${AIMON_SESSION_ID}} when the run is a session's turn, {@code ${AIMON_EXECUTION_ID}} when it is not
     * (a skill invoked from inside a subagent fork or a scheduled routine). Both are copied straight across rather
     * than merged: the session key is empty in a fork precisely so a body cannot mistake a run identity for a
     * session, and collapsing them here would undo that.
     *
     * <p>
     * <b>The skill directory is always a staged path.</b> {@code ${AIMON_SKILL_DIR}} is set to what the run's
     * {@code ExecutionEnvironment.stage(...)} returns for the skill's {@link Skill#getStagedResource() resource}
     * (execution-environment design §4.4) — a copy the run's shell and file tools can both read — and never to a
     * repository path, which the shell may not be able to see. Without a resource (a hand-built skill) or without an
     * environment in the context, it stays unset and renders empty, with a WARN.
     *
     * @param skill
     *            The skill being rendered (must not be null)
     * @param context
     *            The tool context of the invoking run (must not be null)
     * @return A pre-populated builder (never null)
     * @throws NullPointerException
     *             if either argument is null
     * @throws at.aimon.core.environment.exception.StagingException
     *             if the skill cannot be staged; callers turn it into a tool or command error
     * @throws at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException
     *             if the run's environment is unavailable
     */
    public static RenderContext.Builder builderFor(Skill skill, ToolContext context) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final RenderContext.Builder builder = SkillRenderContexts.builderFor(skill);
        context.get(ToolContextKeys.AGENT_RUNTIME_ID).map(AgentRuntimeId::value).ifPresent(builder::agentRuntimeId);
        context.get(ToolContextKeys.SESSION_ID).map(SessionId::value).ifPresent(builder::sessionId);
        context.get(ToolContextKeys.EXECUTION_ID).map(ExecutionId::value).ifPresent(builder::executionId);
        context.get(ToolContextKeys.PRINCIPAL).ifPresent((Principal p) -> builder.principal(p));

        final Optional<StagedResource> resource = skill.getStagedResource();
        final Optional<ExecutionEnvironment> environment = ExecutionEnvironmentAccess.of(context);
        if (resource.isEmpty()) {
            log.warn("Skill '{}' carries no staged resource; ${{AIMON_SKILL_DIR}} renders empty", skill.getName());
        } else if (environment.isEmpty()) {
            log.warn("No execution environment to stage skill '{}' into; ${{AIMON_SKILL_DIR}} renders empty",
                    skill.getName());
        } else {
            builder.skillBaseDir(environment.get().stage(resource.get()));
        }
        return builder;
    }
}
