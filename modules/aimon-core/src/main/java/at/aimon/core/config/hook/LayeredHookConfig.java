package at.aimon.core.config.hook;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Holds the per-source {@link HookConfigDocument} produced by {@link HookConfigLoader}.
 *
 * <p>
 * The layering precedence (USER &lt; PROJECT &lt; LOCAL) is enforced by {@code HookConfigMerger} at the
 * dispatch table level &mdash; this value object simply preserves the per-source split so the merger can apply the
 * rules and the loader can report which file contributed which entry.
 *
 * <p>
 * SKILL entries are intentionally separate: each skill carries its own list of (skill name → document) pairs because
 * skills are scope-isolated and may register/unregister independently.
 *
 * <p>
 * Immutable; thread-safe.
 */
public final class LayeredHookConfig {

    private final Map<HookConfigSource, HookConfigDocument> layered;
    private final Map<String, HookConfigDocument> skills;

    private LayeredHookConfig(Builder b) {
        this.layered = Collections.unmodifiableMap(new EnumMap<>(b.layered));
        this.skills = Map.copyOf(b.skills);
    }

    /**
     * @return immutable map (USER/PROJECT/LOCAL → document); sources without a config are absent
     */
    public Map<HookConfigSource, HookConfigDocument> layered() {
        return layered;
    }

    /**
     * @return immutable map (skill name → document); empty when no skills declare hooks
     */
    public Map<String, HookConfigDocument> skills() {
        return skills;
    }

    /**
     * Convenience accessor.
     *
     * @param source
     *            the source to look up (must not be null)
     * @return document for the source, or {@link HookConfigDocument#empty()} when absent
     */
    public HookConfigDocument get(HookConfigSource source) {
        Objects.requireNonNull(source, "source cannot be null");
        return layered.getOrDefault(source, HookConfigDocument.empty());
    }

    /**
     * Returns the document declared by a specific skill.
     *
     * @param skillName
     *            the skill name (must not be null)
     * @return the document, or {@link HookConfigDocument#empty()} when the skill has no hooks
     */
    public HookConfigDocument forSkill(String skillName) {
        Objects.requireNonNull(skillName, "skillName cannot be null");
        return skills.getOrDefault(skillName, HookConfigDocument.empty());
    }

    /**
     * Returns the per-event entries the merger should consider, ordered USER → PROJECT → LOCAL.
     *
     * <p>
     * SKILL is excluded because skills are dispatched into their own scope-isolated registry.
     *
     * @return list of (source, document) pairs in precedence-ascending order
     */
    public List<Map.Entry<HookConfigSource, HookConfigDocument>> layeredAscending() {
        final java.util.ArrayList<Map.Entry<HookConfigSource, HookConfigDocument>> out = new java.util.ArrayList<>();
        for (HookConfigSource src : new HookConfigSource[]{HookConfigSource.USER, HookConfigSource.PROJECT,
                HookConfigSource.LOCAL}) {
            final HookConfigDocument doc = layered.get(src);
            if (doc != null) {
                out.add(Map.entry(src, doc));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** @return new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return empty layered config */
    public static LayeredHookConfig empty() {
        return new Builder().build();
    }

    /** Builder. */
    public static final class Builder {
        private final EnumMap<HookConfigSource, HookConfigDocument> layered = new EnumMap<>(HookConfigSource.class);
        private final Map<String, HookConfigDocument> skills = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Records a layered (USER/PROJECT/LOCAL) document. Calling with {@link HookConfigSource#SKILL} is rejected.
         *
         * @param source
         *            the source layer (must not be null and must not be SKILL)
         * @param document
         *            the parsed document (must not be null)
         * @return this builder
         */
        public Builder put(HookConfigSource source, HookConfigDocument document) {
            Objects.requireNonNull(source, "source cannot be null");
            Objects.requireNonNull(document, "document cannot be null");
            if (source == HookConfigSource.SKILL) {
                throw new IllegalArgumentException("Use putSkill(name, doc) for SKILL-scoped configs");
            }
            this.layered.put(source, document);
            return this;
        }

        /**
         * Records a skill-scoped document.
         *
         * @param skillName
         *            the owning skill name (must not be null)
         * @param document
         *            the parsed document (must not be null)
         * @return this builder
         */
        public Builder putSkill(String skillName, HookConfigDocument document) {
            Objects.requireNonNull(skillName, "skillName cannot be null");
            Objects.requireNonNull(document, "document cannot be null");
            this.skills.put(skillName, document);
            return this;
        }

        public LayeredHookConfig build() {
            return new LayeredHookConfig(this);
        }
    }
}
