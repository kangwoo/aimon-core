package at.aimon.core.skill.render;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.skill.Skill;

/**
 * The skill-derived half of the {@link RenderContext} a skill body is rendered with.
 *
 * <p>
 * Knows only the {@link Skill}: which directory {@code ${AIMON_SKILL_DIR}} expands to. The run-derived half — the
 * agent runtime id, the session or execution id, the principal — comes from whoever invokes the skill, and is layered
 * on top by {@code at.aimon.core.tools.SkillRenderContextAccess} for the paths that carry a tool context. Keeping it
 * out of here keeps this package free of the tool layer.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class SkillRenderContexts {

    private SkillRenderContexts() {
    }

    /**
     * Starts a {@link RenderContext} builder for rendering the given skill, with its base directory set when one is
     * known (see {@link #resolveSkillBaseDir(Skill)}).
     *
     * @param skill
     *            The skill being rendered (must not be null)
     * @return A builder carrying the skill's base directory (never null)
     */
    public static RenderContext.Builder builderFor(Skill skill) {
        final RenderContext.Builder builder = RenderContext.builder();
        resolveSkillBaseDir(skill).ifPresent(builder::skillBaseDir);
        return builder;
    }

    /**
     * Resolves the directory {@code ${AIMON_SKILL_DIR}} expands to for the given skill.
     *
     * <p>
     * Prefers the authoritative {@link Skill#getBaseDir() base directory} carried by the skill. Skills assembled
     * without one — by hand, or by a repository that does not override {@code SkillRepository#resolveBaseDir} — fall
     * back to deriving it from a registered resource: root files, then scripts, references, assets. Skills with neither
     * resolve to empty, in which case the renderer may emit a warning when the placeholder is referenced.
     *
     * <p>
     * The derivation strips the resource's key, which is relative to its category directory, and the category
     * directory itself: {@code scripts/lib/y.sh} registered at {@code /skills/s/scripts/lib/y.sh} gives
     * {@code /skills/s}. Taking the plain parent instead would give {@code /skills/s/scripts/lib}, so
     * {@code ${AIMON_SKILL_DIR}/scripts/x.sh} would point into a directory that does not exist — and since the
     * resource maps are unordered, which subdirectory depended on the load. A path that does not end with its key is
     * outside the conventional layout; its plain parent is the best that can be said.
     *
     * @param skill
     *            The skill to inspect (must not be null)
     * @return The base directory if known or derivable, otherwise empty
     */
    public static Optional<String> resolveSkillBaseDir(Skill skill) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        return skill.getBaseDir().or(() -> derive(skill.getRootFiles(), ""))
                .or(() -> derive(skill.getScripts(), "scripts/")).or(() -> derive(skill.getReferences(), "references/"))
                .or(() -> derive(skill.getAssets(), "assets/"));
    }

    private static Optional<String> derive(Map<String, String> resources, String categoryPrefix) {
        return resources.entrySet().stream().findFirst()
                .map(e -> skillDirOf(e.getValue(), '/' + categoryPrefix + e.getKey()));
    }

    private static String skillDirOf(String fullPath, String relativeSuffix) {
        if (fullPath.endsWith(relativeSuffix)) {
            return fullPath.substring(0, fullPath.length() - relativeSuffix.length());
        }
        final int slash = fullPath.lastIndexOf('/');
        if (slash <= 0) {
            return "";
        }
        return fullPath.substring(0, slash);
    }
}
