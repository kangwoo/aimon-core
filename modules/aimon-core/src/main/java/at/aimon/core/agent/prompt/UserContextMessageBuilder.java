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
 * extensions (e.g. {@code CLAUDE.md} contents) sourced from a {@link AgentEnvironmentSnapshot}. Because the entries are
 * wrapped
 * via {@link SystemReminderFormatter}, the model cannot mistake them for genuine end-user intent.
 *
 * <p>
 * The emitted ordering is stable:
 *
 * <ol>
 * <li>{@code working-directory} — when non-blank
 * <li>{@code current-date} — the instant's canonical {@link java.time.Instant#toString() ISO-8601} form
 * <li>each entry in {@link AgentEnvironmentSnapshot#getExtensions() extensions}, in the map's iteration order;
 * extension keys are
 * used verbatim as reminder keys, so callers that need deterministic ordering should pass a
 * {@link java.util.LinkedHashMap}
 * </ol>
 *
 * <p>
 * Returns {@link Optional#empty() empty} when no entry would be emitted (e.g. the working directory is blank, the
 * session's extensions map is empty, and {@code currentDate} is somehow absent). Ordinary
 * {@link AgentEnvironmentSnapshot}
 * instances always yield at least the working directory and current date, so {@link Optional#empty()} is primarily a
 * defensive guard against degenerate inputs.
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
     * Builds the synthetic user-context message for the given {@link AgentEnvironmentSnapshot}.
     *
     * <p>
     * The returned message, if present, is a user-role {@link Message} whose content is the concatenation of
     * {@code <system-reminder>} blocks for each populated entry. Entries whose values are {@code null} or blank are
     * skipped; if no entry remains, {@link Optional#empty()} is returned.
     *
     * @param agentEnvironmentSnapshot
     *            the session context to materialise into a user message (must not be null)
     * @return the synthetic user-context message, or {@link Optional#empty()} if no entry would be emitted
     * @throws NullPointerException
     *             if {@code agentEnvironmentSnapshot} is null
     */
    public static Optional<Message> build(AgentEnvironmentSnapshot agentEnvironmentSnapshot) {
        Objects.requireNonNull(agentEnvironmentSnapshot, "agentEnvironmentSnapshot must not be null");

        final Map<String, String> entries = new LinkedHashMap<>();

        final String workingDirectory = agentEnvironmentSnapshot.getWorkingDirectory();
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            entries.put(KEY_WORKING_DIRECTORY, workingDirectory);
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
