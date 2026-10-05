package at.aimon.core.llm.capability;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import at.aimon.core.llm.ReasoningEffort;

/**
 * Works out what a configured declaration silently took away from the built-in row it displaced, and says so.
 *
 * <p>
 * A declaration is the whole row for its name (see
 * {@link InMemoryModelCapabilityRegistry#withDefaultsExtendedBy(java.util.Map)}), so a flag it does not state is
 * registered at {@link ModelCapabilities#unknown()}'s value — not at what the built-in row said. When the name is one
 * the built-in table already describes, that is a regression nobody asked for: declaring only
 * {@code thinkingDialect} for a {@code claude-sonnet-5} name hands {@code temperature} back to a model measured to
 * answer it with HTTP 400. This type does not change that rule. It finds the flags the rule dropped and writes the
 * sentence that names them.
 *
 * <p>
 * <b>What counts as dropped.</b> A flag the built-in row sets to something other than the fail-open value
 * <em>and</em> the declaration leaves unstated. Both halves matter. A row that merely restates a default loses
 * nothing by being replaced, and a flag the declaration does state — with the built-in value, or with a different
 * one on purpose — is the operator's own answer, which is exactly what a declaration is for. That second half is
 * also the way to silence the warning, so the message says it.
 *
 * <p>
 * The comparison is possible only because {@link ModelCapabilityDeclaration} keeps "not stated" apart from "stated as
 * the default"; the resolved {@link ModelCapabilities} it produces does not, and comparing two of those would warn
 * on every deliberate override.
 *
 * <p>
 * Stateless.
 */
final class ShadowedBuiltInRow {

    private ShadowedBuiltInRow() {
    }

    /**
     * Describes the flags a declaration dropped from the built-in row it shadows.
     *
     * @param modelName
     *            the declared name, as the operator wrote it
     * @param rowLabel
     *            how to refer to the built-in row that answered for this name before the declaration
     * @param row
     *            that row's capabilities
     * @param declaration
     *            what the operator declared
     * @return the warning to log, or empty when the declaration states every flag the row sets
     */
    static Optional<String> warningFor(String modelName, String rowLabel, ModelCapabilities row,
            ModelCapabilityDeclaration declaration) {
        final ModelCapabilities failOpen = ModelCapabilities.unknown();
        final List<String> dropped = new ArrayList<>();
        final List<String> restatements = new ArrayList<>();
        final List<String> acknowledgements = new ArrayList<>();
        final Finding finding = new Finding(dropped, restatements, acknowledgements);

        finding.compare("supportsSamplingParameters", declaration.supportsSamplingParameters().isPresent(),
                row.supportsSamplingParameters(), failOpen.supportsSamplingParameters());
        finding.compare("supportsReasoningEffort", declaration.supportsReasoningEffort().isPresent(),
                row.supportsReasoningEffort(), failOpen.supportsReasoningEffort());
        finding.compare("supportsToolsWithReasoning", declaration.supportsToolsWithReasoning().isPresent(),
                row.supportsToolsWithReasoning(), failOpen.supportsToolsWithReasoning());
        finding.compare("supportsReasoningTraceRoundTrip", declaration.supportsReasoningTraceRoundTrip().isPresent(),
                row.supportsReasoningTraceRoundTrip(), failOpen.supportsReasoningTraceRoundTrip());
        // One fact with two keys: either spelling of the ladder states it.
        finding.compare("acceptedReasoningEfforts",
                declaration.lowestReasoningEffort().isPresent() || declaration.acceptedReasoningEfforts().isPresent(),
                ladder(row.acceptedReasoningEfforts()), ladder(failOpen.acceptedReasoningEfforts()));
        finding.compare("thinkingDialect", declaration.thinkingDialect().isPresent(), lower(row.thinkingDialect()),
                lower(failOpen.thinkingDialect()));
        finding.compare("supportsReasoningSummary", declaration.supportsReasoningSummary().isPresent(),
                row.supportsReasoningSummary(), failOpen.supportsReasoningSummary());

        if (dropped.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("Model capability declaration '" + modelName + "' replaces the built-in " + rowLabel
                + " rather than patching it, and leaves unstated " + dropped.size() + " flag(s) that row sets: "
                + String.join(", ", dropped) + ". A declaration is the whole row for its name, so each unstated flag"
                + " fell back to its fail-open value and requests to '" + modelName + "' are now shaped without what"
                + " the built-in row knew. If '" + modelName + "' is the model that row describes, add to the"
                + " declaration: " + String.join("; ", restatements) + ". If the fall-back is what you want, state"
                + " it (" + String.join("; ", acknowledgements) + ") and this warning stops. Keys are shown in"
                + " camelCase, as the CLI's yaml spells them; the Spring Boot starter spells the same keys in"
                + " kebab-case.");
    }

    private static String ladder(Set<ReasoningEffort> rungs) {
        // Through an EnumSet so the rungs come out in ladder order whatever set the descriptor happens to hold.
        return EnumSet.copyOf(rungs).stream().map(ShadowedBuiltInRow::lower)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String lower(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }

    /** The three renderings of one comparison, accumulated flag by flag. */
    private static final class Finding {

        private final List<String> dropped;

        private final List<String> restatements;

        private final List<String> acknowledgements;

        private Finding(List<String> dropped, List<String> restatements, List<String> acknowledgements) {
            this.dropped = dropped;
            this.restatements = restatements;
            this.acknowledgements = acknowledgements;
        }

        private void compare(String key, boolean stated, Object builtIn, Object failOpen) {
            if (stated || builtIn.equals(failOpen)) {
                return;
            }
            dropped.add(key + "=" + builtIn + " (now " + failOpen + ")");
            restatements.add(key + ": " + builtIn);
            acknowledgements.add(key + ": " + failOpen);
        }
    }
}
