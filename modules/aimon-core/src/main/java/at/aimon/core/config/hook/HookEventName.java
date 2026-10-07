package at.aimon.core.config.hook;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import at.aimon.core.hook.HookEventType;
import at.aimon.core.skill.hook.SkillHookSet;

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

    /**
     * How far an unknown event name may be from a known one and still be read as a misspelling of it.
     *
     * <p>
     * Two edits covers a dropped, doubled, wrong or transposed letter ({@code preTol}, {@code pretoool},
     * {@code onstrat}) without reaching from one real event name to another event's vocabulary: the names AIMON
     * deliberately does not know yet &mdash; other Claude Code events such as {@code PostToolUseFailure} or
     * {@code TeammateIdle} &mdash; are all further than this from every guard event.
     */
    public static final int NEAR_MISS_DISTANCE = 2;

    /** AIMON names of the events whose hooks can block: a missing hook there is a missing guard. */
    private static final Set<String> GUARD_EVENTS = SkillHookSet.guardEvents().stream().map(HookEventType::name)
            .collect(Collectors.toUnmodifiableSet());

    /** AIMON names of the events whose hooks report what already happened inside an execution. */
    private static final Set<String> REPORT_EVENTS = SkillHookSet.reportEvents().stream().map(HookEventType::name)
            .collect(Collectors.toUnmodifiableSet());

    private HookEventName() {
    }

    /**
     * Returns whether hooks on the given event report something that already happened inside an execution:
     * {@code postTool}, {@code onStop}, {@code subagentStart}, {@code subagentStop}, {@code permissionDenied},
     * {@code postCompact}. Only there can a handler declare {@code ignoreInterrupt}.
     *
     * @param aimonName
     *            the canonical AIMON event name (must not be null)
     * @return true for a report event
     */
    public static boolean isReport(String aimonName) {
        Objects.requireNonNull(aimonName, "aimonName cannot be null");
        return REPORT_EVENTS.contains(aimonName);
    }

    /**
     * Returns whether hooks on the given event can block (or deny): {@code preTool}, {@code onStart},
     * {@code preCompact}, {@code permissionRequest}.
     *
     * @param aimonName
     *            the canonical AIMON event name (must not be null)
     * @return true for a guard event
     */
    public static boolean isGuard(String aimonName) {
        Objects.requireNonNull(aimonName, "aimonName cannot be null");
        return GUARD_EVENTS.contains(aimonName);
    }

    /**
     * Finds the known event name an unknown one most likely misspells: the closest name by edit distance
     * (case-insensitive), provided it is within {@link #NEAR_MISS_DISTANCE}.
     *
     * <p>
     * This is what tells a typo from "an event AIMON does not know yet". Unknown names are accepted on purpose so a
     * config written for a newer AIMON, or imported from Claude Code, still loads; but {@code preTol} is not a future
     * event, and treating it as one registers nothing where the operator wrote a guard.
     *
     * <p>
     * When several known names are equally close and one of them is a guard event, the guard event is the answer:
     * the caller's question is whether a guard may have been lost.
     *
     * @param raw
     *            the event name as it appeared in the config (must not be null)
     * @return the nearest known name, or empty when nothing is close (or {@code raw} is itself a known name)
     */
    public static Optional<Nearest> nearest(String raw) {
        Objects.requireNonNull(raw, "raw cannot be null");
        final String typed = raw.toLowerCase(Locale.ROOT);
        if (CC_TO_AIMON.containsKey(typed)) {
            return Optional.empty();
        }
        String bestKey = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, String> known : CC_TO_AIMON.entrySet()) {
            final int distance = editDistance(typed, known.getKey());
            final boolean closer = distance < bestDistance;
            final boolean tieWonByGuard = distance == bestDistance && isGuard(known.getValue())
                    && !isGuard(CC_TO_AIMON.get(bestKey));
            // Map.ofEntries iterates in no fixed order; break the remaining ties by name so the answer is stable.
            final boolean tieWonByName = distance == bestDistance && bestKey != null
                    && isGuard(known.getValue()) == isGuard(CC_TO_AIMON.get(bestKey))
                    && known.getKey().compareTo(bestKey) < 0;
            if (closer || tieWonByGuard || tieWonByName) {
                bestKey = known.getKey();
                bestDistance = distance;
            }
        }
        if (bestKey == null || bestDistance > NEAR_MISS_DISTANCE) {
            return Optional.empty();
        }
        final String aimonName = CC_TO_AIMON.get(bestKey);
        return Optional.of(new Nearest(spelledLike(raw, bestKey, aimonName), aimonName, bestDistance));
    }

    /**
     * Spells the suggestion the way the config spells its neighbours: a name typed Claude Code style
     * ({@code PreToolUs}) is answered with the Claude Code name, anything else with the AIMON name.
     */
    private static String spelledLike(String raw, String knownKey, String aimonName) {
        final String claudeCodeName = AIMON_TO_CC.get(aimonName);
        final boolean keyIsClaudeCodeName = claudeCodeName != null
                && claudeCodeName.toLowerCase(Locale.ROOT).equals(knownKey);
        final boolean typedClaudeCodeStyle = !raw.isEmpty() && Character.isUpperCase(raw.charAt(0));
        final boolean keyIsAlsoAimonName = aimonName.toLowerCase(Locale.ROOT).equals(knownKey);
        if (keyIsClaudeCodeName && (typedClaudeCodeStyle || !keyIsAlsoAimonName)) {
            return claudeCodeName;
        }
        return aimonName;
    }

    /** Levenshtein distance: insertions, deletions and substitutions each cost one. */
    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                final int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /**
     * A known event name close to an unknown one.
     *
     * <p>
     * Immutable; thread-safe.
     */
    public static final class Nearest {

        private final String name;
        private final String aimonName;
        private final int distance;

        private Nearest(String name, String aimonName, int distance) {
            this.name = name;
            this.aimonName = aimonName;
            this.distance = distance;
        }

        /**
         * @return the known name to suggest, spelled in the style the unknown name was typed in (never null)
         */
        public String getName() {
            return name;
        }

        /**
         * @return the canonical AIMON name of that event (never null)
         */
        public String getAimonName() {
            return aimonName;
        }

        /**
         * @return the edit distance between the unknown name and the known one (1 or more)
         */
        public int getDistance() {
            return distance;
        }

        /**
         * @return true when the known event is one whose hooks can block
         */
        public boolean isGuard() {
            return HookEventName.isGuard(aimonName);
        }
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
