package at.aimon.core.hook;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.prompt.SystemReminderFormatter;
import at.aimon.core.hook.execution.HookResult;

/**
 * Renders advisory {@link HookResult#withFeedback(String) hook feedback} into the form the model actually sees.
 *
 * <p>
 * Feedback is synthetic context, not genuine end-user intent, so it is wrapped in a
 * {@code <system-reminder key="hook-feedback">} block per the convention documented in
 * {@code docs/features/agent-execution/system-reminder-convention.md}.
 *
 * <p>
 * Rendering here is only half the contract: a chain's feedback reaches the model only if its <em>firing site</em> reads
 * the returned results. Today three routes exist, and most sites take none of them:
 *
 * <ul>
 * <li><b>Tool-scoped chains</b> ({@code PermissionRequest} / {@code PreTool} / {@code PostTool}) — the block is
 * appended to the tool result by {@code SingleToolInvoker}, so a note stays attributable to its dispatch when a batch
 * runs tools in parallel and no user turn is inserted between an assistant {@code tool_use} and its
 * {@code tool_result}.
 * <li><b>{@code OnStart}</b> — the only lifecycle chain whose feedback becomes a user-role message, appended to the
 * conversation by {@code OrcaAgentExecutor} and {@code DefaultSubagentExecutor}.
 * <li><b>{@code PreCompact}</b> — not rendered as a message at all: {@code DefaultCompactionEngine} merges the advisory
 * text into the summarization system prompt as custom instructions.
 * </ul>
 *
 * <p>
 * Every other firing site drops the results it gets: {@code PermissionDenied}, {@code OnStop}, {@code OnSessionStart},
 * {@code OnSessionEnd}, {@code SubagentStart}, {@code SubagentStop}, {@code PostCompact} and {@code OnConfigReload} are
 * invoked for their side effects only, so feedback returned from a hook on one of those events is silently discarded.
 * Wiring one up is a feature, not a bug fix — each needs a call site that decides where the block belongs.
 *
 * <p>
 * Feedback carried by a {@link HookResult#isBlocked() blocked} result is the deny reason, which the blocking site
 * already renders into its error; {@link #collectAdvisory(List)} therefore skips those rather than showing the model
 * the same sentence twice.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class HookFeedback {

    /** Reminder key tagging every rendered hook-feedback block. */
    public static final String REMINDER_KEY = "hook-feedback";

    private static final Logger log = LoggerFactory.getLogger(HookFeedback.class);

    private HookFeedback() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Collects the advisory feedback of one hook chain, skipping blocked results and blank messages.
     *
     * @param results
     *            the chain's results (must not be null)
     * @return the advisory messages in chain order (never null, may be empty)
     * @throws NullPointerException
     *             if results is null
     */
    public static List<String> collectAdvisory(List<HookResult> results) {
        Objects.requireNonNull(results, "Results cannot be null");
        return results.stream().filter(result -> !result.isBlocked()).map(HookResult::getFeedback)
                .filter(Optional::isPresent).map(Optional::get).filter(message -> !message.isBlank()).toList();
    }

    /**
     * Renders the given messages as a single {@code <system-reminder>} block.
     *
     * <p>
     * Best-effort by design: hook feedback is auxiliary, so a message the reminder formatter cannot represent is
     * logged and dropped rather than allowed to fail the surrounding turn. Reserved markers inside a message are
     * neutralized, so a hook cannot forge a reminder block of its own.
     *
     * @param messages
     *            the advisory messages, typically from {@link #collectAdvisory(List)} (must not be null)
     * @return the rendered block, or {@link Optional#empty()} when there is nothing to say (or rendering failed)
     * @throws NullPointerException
     *             if messages is null
     */
    public static Optional<String> toReminderBlock(List<String> messages) {
        Objects.requireNonNull(messages, "Messages cannot be null");
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(SystemReminderFormatter.wrap(REMINDER_KEY,
                    SystemReminderFormatter.sanitizeBody(String.join("\n\n", messages))));
        } catch (RuntimeException e) {
            log.warn("Dropping {} hook feedback message(s) that could not be rendered: {}", messages.size(),
                    e.getMessage());
            return Optional.empty();
        }
    }
}
