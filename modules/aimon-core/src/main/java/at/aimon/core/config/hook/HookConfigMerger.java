package at.aimon.core.config.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.config.hook.MergedHookConfig.MergedHookEntry;

/**
 * Merges the per-source documents of a {@link LayeredHookConfig} into a single {@link MergedHookConfig} keyed by
 * AIMON event name.
 *
 * <p>
 * Merge strategy:
 * <ul>
 * <li>Hooks across layers are <strong>additive</strong>: every entry from every present layer fires (matching the
 * Claude Code semantics for layered hooks).
 * <li>Layer order is precedence-ascending &mdash; USER &rarr; PROJECT &rarr; LOCAL. Later layers append after earlier
 * layers, so when two entries share a matcher the later layer fires later in the dispatch list and effectively
 * &quot;overrides&quot; outcomes that depend on order (e.g. {@code updatedInput} threading).
 * <li>SKILL entries are kept separate &mdash; they are emitted under the same event keys but tagged with the owning
 * skill so the bootstrap can attach them to the skill-scoped registry rather than the global one.
 * <li>Unknown / unsupported event names are skipped with a WARN log, which names the nearest known event when the
 * unknown name is a near-miss of one. A near-miss of a <em>guard</em> event is the exception: it fails the merge with
 * a {@link HookConfigParseException} (see {@link HookEventName#nearest(String)}).
 * <li>Unknown JSON fields on {@link HookHandlerSpec} are silently dropped at parse time
 * (forwards-compat hook for fields such as {@code asyncRewake}).
 * </ul>
 *
 * <p>
 * Thread-safe and stateless.
 */
public final class HookConfigMerger {

    private static final Logger log = LoggerFactory.getLogger(HookConfigMerger.class);

    /**
     * Merges {@code config} into a flat per-event dispatch table.
     *
     * @param config
     *            the layered config (must not be null)
     * @return merged config (never null; empty when {@code config} is empty)
     */
    public MergedHookConfig merge(LayeredHookConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        final Map<String, List<MergedHookEntry>> out = new LinkedHashMap<>();

        for (Map.Entry<HookConfigSource, HookConfigDocument> layer : config.layeredAscending()) {
            mergeOne(out, layer.getKey(), null, layer.getValue(), config);
        }
        for (Map.Entry<String, HookConfigDocument> skillEntry : config.skills().entrySet()) {
            mergeOne(out, HookConfigSource.SKILL, skillEntry.getKey(), skillEntry.getValue(), config);
        }
        return MergedHookConfig.of(out, config.origins());
    }

    private void mergeOne(Map<String, List<MergedHookEntry>> out, HookConfigSource source, String skillName,
            HookConfigDocument doc, LayeredHookConfig config) {
        for (Map.Entry<String, List<HookEntry>> e : doc.getHooks().entrySet()) {
            final String rawEvent = e.getKey();
            if (HookEventName.isUnsupported(rawEvent)) {
                log.warn("hooks: '{}' event is not supported by AIMON in this phase; entries from {} will be"
                        + " ignored", rawEvent, describe(source, skillName));
                continue;
            }
            final String aimonName = HookEventName.toAimon(rawEvent).orElse(null);
            if (aimonName == null) {
                skipOrRejectUnknownEvent(rawEvent, source, skillName, config);
                continue;
            }
            final List<MergedHookEntry> bucket = out.computeIfAbsent(aimonName, k -> new ArrayList<>());
            for (HookEntry entry : e.getValue()) {
                bucket.add(new MergedHookEntry(source, skillName, entry));
            }
        }
    }

    /**
     * An event name AIMON does not know is skipped with a WARN: it may be an event a newer AIMON (or Claude Code)
     * has. The exception is a name within {@link HookEventName#NEAR_MISS_DISTANCE} edits of a <em>guard</em> event
     * &mdash; {@code preTol} is a typo, not a future event, and skipping it would start the host with the guard the
     * operator wrote missing. That one fails the load, in the wording a file that does not parse gets. A near-miss of
     * any other event is still skipped, with the likely name in the WARN.
     */
    private static void skipOrRejectUnknownEvent(String rawEvent, HookConfigSource source, String skillName,
            LayeredHookConfig config) {
        final HookEventName.Nearest nearest = HookEventName.nearest(rawEvent).orElse(null);
        if (nearest == null) {
            log.warn("hooks: unknown event '{}' from {}; entries will be ignored", rawEvent,
                    describe(source, skillName));
            return;
        }
        if (nearest.isGuard() && source != HookConfigSource.SKILL) {
            throw new HookConfigParseException(MergedHookConfig.fileLabel(config.origin(source).orElse(null), source)
                    + " is invalid: unknown event '" + rawEvent + "' - did you mean '" + nearest.getName()
                    + "'? A name this close to an event"
                    + " whose hooks can block is read as a typo, not as an event AIMON does not know yet:"
                    + " skipping it would leave that guard off");
        }
        log.warn("hooks: unknown event '{}' from {} - did you mean '{}'? entries will be ignored", rawEvent,
                describe(source, skillName), nearest.getName());
    }

    private static String describe(HookConfigSource source, String skillName) {
        return source == HookConfigSource.SKILL ? "skill:" + skillName : source.name();
    }
}
