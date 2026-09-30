package at.aimon.core.agent.tool.permission;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import at.aimon.core.agent.tool.Tool;

/**
 * Operations over allow-lists: narrowing an offer to what a list admits, and combining a caller's list with
 * another's.
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
     * Returns a predicate admitting the tools an allow-list names, for withholding the rest from a model's offer.
     *
     * <p>
     * <b>Name level is all this can do, and that is a property of an allow-list rather than a shortcoming here.</b> An
     * {@link AllowedTool} may carry a pattern ({@code Bash(git:*)}, {@code Read(/tmp/**)}), and a pattern cannot be
     * expressed in a list of tools — a tool is either offered or it is not. So a pattern-restricted tool stays
     * admitted and its out-of-pattern calls are still refused at dispatch. What this removes is the other case: a tool
     * whose <i>name</i> appears nowhere in the list, which could never have been dispatched at all.
     *
     * <p>
     * <b>It cannot over-withhold.</b> {@link DefaultToolPermissionValidator} keeps only the entries whose name equals
     * the called tool's before it consults any subject or custom rule, so a name absent from the list is already a
     * denial. A name match is a necessary condition for permission, and filtering on the name set therefore never
     * withholds a tool that would have been allowed.
     *
     * <p>
     * An empty list restricts nothing, so the predicate admits everything — the reading every validator in this
     * package gives an empty list.
     *
     * <p>
     * Prefer this over testing entries in a loop: the list is reduced to a name set once here rather than once per
     * candidate. The predicate is immutable and holds no reference to any registry.
     *
     * @param allowedTools
     *            the allow-list to read (must not be null)
     * @return a predicate over tools, always {@code true} when the list is empty
     * @throws NullPointerException
     *             if allowedTools is null
     */
    public static Predicate<Tool> admissionFilter(List<AllowedTool> allowedTools) {
        Objects.requireNonNull(allowedTools, "Allowed tools cannot be null");
        if (allowedTools.isEmpty()) {
            return tool -> true;
        }
        final Set<String> allowedNames = namesOf(allowedTools);
        return tool -> allowedNames.contains(tool.getDefinition().getName());
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
     * <li>One side names the tool with <b>no pattern entry at all</b> — that side does not constrain it, so the
     * other's entries govern and the result is exact. The condition is the absence of every pattern for that name,
     * not the presence of one bare entry: {@code Read, Read(/tmp/**)} means <em>/tmp only</em>, which is how
     * {@link DefaultToolPermissionValidator} reads it, so its pattern has to survive.
     * <li>Both sides pattern the name — only entries present in both survive, compared by
     * {@link AllowedTool#equals(Object)}. Exact for an identical pattern; a pair that merely overlaps is dropped
     * rather than approximated, because a pattern intersection is not computable from two globs in general and an
     * over-wide guess here would grant what one side refused.
     * </ul>
     *
     * <p>
     * The result does not depend on the argument order: swapping the two yields the same list.
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
            // "This side does not constrain this tool" is noneMatch(hasPattern), not anyMatch(!hasPattern), because
            // that is the test DefaultToolPermissionValidator.isAllowed applies: a bare name is neutralized by any
            // sibling pattern entry for the same name, so `Read, Read(/tmp/**)` means /tmp only. Reading the bare
            // name as unrestricted here would hand a spawned run a wider reach than the caller that spawned it.
            if (fromFirst.stream().noneMatch(AllowedTool::hasPattern)) {
                result.addAll(fromSecond);
            } else if (fromSecond.stream().noneMatch(AllowedTool::hasPattern)) {
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
