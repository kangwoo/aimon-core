package at.aimon.core.skill.render;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.base.Principal;
import at.aimon.core.skill.Skill;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Builds the {@link RenderContext} a skill body is rendered with, from the skill and the {@link ToolContext} of the run
 * invoking it.
 *
 * <p>
 * Shared by every path that renders a skill — the {@code Skill} tool (model invocation) and the skill-backed slash
 * command (user invocation) — so the two cannot drift. They used to: only the tool path set a base directory, and a
 * command-invoked body rendered {@code ${AIMON_SKILL_DIR}} as an empty string.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class SkillRenderContexts {

    private SkillRenderContexts() {
    }

    /**
     * Starts a {@link RenderContext} builder for rendering the given skill in the given run.
     *
     * <p>
     * Populates the agent runtime identifier, the identity of the run doing the rendering, principal, and skill base
     * directory (see {@link #resolveSkillBaseDir(Skill)}). Missing values are simply omitted; the renderer is expected
     * to handle absent fields gracefully. A builder is returned rather than a context so a caller holding a more
     * specific value (a command's own principal, say) can set it before building.
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
     * @param skill
     *            The skill being rendered (must not be null)
     * @param context
     *            The tool context of the invoking run (must not be null)
     * @return A pre-populated builder (never null)
     */
    public static RenderContext.Builder builderFor(Skill skill, ToolContext context) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final RenderContext.Builder builder = RenderContext.builder();
        context.get(ToolContextKeys.AGENT_RUNTIME_ID).map(AgentRuntimeId::value).ifPresent(builder::agentRuntimeId);
        context.get(ToolContextKeys.SESSION_ID).map(SessionId::value).ifPresent(builder::sessionId);
        context.get(ToolContextKeys.EXECUTION_ID).map(ExecutionId::value).ifPresent(builder::executionId);
        context.get(ToolContextKeys.PRINCIPAL).ifPresent((Principal p) -> builder.principal(p));
        resolveSkillBaseDir(skill).ifPresent(builder::skillBaseDir);
        return builder;
    }

    /**
     * Resolves the directory {@code ${AIMON_SKILL_DIR}} expands to for the given skill.
     *
     * <p>
     * Prefers the authoritative {@link Skill#getBaseDir() base directory} carried by the skill. Skills assembled
     * without one (backwards compatibility) fall back to the parent of the first registered resource path — root
     * files, then scripts, references, assets. Skills with neither resolve to empty, in which case the renderer may
     * emit a warning when the placeholder is referenced.
     *
     * @param skill
     *            The skill to inspect (must not be null)
     * @return The base directory if known or derivable, otherwise empty
     */
    public static Optional<String> resolveSkillBaseDir(Skill skill) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        return skill.getBaseDir().or(() -> firstResourcePath(skill).map(SkillRenderContexts::parentPath));
    }

    private static Optional<String> firstResourcePath(Skill skill) {
        return Optional.<String>empty().or(() -> skill.getRootFiles().values().stream().findFirst())
                .or(() -> skill.getScripts().values().stream().findFirst())
                .or(() -> skill.getReferences().values().stream().findFirst())
                .or(() -> skill.getAssets().values().stream().findFirst());
    }

    private static String parentPath(String fullPath) {
        final int slash = fullPath.lastIndexOf('/');
        if (slash <= 0) {
            return "";
        }
        return fullPath.substring(0, slash);
    }
}
