package at.aimon.core.agent.prompt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentEnvironmentSnapshot;
import at.aimon.core.llm.Message;

/**
 * Builds the synthetic first-user message that carries session-level context to the LLM.
 *
 * <p>
 * Mirrors the reference implementation's behavior: the LLM's very first user-role message is a synthetic block
 * containing
 * {@code <system-reminder>}-wrapped entries describing the working directory, the current date, and any user-defined
 * extensions (e.g. {@code CLAUDE.md} contents). The date and the extensions come from an
 * {@link AgentEnvironmentSnapshot}; the working directory does not — it is a fact of the execution that is running,
 * so the caller passes it in from that execution's environment. Because the entries are wrapped via
 * {@link SystemReminderFormatter}, the model cannot mistake them for genuine end-user intent.
 *
 * <p>
 * The emitted ordering is stable:
 *
 * <ol>
 * <li>{@code working-directory} — the execution's, when the caller passed a non-blank one
 * <li>{@code current-date} — the instant's canonical {@link java.time.Instant#toString() ISO-8601} form
 * <li>each entry in {@link AgentEnvironmentSnapshot#getExtensions() extensions}, in the map's iteration order;
 * extension keys are
 * used verbatim as reminder keys, so callers that need deterministic ordering should pass a
 * {@link java.util.LinkedHashMap}
 * </ol>
 *
 * <p>
 * Returns {@link Optional#empty() empty} when no entry would be emitted (e.g. the working directory is blank, the
 * snapshot's extensions map is empty, and {@code currentDate} is somehow absent). Ordinary
 * {@link AgentEnvironmentSnapshot} instances always yield at least the current date, so {@link Optional#empty()} is
 * primarily a defensive guard against degenerate inputs.
 *
 * <p>
 * This builder holds no state and is thread-safe.
 */
public final class UserContextMessageBuilder {

    private static final String KEY_WORKING_DIRECTORY = "working-directory";
    private static final String KEY_CURRENT_DATE = "current-date";

    private UserContextMessageBuilder() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Builds the synthetic user-context message for the given {@link AgentEnvironmentSnapshot} alone, with no
     * execution behind it — so the message has no {@code working-directory} entry.
     *
     * <p>
     * The returned message, if present, is a user-role {@link Message} whose content is the concatenation of
     * {@code <system-reminder>} blocks for each populated entry. Entries whose values are {@code null} or blank are
     * skipped; if no entry remains, {@link Optional#empty()} is returned.
     *
     * @param agentEnvironmentSnapshot
     *            the agent's environment snapshot to materialise into a user message (must not be null)
     * @return the synthetic user-context message, or {@link Optional#empty()} if no entry would be emitted
     * @throws NullPointerException
     *             if {@code agentEnvironmentSnapshot} is null
     */
    public static Optional<Message> build(AgentEnvironmentSnapshot agentEnvironmentSnapshot) {
        return build(agentEnvironmentSnapshot, null);
    }

    /**
     * As {@link #build(AgentEnvironmentSnapshot)}, plus the working directory of the execution that is running — the
     * directory its environment's shell and file tools resolve against (execution-environment design §10).
     *
     * <p>
     * That directory is the only source of the {@code working-directory} entry. An execution whose environment is
     * unavailable has none, and then the entry is left out: nothing else stands in for it, least of all a directory
     * of the host the framework runs on (design §5.1).
     *
     * @param agentEnvironmentSnapshot
     *            the agent's environment snapshot to materialise into a user message (must not be null)
     * @param executionWorkingDirectory
     *            the execution's working directory; null or blank emits no {@code working-directory} entry
     * @return the synthetic user-context message, or {@link Optional#empty()} if no entry would be emitted
     * @throws NullPointerException
     *             if {@code agentEnvironmentSnapshot} is null
     */
    public static Optional<Message> build(AgentEnvironmentSnapshot agentEnvironmentSnapshot,
            String executionWorkingDirectory) {
        Objects.requireNonNull(agentEnvironmentSnapshot, "agentEnvironmentSnapshot must not be null");

        final Map<String, String> entries = new LinkedHashMap<>();

        if (executionWorkingDirectory != null && !executionWorkingDirectory.isBlank()) {
            entries.put(KEY_WORKING_DIRECTORY, executionWorkingDirectory);
        }

        if (agentEnvironmentSnapshot.getCurrentDate() != null) {
            entries.put(KEY_CURRENT_DATE, agentEnvironmentSnapshot.getCurrentDate().toString());
        }

        for (Map.Entry<String, String> extension : agentEnvironmentSnapshot.getExtensions().entrySet()) {
            final String key = extension.getKey();
            final String value = extension.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            // Extension keys are used verbatim as reminder keys; SystemReminderFormatter enforces key syntax.
            entries.put(key, value);
        }

        if (entries.isEmpty()) {
            return Optional.empty();
        }

        final String body = SystemReminderFormatter.wrapMany(entries);
        return Optional.of(Message.user(body));
    }
}
