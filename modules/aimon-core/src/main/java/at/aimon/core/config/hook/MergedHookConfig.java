package at.aimon.core.config.hook;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The flattened result of merging the layered configs into a single per-event dispatch list.
 *
 * <p>
 * Each entry is a {@link MergedHookEntry} that remembers the {@link HookConfigSource} it came from so the loader can
 * surface descriptive errors (&quot;invalid handler in project hooks.json line N&quot;) and the bootstrap can do
 * source-aware deduplication.
 *
 * <p>
 * Immutable; thread-safe.
 */
public final class MergedHookConfig {

    private final Map<String, List<MergedHookEntry>> entriesByAimonEvent;

    private MergedHookConfig(Map<String, List<MergedHookEntry>> entriesByAimonEvent) {
        final Map<String, List<MergedHookEntry>> copy = new LinkedHashMap<>();
        entriesByAimonEvent.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.entriesByAimonEvent = Collections.unmodifiableMap(copy);
    }

    /**
     * @return immutable map keyed by AIMON event name, values are entries in dispatch order (never null)
     */
    public Map<String, List<MergedHookEntry>> entriesByAimonEvent() {
        return entriesByAimonEvent;
    }

    /**
     * Returns the merged entries for an AIMON event.
     *
     * @param aimonEventName
     *            canonical AIMON event name (must not be null)
     * @return immutable list (possibly empty)
     */
    public List<MergedHookEntry> forEvent(String aimonEventName) {
        Objects.requireNonNull(aimonEventName, "aimonEventName cannot be null");
        return entriesByAimonEvent.getOrDefault(aimonEventName, List.of());
    }

    /**
     * @param entriesByAimonEvent
     *            already-merged map (must not be null; defensively copied)
     * @return new merged config (never null)
     */
    public static MergedHookConfig of(Map<String, List<MergedHookEntry>> entriesByAimonEvent) {
        Objects.requireNonNull(entriesByAimonEvent, "entriesByAimonEvent cannot be null");
        return new MergedHookConfig(entriesByAimonEvent);
    }

    /** @return empty config */
    public static MergedHookConfig empty() {
        return new MergedHookConfig(Map.of());
    }

    /**
     * One {@link HookEntry} after merge, annotated with its provenance.
     */
    public static final class MergedHookEntry {

        private final HookConfigSource source;
        private final String skillName;
        private final HookEntry entry;

        /**
         * @param source
         *            the originating layer (must not be null)
         * @param skillName
         *            the owning skill name (only set when {@code source == SKILL}); may be null otherwise
         * @param entry
         *            the underlying entry (must not be null)
         */
        public MergedHookEntry(HookConfigSource source, String skillName, HookEntry entry) {
            this.source = Objects.requireNonNull(source, "source cannot be null");
            this.skillName = skillName;
            this.entry = Objects.requireNonNull(entry, "entry cannot be null");
        }

        /** @return the originating layer (never null) */
        public HookConfigSource getSource() {
            return source;
        }

        /** @return the owning skill, or {@code null} for non-SKILL sources */
        public String getSkillName() {
            return skillName;
        }

        /** @return the underlying entry (never null) */
        public HookEntry getEntry() {
            return entry;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MergedHookEntry that)) {
                return false;
            }
            return source == that.source && Objects.equals(skillName, that.skillName) && entry.equals(that.entry);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, skillName, entry);
        }
    }
}
