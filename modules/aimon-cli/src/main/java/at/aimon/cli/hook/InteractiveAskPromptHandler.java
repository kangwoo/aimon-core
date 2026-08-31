package at.aimon.cli.hook;

import java.io.Console;
import java.io.PrintStream;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;

/**
 * CLI-side {@link AskPromptHandler} that asks the user via stdin/stdout when a TTY is attached, and falls back to the
 * non-interactive {@link AskPromptHandler#fromEnv(Map)} default otherwise.
 *
 * <p>
 * Interactive flow:
 * <ol>
 * <li>Print the prompt followed by {@code [y/N]:}.
 * <li>Read a line from {@link System#console()}.
 * <li>{@code y} / {@code yes} (case-insensitive) &rarr; {@link Decision#ALLOW}; everything else (including EOF and the
 * empty default) &rarr; {@link Decision#DENY}. Fail-safe: any IO error logs at WARN and resolves to deny.
 * </ol>
 *
 * <p>
 * Non-TTY (no {@code System.console()}, e.g. piped CLI in CI) skips the prompt entirely and delegates to the env-driven
 * fallback &mdash; respecting {@code AIMON_HOOK_ASK_DEFAULT}.
 *
 * <p>
 * Thread-safe.
 */
public final class InteractiveAskPromptHandler implements AskPromptHandler {

    private static final Logger log = LoggerFactory.getLogger(InteractiveAskPromptHandler.class);

    private final Console console;
    private final PrintStream out;
    private final AskPromptHandler nonInteractiveFallback;

    /**
     * Creates a handler that uses {@link System#console()} and {@link System#out} and falls back to
     * {@link AskPromptHandler#fromEnv(Map)} on {@link System#getenv()}.
     */
    public InteractiveAskPromptHandler() {
        this(System.console(), System.out, AskPromptHandler.fromEnv(System.getenv()));
    }

    /**
     * Creates a handler with explicit dependencies (test-friendly).
     *
     * @param console
     *            the interactive console; pass {@code null} to force non-interactive mode
     * @param out
     *            the stream the prompt is written to (must not be null)
     * @param nonInteractiveFallback
     *            handler used when no console is attached (must not be null)
     */
    public InteractiveAskPromptHandler(Console console, PrintStream out, AskPromptHandler nonInteractiveFallback) {
        this.console = console;
        this.out = Objects.requireNonNull(out, "out cannot be null");
        this.nonInteractiveFallback = Objects.requireNonNull(nonInteractiveFallback,
                "nonInteractiveFallback cannot be null");
    }

    @Override
    public Decision resolve(String prompt) {
        Objects.requireNonNull(prompt, "prompt cannot be null");
        if (console == null) {
            final Decision fallback = nonInteractiveFallback.resolve(prompt);
            log.info("Ask prompt resolved non-interactively (no console attached): decision={} prompt={}", fallback,
                    prompt);
            return fallback;
        }
        try {
            out.print("[ask] " + prompt + " [y/N]: ");
            out.flush();
            final String answer = console.readLine();
            if (answer == null) {
                log.info("Ask prompt received EOF; resolving to DENY. prompt={}", prompt);
                return Decision.DENY;
            }
            final String trimmed = answer.trim().toLowerCase(Locale.ROOT);
            if ("y".equals(trimmed) || "yes".equals(trimmed)) {
                return Decision.ALLOW;
            }
            return Decision.DENY;
        } catch (RuntimeException e) {
            log.warn("Ask prompt failed; resolving to DENY (fail-safe). prompt={}", prompt, e);
            return Decision.DENY;
        }
    }
}
