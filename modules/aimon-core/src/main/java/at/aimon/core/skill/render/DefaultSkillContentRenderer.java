package at.aimon.core.skill.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.base.Principal;
import at.aimon.core.skill.Skill;

/**
 * Default {@link SkillContentRenderer} that performs argument substitution, environment variable substitution, and
 * tool-call token formatting on a skill body.
 *
 * <p>
 * This renderer is the single rendering entry point shared by both the model-invocation path ({@code SkillTool}) and
 * the user-invocation path ({@code SkillExecutor}, introduced in SK-08-C). Both paths produce identical output for the
 * same body and arguments.
 *
 * <p>
 * Substitution rules (all applied in a single pass so substituted text is never re-interpreted):
 *
 * <ul>
 * <li><b>Positional arguments</b>
 * <ul>
 * <li>{@code $ARGUMENTS} (when not followed by an identifier character) and {@code $0} are replaced by the entire raw
 * argument string verbatim.
 * <li>{@code $ARG_COUNT} (when not followed by an identifier character) is replaced by the number of positional
 * arguments obtained via {@link ShellArgumentTokenizer#tokenize(String)}.
 * <li>{@code $1}, {@code $2}, …, {@code $N} (multi-digit, with a non-digit lookahead so the entire run of digits is
 * captured) are replaced by the i-th positional argument; missing positions are replaced with the empty string.
 * </ul>
 * <li><b>Named arguments</b> When the skill declares
 * {@link at.aimon.core.skill.SkillMetadata#getArgumentNames() argument names}, occurrences of {@code $name}
 * (matched only when {@code name} is one of the declared identifiers) are replaced with the corresponding positional
 * token. Missing tokens resolve to the empty string. Names not in the declared list are left in place verbatim.
 * <li><b>Environment variables</b> {@code ${VAR}} are resolved as follows, in order:
 * <ol>
 * <li>Built-in {@code AIMON_*} variables resolved from {@link RenderContext} ({@code AIMON_SKILL_DIR},
 * {@code AIMON_AGENT_RUNTIME_ID}, {@code AIMON_SESSION_ID}, {@code AIMON_EXECUTION_ID} and {@code AIMON_USER}). The
 * three id variables address different lifetimes and none falls back to another — the runtime id is agent-scoped and
 * identical across sessions, the session id names one session, and the execution id names one run of something that
 * has no session at all. The latter two are an exclusive pair: whichever describes the current run is set and the
 * other is not. When the matching context field is absent, the placeholder is replaced by the empty string and a
 * {@code WARN} log entry is emitted.
 * <li>{@link RenderContext#getAdditionalVariables()} entries.
 * <li>Otherwise the placeholder is left in place verbatim so that downstream consumers may interpret it.
 * </ol>
 * <li><b>Tool-call tokens</b>
 * <ul>
 * <li>{@code !`command`} is rewritten as {@code Bash(command)} so the skill body declares its bash needs in a form the
 * permission layer can validate.
 * <li>{@code @file/path} (only at line start or following whitespace, to avoid false positives in e-mail addresses or
 * usernames) is rewritten as {@code Read(file/path)}.
 * </ul>
 * The token rewrites describe intent; actual execution happens in the ReAct loop after permission checks.
 * </ul>
 *
 * <p>
 * If none of the positional or recognized named placeholders appear in the body and {@code args} is non-empty, the raw
 * argument string is appended at the end as a separate paragraph in the form {@code \n\nARGUMENTS: <args>\n}.
 * Environment variable references and tool-call tokens do not suppress this trailer — they do not consume arguments.
 *
 * <p>
 * The renderer never reads {@link System#getenv(String)} — only context-supplied variables are resolved.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class DefaultSkillContentRenderer implements SkillContentRenderer {

    public static final String VAR_AIMON_SKILL_DIR = "AIMON_SKILL_DIR";

    /**
     * Identifier of the agent runtime rendering the skill: {@code agent:<name>} or
     * {@code agent:<name>:<discriminator>}.
     *
     * <p>
     * <b>Agent-scoped, not per-session.</b> The id is derived deterministically from the
     * {@code (Agent, discriminator)} pair, so every session of the same agent — and every cron re-fire — renders
     * the identical string. A skill body must not use it as a per-run uniqueness discriminator.
     */
    public static final String VAR_AIMON_AGENT_RUNTIME_ID = "AIMON_AGENT_RUNTIME_ID";

    /**
     * Identifier of the session the skill is being rendered for.
     *
     * <p>
     * <b>Per-session.</b> Two concurrent sessions of the same agent render different values, so unlike
     * {@link #VAR_AIMON_AGENT_RUNTIME_ID} this <i>is</i> usable as a uniqueness discriminator — a body wanting a
     * private
     * scratch directory should write {@code /tmp/work/${AIMON_SESSION_ID}}.
     *
     * <p>
     * <b>Empty when the run rendering the skill has no session of its own</b> — inside a subagent fork or a scheduled
     * routine, where {@link #VAR_AIMON_EXECUTION_ID} identifies the run instead. A body that wants a scratch path in
     * both settings must reference both variables; exactly one of them expands to a value.
     *
     * <p>
     * This literal spent one release as a deprecated alias of {@link #VAR_AIMON_AGENT_RUNTIME_ID}. The alias was
     * withdrawn, not re-pointed: a body that ignored the {@code WARN} and kept using it now receives a different value
     * —
     * the per-session one its name always promised.
     */
    public static final String VAR_AIMON_SESSION_ID = "AIMON_SESSION_ID";

    /**
     * Identity of the run rendering the skill when that run has no session of its own — a subagent fork, a skill
     * fork, a scheduled routine. Empty when the run <i>is</i> a session's turn, where
     * {@link #VAR_AIMON_SESSION_ID} names it instead.
     *
     * <p>
     * <b>Per-run.</b> Two concurrent forks of the same subagent render different values, which makes this the
     * uniqueness discriminator a body should reach for in that setting. Node-local and never persisted, so it must
     * not be used to name anything durable, and it identifies no user, so it must not be used as an authorization
     * input.
     *
     * <p>
     * A fork used to render a minted session id into {@link #VAR_AIMON_SESSION_ID} — a value indistinguishable from
     * the user's own. That is why this is a separate variable rather than a fallback: a body asking for a session id
     * gets one or gets nothing, never a run identity wearing a session's name.
     */
    public static final String VAR_AIMON_EXECUTION_ID = "AIMON_EXECUTION_ID";

    public static final String VAR_AIMON_USER = "AIMON_USER";

    private static final String ARG_COUNT_TOKEN = "$ARG_COUNT";

    private static final Logger log = LoggerFactory.getLogger(DefaultSkillContentRenderer.class);

    private static final Pattern PLACEHOLDER_PATTERN = Pattern
            .compile("\\$ARGUMENTS(?![A-Za-z0-9_])|\\$ARG_COUNT(?![A-Za-z0-9_])|\\$(\\d+)(?!\\d)"
                    + "|\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)"
                    + "|!`([^`]+)`|(?<=^|\\s)@([\\w/.\\-]+)");

    private final ShellArgumentTokenizer tokenizer;

    /** Creates a renderer with a default {@link ShellArgumentTokenizer}. */
    public DefaultSkillContentRenderer() {
        this(new ShellArgumentTokenizer());
    }

    /**
     * Creates a renderer with the given tokenizer.
     *
     * @param tokenizer
     *            the tokenizer used to split positional arguments (must not be null)
     */
    public DefaultSkillContentRenderer(ShellArgumentTokenizer tokenizer) {
        this.tokenizer = Objects.requireNonNull(tokenizer, "Tokenizer cannot be null");
    }

    @Override
    public String render(Skill skill, String args, RenderContext context) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(args, "Args cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final String body = skill.getContent().getInstructions();
        final List<String> positional = tokenizer.tokenize(args);
        final Map<String, Integer> namedIndex = buildNamedIndex(skill);

        final Matcher matcher = PLACEHOLDER_PATTERN.matcher(body);
        final StringBuilder out = new StringBuilder();
        boolean hasPositional = false;
        while (matcher.find()) {
            final String matched = matcher.group();
            final String digit = matcher.group(1);
            final String varName = matcher.group(2);
            final String namedArg = matcher.group(3);
            final String bashCmd = matcher.group(4);
            final String filePath = matcher.group(5);
            final String replacement;
            if (digit != null) {
                hasPositional = true;
                final int index = parsePositionalIndex(digit);
                replacement = (index == 0) ? args : (index <= positional.size() ? positional.get(index - 1) : "");
            } else if (varName != null) {
                final String resolved = resolveVariable(skill, varName, context);
                replacement = (resolved == null) ? matched : resolved;
            } else if (namedArg != null) {
                final Integer index = namedIndex.get(namedArg);
                if (index == null) {
                    replacement = matched;
                } else {
                    hasPositional = true;
                    replacement = (index < positional.size()) ? positional.get(index) : "";
                }
            } else if (bashCmd != null) {
                replacement = "Bash(" + bashCmd + ")";
            } else if (filePath != null) {
                replacement = "Read(" + filePath + ")";
            } else if (matched.startsWith(ARG_COUNT_TOKEN)) {
                hasPositional = true;
                replacement = Integer.toString(positional.size());
            } else {
                hasPositional = true;
                replacement = args;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);

        if (!hasPositional && !args.isEmpty()) {
            out.append("\n\nARGUMENTS: ").append(args).append('\n');
        }

        return out.toString();
    }

    private static int parsePositionalIndex(String digits) {
        // Pattern guarantees at least one digit; cap at Integer.MAX_VALUE for very long runs to avoid overflow.
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException overflow) {
            return Integer.MAX_VALUE;
        }
    }

    private static Map<String, Integer> buildNamedIndex(Skill skill) {
        final List<String> names = skill.getMetadata().getArgumentNames();
        if (names.isEmpty()) {
            return Map.of();
        }
        final Map<String, Integer> map = new HashMap<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            map.put(names.get(i), i);
        }
        return map;
    }

    private static String resolveVariable(Skill skill, String varName, RenderContext context) {
        switch (varName) {
            case VAR_AIMON_SKILL_DIR :
                return resolveSkillDir(skill, context);
            case VAR_AIMON_AGENT_RUNTIME_ID :
                return resolveAgentRuntimeId(skill, varName, context);
            case VAR_AIMON_SESSION_ID :
                return resolveSessionId(skill, varName, context);
            case VAR_AIMON_EXECUTION_ID :
                return resolveExecutionId(skill, varName, context);
            case VAR_AIMON_USER :
                return context.getPrincipal().map(Principal::getDisplayName).orElseGet(() -> {
                    log.warn("Skill '{}' references ${{}} but no principal is available in the render context",
                            skill.getName(), VAR_AIMON_USER);
                    return "";
                });
            default :
                final String additional = context.getAdditionalVariables().get(varName);
                return additional;
        }
    }

    private static String resolveAgentRuntimeId(Skill skill, String varName, RenderContext context) {
        return context.getAgentRuntimeId().orElseGet(() -> {
            log.warn("Skill '{}' references ${{}} but no agent runtime id is available in the render context",
                    skill.getName(), varName);
            return "";
        });
    }

    private static String resolveSessionId(Skill skill, String varName, RenderContext context) {
        return context.getSessionId().orElseGet(() -> {
            // Not necessarily a misconfiguration: a fork or a scheduled routine has no session, by design. Name the
            // alternative so a body reaching for a per-run discriminator knows which variable to use instead.
            if (context.getExecutionId().isPresent()) {
                log.warn(
                        "Skill '{}' references ${{}} but this run has no session of its own; "
                                + "reference ${{}} for a per-run identity",
                        skill.getName(), varName, VAR_AIMON_EXECUTION_ID);
            } else {
                log.warn("Skill '{}' references ${{}} but no session id is available in the render context",
                        skill.getName(), varName);
            }
            return "";
        });
    }

    private static String resolveExecutionId(Skill skill, String varName, RenderContext context) {
        return context.getExecutionId().orElseGet(() -> {
            if (context.getSessionId().isPresent()) {
                log.warn("Skill '{}' references ${{}} but this run is a session's turn; reference ${{}} instead",
                        skill.getName(), varName, VAR_AIMON_SESSION_ID);
            } else {
                log.warn("Skill '{}' references ${{}} but no execution id is available in the render context",
                        skill.getName(), varName);
            }
            return "";
        });
    }

    private static String resolveSkillDir(Skill skill, RenderContext context) {
        return context.getSkillBaseDir().orElseGet(() -> {
            log.warn("Skill '{}' references ${{}} but no skill base directory is available in the render context",
                    skill.getName(), VAR_AIMON_SKILL_DIR);
            return "";
        });
    }
}
