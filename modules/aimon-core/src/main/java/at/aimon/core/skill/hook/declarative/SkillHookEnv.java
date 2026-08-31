package at.aimon.core.skill.hook.declarative;

/**
 * Environment variable name constants exposed to declarative shell-action hooks (AIMON extension, SK-13).
 *
 * <p>
 * These names are part of the public hook contract — renaming any of them is a breaking change for skill authors who
 * have shell scripts referencing them. Add new variables freely; do not rename existing ones.
 *
 * <p>
 * Every variable here also reaches the hook command as a field of the JSON document on standard input, named by
 * stripping the {@code AIMON_} prefix and lower-casing the rest ({@code AIMON_TOOL_NAME} &rarr; {@code tool_name}).
 * The two channels cannot drift because the JSON is derived from this map — see {@code ShellHookPayload}.
 *
 * <p>
 * <b>Size discipline.</b> The environment block handed to a child process has a hard OS-level size limit (128 KiB per
 * {@code execve} argument on Linux, ~32 KiB total on Windows) and the whole block is readable by anything that can see
 * the process table or {@code /proc/<pid>/environ}. Values that originate from the model or from free-form user text
 * are therefore never exported verbatim: hooks pass them through {@link #truncateValue(String)} first. Because the
 * stdin payload is rendered <em>from</em> the same map, the cap applies to both channels — a truncated value is
 * truncated everywhere, which is why the marker is visible rather than silent.
 *
 * <p>
 * Per-event coverage:
 * <ul>
 * <li>{@link #AIMON_HOOK_EVENT}, {@link #AIMON_SKILL_NAME}, {@link #AIMON_INVOKER_NAME},
 * {@link #AIMON_INVOKER_TYPE} — set on every event.
 * <li>{@link #AIMON_TOOL_NAME} — set on {@code preTool}, {@code postTool}, {@code permissionRequest} and
 * {@code permissionDenied}.
 * <li>{@link #AIMON_ITERATION} — set on {@code preTool} and {@code postTool}.
 * <li>{@link #AIMON_TOOL_RESULT_STATUS} — set on {@code postTool} only.
 * <li>{@link #AIMON_USER_MESSAGE_LENGTH} — set on {@code onStart} only.
 * <li>{@link #AIMON_SUCCESS} — set on {@code onStop}, {@code subagentStop} and {@code onConfigReload}.
 * <li>{@link #AIMON_ITERATION_COUNT} — set on {@code onStop} only.
 * <li>{@link #AIMON_SESSION_ID}, {@link #AIMON_EXECUTION_ID} — set on {@code onSessionStart},
 * {@code onSessionEnd} and {@code preCompact}. Exactly one of the two carries a value; see their javadoc.
 * <li>{@link #AIMON_AGENT_RUNTIME_ID} — set on {@code onSessionStart} and {@code onSessionEnd}.
 * <li>{@link #AIMON_SESSION_CLEAN}, {@link #AIMON_TERMINATION_REASON} — set on {@code onSessionEnd} only.
 * <li>{@link #AIMON_SUBAGENT_NAME}, {@link #AIMON_TASK_ID} — set on {@code subagentStart} and {@code subagentStop}.
 * <li>{@link #AIMON_SUBAGENT_GOAL}, {@link #AIMON_SUBAGENT_DESCRIPTION} — set on {@code subagentStart} only.
 * <li>{@link #AIMON_COMPACTION_TRIGGER} — set on {@code preCompact} and {@code postCompact}.
 * <li>{@link #AIMON_MESSAGE_COUNT}, {@link #AIMON_ESTIMATED_TOKENS} — set on {@code preCompact} only.
 * <li>{@link #AIMON_MESSAGES_SUMMARIZED}, {@link #AIMON_PRE_COMPACT_TOKENS}, {@link #AIMON_POST_COMPACT_TOKENS} — set
 * on {@code postCompact} only.
 * <li>{@link #AIMON_PRINCIPAL_ID}, {@link #AIMON_PRINCIPAL_TYPE} — set on {@code permissionRequest} and
 * {@code permissionDenied}.
 * <li>{@link #AIMON_DENY_REASON} — set on {@code permissionDenied} only.
 * <li>{@link #AIMON_RELOAD_COUNTER}, {@link #AIMON_CONFIG_SOURCE}, {@link #AIMON_FAILURE_REASON} — set on
 * {@code onConfigReload} only.
 * </ul>
 */
public final class SkillHookEnv {

    /**
     * Maximum number of characters of an unbounded (model- or user-authored) value that is exported to the hook
     * environment. Chosen so that even a handful of such variables stays far below the smallest platform env-block
     * limit while still carrying enough text for a script to match on.
     */
    public static final int MAX_ENV_VALUE_LENGTH = 2000;

    /**
     * Event name, exactly as it is spelled in {@code hooks.json} and skill frontmatter: {@code "preTool"},
     * {@code "postTool"}, {@code "onStart"}, {@code "onStop"}, {@code "onSessionStart"}, {@code "onSessionEnd"},
     * {@code "subagentStart"}, {@code "subagentStop"}, {@code "preCompact"}, {@code "postCompact"},
     * {@code "permissionRequest"}, {@code "permissionDenied"}, or {@code "onConfigReload"}.
     */
    public static final String AIMON_HOOK_EVENT = "AIMON_HOOK_EVENT";

    /** Skill name (verbatim from {@code SkillMetadata.name}). */
    public static final String AIMON_SKILL_NAME = "AIMON_SKILL_NAME";

    /** Invoker name (typically the agent name). */
    public static final String AIMON_INVOKER_NAME = "AIMON_INVOKER_NAME";

    /** Invoker type ({@code MAIN_AGENT} / {@code SUBAGENT}). */
    public static final String AIMON_INVOKER_TYPE = "AIMON_INVOKER_TYPE";

    /** Tool name being invoked — preTool / postTool / permissionRequest / permissionDenied. */
    public static final String AIMON_TOOL_NAME = "AIMON_TOOL_NAME";

    /** ReAct loop iteration (1-based) — preTool/postTool only. */
    public static final String AIMON_ITERATION = "AIMON_ITERATION";

    /** Tool execution result status: {@code "success"} or {@code "error"} — postTool only. */
    public static final String AIMON_TOOL_RESULT_STATUS = "AIMON_TOOL_RESULT_STATUS";

    /** Length (in characters) of the original user message — onStart only. */
    public static final String AIMON_USER_MESSAGE_LENGTH = "AIMON_USER_MESSAGE_LENGTH";

    /** Success flag: {@code "true"} or {@code "false"} — onStop / subagentStop / onConfigReload. */
    public static final String AIMON_SUCCESS = "AIMON_SUCCESS";

    /** Final iteration count — onStop only. */
    public static final String AIMON_ITERATION_COUNT = "AIMON_ITERATION_COUNT";

    /**
     * Session id — onSessionStart / onSessionEnd / preCompact; <b>empty when the run has no session</b>, in which case
     * {@link #AIMON_EXECUTION_ID} carries the correlation id instead.
     *
     * <p>
     * The empty case is real: a rewake replay fires these same events without any session behind it. It used to
     * export a fabricated {@code "rewake:<envelopeId>"} value here, which a handler could not tell apart from a
     * genuine session id — so a script that keys per-session state on this variable would have accumulated one bogus
     * entry per replay. Handlers must now treat empty as "no session" rather than assuming a value.
     *
     * <p>
     * Spelled {@code AIMON_CONVERSATION_ID} until the session-first restructure. No alias is exported for the old
     * spelling, so a handler reading it finds it unset rather than silently receiving a value that means something
     * else. That is now the rule for every name here: {@code AIMON_AGENT_EXECUTION_CONTEXT_ID} was the one legacy
     * alias still exported, and it has been removed.
     */
    public static final String AIMON_SESSION_ID = "AIMON_SESSION_ID";

    /**
     * Correlation id of a run that has no session of its own — onSessionStart / onSessionEnd / preCompact; empty for
     * an ordinary session-backed firing, where {@link #AIMON_SESSION_ID} identifies the run instead.
     *
     * <p>
     * Node-local and never persisted: it identifies <em>this</em> replay and nothing else. Do not use it to look up
     * anything durable, and do not treat it as an authorization input.
     */
    public static final String AIMON_EXECUTION_ID = "AIMON_EXECUTION_ID";

    /** Agent runtime id — onSessionStart / onSessionEnd. */
    public static final String AIMON_AGENT_RUNTIME_ID = "AIMON_AGENT_RUNTIME_ID";

    /** Whether the session ended cleanly: {@code "true"} or {@code "false"} — onSessionEnd only. */
    public static final String AIMON_SESSION_CLEAN = "AIMON_SESSION_CLEAN";

    /** Termination reason text — onSessionEnd only; empty when none was set. */
    public static final String AIMON_TERMINATION_REASON = "AIMON_TERMINATION_REASON";

    /** Subagent name — subagentStart / subagentStop. */
    public static final String AIMON_SUBAGENT_NAME = "AIMON_SUBAGENT_NAME";

    /** Task id assigned to the subagent dispatch — subagentStart / subagentStop. */
    public static final String AIMON_TASK_ID = "AIMON_TASK_ID";

    /**
     * Goal handed to the subagent — subagentStart only. Model-authored and therefore capped at
     * {@value #MAX_ENV_VALUE_LENGTH} characters by {@link #truncateValue(String)}.
     */
    public static final String AIMON_SUBAGENT_GOAL = "AIMON_SUBAGENT_GOAL";

    /**
     * Short description of the dispatched task — subagentStart only. Model-authored and therefore capped at
     * {@value #MAX_ENV_VALUE_LENGTH} characters by {@link #truncateValue(String)}.
     */
    public static final String AIMON_SUBAGENT_DESCRIPTION = "AIMON_SUBAGENT_DESCRIPTION";

    /** Compaction trigger: {@code "AUTO"} or {@code "MANUAL"} — preCompact / postCompact. */
    public static final String AIMON_COMPACTION_TRIGGER = "AIMON_COMPACTION_TRIGGER";

    /** Number of messages in the conversation about to be compacted — preCompact only. */
    public static final String AIMON_MESSAGE_COUNT = "AIMON_MESSAGE_COUNT";

    /** Estimated token count of the conversation about to be compacted — preCompact only. */
    public static final String AIMON_ESTIMATED_TOKENS = "AIMON_ESTIMATED_TOKENS";

    /** Number of messages folded into the summary — postCompact only. */
    public static final String AIMON_MESSAGES_SUMMARIZED = "AIMON_MESSAGES_SUMMARIZED";

    /** Token count before compaction — postCompact only. */
    public static final String AIMON_PRE_COMPACT_TOKENS = "AIMON_PRE_COMPACT_TOKENS";

    /** Token count after compaction — postCompact only. */
    public static final String AIMON_POST_COMPACT_TOKENS = "AIMON_POST_COMPACT_TOKENS";

    /** Id of the principal requesting the tool — permissionRequest / permissionDenied; empty when unauthenticated. */
    public static final String AIMON_PRINCIPAL_ID = "AIMON_PRINCIPAL_ID";

    /**
     * Type of the principal requesting the tool ({@code USER} / {@code GROUP} / {@code SYSTEM} / {@code SERVICE}) —
     * permissionRequest / permissionDenied; empty when unauthenticated.
     */
    public static final String AIMON_PRINCIPAL_TYPE = "AIMON_PRINCIPAL_TYPE";

    /** Reason the permission layer rejected the call — permissionDenied only. */
    public static final String AIMON_DENY_REASON = "AIMON_DENY_REASON";

    /** Monotonic reload sequence number — onConfigReload only. */
    public static final String AIMON_RELOAD_COUNTER = "AIMON_RELOAD_COUNTER";

    /** Identifier of the configuration source that was reloaded — onConfigReload only. */
    public static final String AIMON_CONFIG_SOURCE = "AIMON_CONFIG_SOURCE";

    /** Why the reload failed — onConfigReload only; empty on a successful reload. */
    public static final String AIMON_FAILURE_REASON = "AIMON_FAILURE_REASON";

    /**
     * Halves of the marker {@link #truncateValue(String)} appends around the original length. Spelled exactly like
     * {@code ShellHookOutcome#denyReason()} so a script only has to recognise one truncation marker.
     */
    private static final String TRUNCATION_MARKER_HEAD = "... [truncated, ";

    private static final String TRUNCATION_MARKER_TAIL = " chars total]";

    /**
     * Caps a value that a hook is about to export, so that unbounded model- or user-authored text cannot blow past the
     * environment block limit (and cannot dump a whole prompt into every process listing on the box).
     *
     * <p>
     * Truncation is deliberately <em>visible</em>: the returned string keeps at most
     * {@value #MAX_ENV_VALUE_LENGTH} characters of the original and appends a {@code "... [truncated, N chars total]"}
     * marker, so a script can tell it is looking at a prefix instead of silently matching against a cut-off value. The
     * same marker format is used for shell-hook deny reasons.
     *
     * <p>
     * Callers should apply this to every value they cannot bound themselves; short, framework-generated values (ids,
     * counters, enum names) do not need it.
     *
     * @param value
     *            the raw value, or {@code null}
     * @return the value unchanged when it fits, a marked prefix when it does not, or {@code ""} when {@code value} is
     *         null — an environment map cannot carry a null value
     */
    public static String truncateValue(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_ENV_VALUE_LENGTH) {
            return value;
        }
        int end = MAX_ENV_VALUE_LENGTH;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            // Never cut between the halves of a surrogate pair: a lone surrogate cannot be serialised into the
            // JSON stdin payload, which is rendered from this same value.
            end--;
        }
        return value.substring(0, end) + TRUNCATION_MARKER_HEAD + value.length() + TRUNCATION_MARKER_TAIL;
    }

    private SkillHookEnv() {
    }
}
