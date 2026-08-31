package at.aimon.core.skill.policy;

import java.util.Objects;

/**
 * {@link SkillInvocationPolicy} that returns {@link SkillInvocationDecision#ALLOW} unconditionally.
 *
 * <p>
 * Default wiring for {@code SkillTool} constructors that pre-date SK-11. Preserves backward compatibility while
 * production callers migrate to {@link RuleBasedSkillInvocationPolicy} or a custom implementation.
 *
 * <p>
 * Singleton — use {@link #INSTANCE}.
 */
public final class AlwaysAllowSkillInvocationPolicy implements SkillInvocationPolicy {

    public static final AlwaysAllowSkillInvocationPolicy INSTANCE = new AlwaysAllowSkillInvocationPolicy();

    private AlwaysAllowSkillInvocationPolicy() {
    }

    @Override
    public SkillInvocationDecision check(SkillInvocationRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");
        return SkillInvocationDecision.ALLOW;
    }
}
