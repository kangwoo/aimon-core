package at.aimon.core.config.hook;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Maps Claude Code event names to AIMON event names, and back.
 *
 * <p>
 * The mapping is intentionally one-to-one for the events AIMON ships today; events that exist in Claude Code but are
 * not yet implemented in AIMON are listed in {@link #UNSUPPORTED} so the loader can warn rather than silently drop
 * them.
 *
 * <p>
 * Lookups are case-insensitive on the Claude Code side &mdash; a future hook config could spell {@code preToolUse}
 * lowercase and still resolve.
 */
public final class HookEventName {

    /** Claude Code event name → canonical AIMON event name. */
    private static final Map<String, String> CC_TO_AIMON = Map.ofEntries(Map.entry("pretooluse", "preTool"),
            Map.entry("posttooluse", "postTool"), Map.entry("stop", "onStop"), Map.entry("precompact", "preCompact"),
            Map.entry("sessionstart", "onSessionStart"), Map.entry("sessionend", "onSessionEnd"),
            Map.entry("subagentstop", "subagentStop"),
            // AIMON-native names accepted as-is (case-preserved on the value side). Every event in
            // HookEventType#values() must appear here, otherwise a hooks.json spelling it its AIMON name is
            // warn-and-skipped by the loader even though the applier could register it.
            Map.entry("pretool", "preTool"), Map.entry("posttool", "postTool"), Map.entry("onstart", "onStart"),
            Map.entry("onstop", "onStop"), Map.entry("postcompact", "postCompact"),
            Map.entry("onsessionstart", "onSessionStart"), Map.entry("onsessionend", "onSessionEnd"),
            Map.entry("subagentstart", "subagentStart"), Map.entry("permissionrequest", "permissionRequest"),
            Map.entry("permissiondenied", "permissionDenied"), Map.entry("onconfigreload", "onConfigReload"));

    /**
     * AIMON event name → canonical Claude Code event name; an absent key means the event has no Claude Code peer.
     *
     * <p>
     * {@code onStart}, {@code postCompact}, {@code subagentStart}, {@code permissionRequest},
     * {@code permissionDenied} and {@code onConfigReload} are AIMON extensions with no counterpart in the Claude Code
     * hook spec, so they are deliberately absent rather than mapped to an invented name.
     */
    private static final Map<String, String> AIMON_TO_CC = Map.of("preTool", "PreToolUse", "postTool", "PostToolUse",
            "onStop", "Stop", "preCompact", "PreCompact", "onSessionStart", "SessionStart", "onSessionEnd",
            "SessionEnd", "subagentStop", "SubagentStop");

    /**
     * Claude Code events AIMON does not yet implement. The loader logs a WARN and skips entries under these keys.
     */
    public static final Set<String> UNSUPPORTED = Set.of("notification", "userpromptsubmit", "stop_hook_active");

    private HookEventName() {
    }

    /**
     * Resolves the supplied Claude Code (or AIMON-native) event name to the canonical AIMON event name.
     *
     * @param raw
     *            the raw event name as it appeared in JSON (must not be null)
     * @return the AIMON name, or {@link Optional#empty()} if the event is unknown / unsupported
     */
    public static Optional<String> toAimon(String raw) {
        Objects.requireNonNull(raw, "raw cannot be null");
        return Optional.ofNullable(CC_TO_AIMON.get(raw.toLowerCase(Locale.ROOT)));
    }

    /**
     * Returns true when {@code raw} is on the {@link #UNSUPPORTED} list (case-insensitive).
     *
     * @param raw
     *            the raw event name (must not be null)
     * @return true when the loader should warn-and-skip this event
     */
    public static boolean isUnsupported(String raw) {
        Objects.requireNonNull(raw, "raw cannot be null");
        return UNSUPPORTED.contains(raw.toLowerCase(Locale.ROOT));
    }

    /**
     * Reverse mapping: AIMON event name → Claude Code event name.
     *
     * @param aimonName
     *            the canonical AIMON event name (must not be null)
     * @return the Claude Code peer or {@link Optional#empty()} when AIMON has no equivalent in the spec
     */
    public static Optional<String> toClaudeCode(String aimonName) {
        Objects.requireNonNull(aimonName, "aimonName cannot be null");
        return Optional.ofNullable(AIMON_TO_CC.get(aimonName));
    }
}
