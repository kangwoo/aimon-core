package at.aimon.core.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Verifies that a stack of {@link CommandRegistry} sources does not expose the same command name twice.
 *
 * <p>
 * Introduced in SK-08-D. The composite slash-command pipeline assembles two sources in priority order — system
 * commands &gt; {@link at.aimon.core.command.skill.SkillBackedCommandRegistry skill-backed commands}. Within a
 * single source the registry implementation owns deduplication (e.g. {@link DefaultCommandRegistry}), but
 * <b>across</b> sources we want a fail-fast guard: if a built-in command and a user-invocable skill both publish
 * {@code commit}, executing {@code /commit} would silently dispatch to whatever the lookup order picked first, and
 * reloading either side could flip the answer. The detector catches this at boot (REPL start) and again after
 * {@link CommandRegistry#reloadAll()}.
 *
 * <p>
 * The detector deliberately ignores ordering — every collision is a hard failure that demands operator action (rename
 * one side or remove one). This matches the SK-08 acceptance criterion "충돌 시 명확한 거부."
 *
 * <p>
 * Thread-safe and stateless.
 */
public final class CommandNameConflictDetector {

    /** Creates a new detector. */
    public CommandNameConflictDetector() {
    }

    /**
     * Verifies that no command name appears in more than one of the supplied registries.
     *
     * <p>
     * The list is consumed in iteration order; failure is reported with sorted source labels for stable, diff-friendly
     * messages. A registry whose label is null or blank is rejected up-front, so callers cannot accidentally hide a
     * source.
     *
     * @param sources
     *            Ordered registry sources to inspect (must not be null; entries must not be null)
     * @throws NullPointerException
     *             if {@code sources} or any entry is null
     * @throws IllegalArgumentException
     *             if any entry has a blank label
     * @throws IllegalStateException
     *             if at least one command name is owned by more than one registry; the message lists every conflicting
     *             name and the labels of the registries that publish it
     */
    public void verifyNoConflicts(List<Source> sources) {
        Objects.requireNonNull(sources, "Sources cannot be null");

        // name -> ordered list of source labels that expose it. We dedupe inside a single source so a malformed
        // registry that exposes the same name twice does not produce a confusing "skill vs skill" conflict message;
        // intra-source duplication is not what this detector polices.
        final Map<String, List<String>> ownership = new LinkedHashMap<>();
        for (Source source : sources) {
            Objects.requireNonNull(source, "Source cannot be null");
            final Set<String> namesInSource = new HashSet<>();
            for (Command command : source.commands()) {
                if (namesInSource.add(command.getName())) {
                    ownership.computeIfAbsent(command.getName(), k -> new ArrayList<>()).add(source.label());
                }
            }
        }

        // Sort by command name for stable error messages.
        final Map<String, List<String>> conflicts = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : ownership.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.put(entry.getKey(), entry.getValue());
            }
        }

        if (conflicts.isEmpty()) {
            return;
        }

        throw new IllegalStateException(formatConflictMessage(conflicts));
    }

    private static String formatConflictMessage(Map<String, List<String>> conflicts) {
        final String detail = conflicts.entrySet().stream()
                .map(e -> "  - '" + e.getKey() + "' is published by: " + String.join(", ", e.getValue()))
                .collect(Collectors.joining("\n"));
        return "Command name conflicts detected across registries:\n" + detail
                + "\nRename or remove one of the conflicting definitions before starting.";
    }

    /**
     * A labelled command source for conflict detection.
     *
     * <p>
     * The label appears verbatim in error messages, so prefer human-friendly identifiers (e.g. {@code "system"},
     * {@code "skill"}). Sources are modelled as a supplier of commands rather than a full {@link CommandRegistry} so
     * in-process registries that do not implement {@code CommandRegistry} (e.g. {@link SystemCommandRegistry}) can
     * participate without adapter scaffolding.
     */
    public static final class Source {

        /**
         * Creates a new source backed by a {@link CommandRegistry}.
         *
         * @param label
         *            Human-friendly identifier (must not be null or blank)
         * @param registry
         *            The registry to inspect (must not be null)
         * @return A new {@link Source}
         * @throws NullPointerException
         *             if any argument is null
         * @throws IllegalArgumentException
         *             if {@code label} is blank
         */
        public static Source of(String label, CommandRegistry registry) {
            Objects.requireNonNull(registry, "Registry cannot be null");
            return new Source(label, registry::getAllCommands);
        }

        /**
         * Creates a new source from a lazy command supplier.
         *
         * <p>
         * Use this factory when the underlying registry does not implement {@link CommandRegistry} — for example
         * {@link SystemCommandRegistry}, which exposes its own {@code getAllCommands} returning command subtypes.
         *
         * @param label
         *            Human-friendly identifier (must not be null or blank)
         * @param commands
         *            Supplier returning the commands currently owned by this source (must not be null, must not return
         *            null)
         * @return A new {@link Source}
         * @throws NullPointerException
         *             if any argument is null
         * @throws IllegalArgumentException
         *             if {@code label} is blank
         */
        public static Source ofCommands(String label, Supplier<List<Command>> commands) {
            Objects.requireNonNull(commands, "Commands supplier cannot be null");
            return new Source(label, commands);
        }

        private final String label;
        private final Supplier<List<Command>> commands;

        private Source(String label, Supplier<List<Command>> commands) {
            Objects.requireNonNull(label, "Label cannot be null");
            if (label.isBlank()) {
                throw new IllegalArgumentException("Label cannot be blank");
            }
            this.label = label;
            this.commands = commands;
        }

        /**
         * @return The source label (never null or blank)
         */
        public String label() {
            return label;
        }

        /**
         * @return The commands currently owned by this source (never null)
         */
        public List<Command> commands() {
            return Objects.requireNonNull(commands.get(), "Commands supplier returned null");
        }
    }
}
