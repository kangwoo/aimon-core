package at.aimon.core.skill.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillMetadata;

/**
 * Pattern-driven {@link SkillInvocationPolicy}.
 *
 * <h2>Decision order</h2>
 * <ol>
 * <li>If skill name matches any <strong>deny</strong> pattern → {@link SkillInvocationDecision#DENY}.
 * <li>Else if skill name matches any <strong>allow</strong> pattern → {@link SkillInvocationDecision#ALLOW}.
 * <li>Else if {@code safeByDefault} is enabled and the skill is "safe" (see below) →
 * {@link SkillInvocationDecision#ALLOW}.
 * <li>Else → {@code defaultDecision} (configurable, defaults to {@link SkillInvocationDecision#DENY}).
 * </ol>
 *
 * <h2>Safe-by-default rule</h2>
 *
 * A skill is considered safe when it has {@link ExecutionMode#INLINE INLINE} execution mode AND declares no per-skill
 * hooks ({@link SkillMetadata#getHooks()}{@code .isEmpty()}). The reasoning: an inline skill with no hooks is just an
 * instruction injection — it can only do what the agent could already do (call existing tools), and per-skill hooks are
 * the privileged extension surface that warrants explicit approval.
 *
 * <h2>Pattern syntax</h2>
 *
 * Patterns support glob-style {@code *} (matches any run of name characters: letters, digits, dots, underscores,
 * colons, hyphens) and {@code ?} (matches a single name character). All other characters are taken literally. Examples:
 * <ul>
 * <li>{@code "commit"} — exact match
 * <li>{@code "wiki-*"} — any skill whose name starts with {@code "wiki-"}
 * <li>{@code "*:prod"} — any namespaced skill whose local name is {@code "prod"}
 * <li>{@code "*"} — match everything
 * </ul>
 *
 * <p>
 * Built via {@link #builder()}. Immutable and thread-safe once constructed.
 */
public final class RuleBasedSkillInvocationPolicy implements SkillInvocationPolicy {

    private final List<Pattern> denyPatterns;
    private final List<Pattern> allowPatterns;
    private final boolean safeByDefault;
    private final SkillInvocationDecision defaultDecision;

    private RuleBasedSkillInvocationPolicy(Builder builder) {
        denyPatterns = List.copyOf(compileAll(builder.denyPatterns));
        allowPatterns = List.copyOf(compileAll(builder.allowPatterns));
        safeByDefault = builder.safeByDefault;
        defaultDecision = Objects.requireNonNull(builder.defaultDecision, "Default decision cannot be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public SkillInvocationDecision check(SkillInvocationRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");
        final String name = request.getSkill().getName();

        if (matchesAny(denyPatterns, name)) {
            return SkillInvocationDecision.DENY;
        }
        if (matchesAny(allowPatterns, name)) {
            return SkillInvocationDecision.ALLOW;
        }
        if (safeByDefault && isSafeSkill(request.getSkill())) {
            return SkillInvocationDecision.ALLOW;
        }
        return defaultDecision;
    }

    private static boolean isSafeSkill(Skill skill) {
        final SkillMetadata metadata = skill.getMetadata();
        return metadata.getExecutionMode() == ExecutionMode.INLINE && metadata.getHooks().isEmpty();
    }

    private static boolean matchesAny(List<Pattern> patterns, String name) {
        for (Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<Pattern> compileAll(List<String> globs) {
        if (globs == null || globs.isEmpty()) {
            return List.of();
        }
        final List<Pattern> compiled = new ArrayList<>(globs.size());
        for (String g : globs) {
            compiled.add(globToRegex(g));
        }
        return compiled;
    }

    private static Pattern globToRegex(String glob) {
        Objects.requireNonNull(glob, "Pattern cannot be null");
        if (glob.isBlank()) {
            throw new IllegalArgumentException("Pattern cannot be blank");
        }
        final StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            final char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append("[A-Za-z0-9._:\\-]*");
                case '?' -> regex.append("[A-Za-z0-9._:\\-]");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString());
    }

    /** Builder for {@link RuleBasedSkillInvocationPolicy}. */
    public static final class Builder {
        private List<String> denyPatterns = new ArrayList<>();
        private List<String> allowPatterns = new ArrayList<>();
        private boolean safeByDefault = true;
        private SkillInvocationDecision defaultDecision = SkillInvocationDecision.DENY;

        private Builder() {
        }

        /** Glob patterns whose match denies the invocation outright (highest precedence). */
        public Builder denyPatterns(List<String> patterns) {
            denyPatterns = patterns == null ? new ArrayList<>() : new ArrayList<>(patterns);
            return this;
        }

        public Builder addDenyPattern(String pattern) {
            denyPatterns.add(pattern);
            return this;
        }

        /** Glob patterns whose match allows the invocation (after deny rules). */
        public Builder allowPatterns(List<String> patterns) {
            allowPatterns = patterns == null ? new ArrayList<>() : new ArrayList<>(patterns);
            return this;
        }

        public Builder addAllowPattern(String pattern) {
            allowPatterns.add(pattern);
            return this;
        }

        /**
         * Enables or disables the safe-by-default rule. Default: {@code true}. When disabled the only path to
         * {@link SkillInvocationDecision#ALLOW} is an explicit allow pattern.
         */
        public Builder safeByDefault(boolean enabled) {
            safeByDefault = enabled;
            return this;
        }

        /**
         * Decision returned when no rule matches and the safe-by-default rule did not fire. Default:
         * {@link SkillInvocationDecision#DENY} (fail-closed). Set to {@link SkillInvocationDecision#ASK} to route
         * unmatched skills through the (future) approval channel.
         */
        public Builder defaultDecision(SkillInvocationDecision decision) {
            defaultDecision = decision;
            return this;
        }

        public RuleBasedSkillInvocationPolicy build() {
            return new RuleBasedSkillInvocationPolicy(this);
        }
    }
}
