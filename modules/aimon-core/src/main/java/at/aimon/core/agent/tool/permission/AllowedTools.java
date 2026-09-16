package at.aimon.core.agent.tool.permission;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Algebra over allow-lists, for a caller that imposes its own list on top of another's.
 *
 * <p>
 * <b>An empty list means unrestricted</b> — that is what every validator in this package does with one
 * ({@code DefaultToolPermissionValidator.isAllowed} returns {@code true} before looking at anything else). So a
 * conjunction that admits nothing <b>cannot be returned as a list</b>: doing so would turn "deny everything" into
 * "allow everything", the most dangerous inversion available here. {@link #intersect} returns an empty
 * {@link Optional} for that case, and a caller must treat it as a refusal rather than as an empty result.
 */
public final class AllowedTools {

    private AllowedTools() {
    }

    /**
     * Returns the entries that satisfy <b>both</b> lists, or an empty {@link Optional} when nothing does.
     *
     * <p>
     * A list is OR-of-entries, so a true conjunction of two patterned entries is not generally expressible as one
     * list. The result is therefore the narrowest list that is always a <b>subset</b> of the conjunction — it never
     * permits a call that either input would refuse, and it is exact wherever it can be:
     *
     * <ul>
     * <li>Either list empty — that side restricts nothing, so the other governs and the result is exact.
     * <li>A name one side does not mention — dropped. Exact: that side already refuses it.
     * <li>One side allows the name with no pattern — the other side's entries govern, and the result is exact.
     * <li>Both sides pattern the name — only entries present in both survive, compared by
     * {@link AllowedTool#equals(Object)}. Exact for an identical pattern; a pair that merely overlaps is dropped
     * rather than approximated, because a pattern intersection is not computable from two globs in general and an
     * over-wide guess here would grant what one side refused.
     * </ul>
     *
     * @param first
     *            one allow-list, typically the caller's (must not be null)
     * @param second
     *            the other, typically the target's (must not be null)
     * @return the combined list, or empty when the two admit nothing in common — <b>never an empty list</b>
     * @throws NullPointerException
     *             if either argument is null
     */
    public static Optional<List<AllowedTool>> intersect(List<AllowedTool> first, List<AllowedTool> second) {
        Objects.requireNonNull(first, "First allow-list cannot be null");
        Objects.requireNonNull(second, "Second allow-list cannot be null");

        if (first.isEmpty()) {
            return Optional.of(List.copyOf(second));
        }
        if (second.isEmpty()) {
            return Optional.of(List.copyOf(first));
        }

        final LinkedHashSet<AllowedTool> result = new LinkedHashSet<>();
        for (String name : namesOf(first)) {
            final List<AllowedTool> fromFirst = entriesNamed(first, name);
            final List<AllowedTool> fromSecond = entriesNamed(second, name);
            if (fromSecond.isEmpty()) {
                continue;
            }
            if (fromFirst.stream().anyMatch(entry -> !entry.hasPattern())) {
                result.addAll(fromSecond);
            } else if (fromSecond.stream().anyMatch(entry -> !entry.hasPattern())) {
                result.addAll(fromFirst);
            } else {
                fromFirst.stream().filter(fromSecond::contains).forEach(result::add);
            }
        }
        return result.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(result));
    }

    private static LinkedHashSet<String> namesOf(List<AllowedTool> allowedTools) {
        final LinkedHashSet<String> names = new LinkedHashSet<>();
        for (AllowedTool entry : allowedTools) {
            names.add(entry.getToolName());
        }
        return names;
    }

    private static List<AllowedTool> entriesNamed(List<AllowedTool> allowedTools, String name) {
        final List<AllowedTool> matching = new ArrayList<>();
        for (AllowedTool entry : allowedTools) {
            if (entry.getToolName().equals(name)) {
                matching.add(entry);
            }
        }
        return matching;
    }
}
