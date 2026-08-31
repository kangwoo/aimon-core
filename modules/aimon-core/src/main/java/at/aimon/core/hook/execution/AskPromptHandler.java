package at.aimon.core.hook.execution;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a {@link Decision#ASK} hook result into a concrete {@code allow}/{@code deny} decision.
 *
 * <p>
 * The dispatcher invokes the handler when a pre-tool hook returns an {@code ask} result. Implementations decide whether
 * to interactively prompt the user (CLI), call out to an external policy service, or fall back to an environment-driven
 * default. The handler MUST return either {@link Decision#ALLOW} or {@link Decision#DENY} &mdash; returning
 * {@link Decision#ASK} again is treated as {@link Decision#DENY} by the dispatcher.
 *
 * <p>
 * Implementations must be thread-safe.
 */
@FunctionalInterface
public interface AskPromptHandler {

    /** Environment variable consulted by {@link #fromEnv(Map)} for the default decision. */
    String ASK_DEFAULT_ENV = "AIMON_HOOK_ASK_DEFAULT";

    /**
     * Resolves the ask prompt to a final decision.
     *
     * @param prompt
     *            the human-readable reason the hook supplied (must not be null; usually the {@code ask(reason)}
     *            argument)
     * @return {@link Decision#ALLOW} or {@link Decision#DENY} (never null; never {@link Decision#ASK})
     */
    Decision resolve(String prompt);

    /**
     * Always denies. Suitable for non-interactive environments that have not opted into a different default.
     *
     * @return a handler that returns {@link Decision#DENY} for every prompt
     */
    static AskPromptHandler denyAll() {
        return prompt -> Decision.DENY;
    }

    /**
     * Always allows. Use only in trusted environments (e.g. controlled CI for known-good skills).
     *
     * @return a handler that returns {@link Decision#ALLOW} for every prompt
     */
    static AskPromptHandler allowAll() {
        return prompt -> Decision.ALLOW;
    }

    /**
     * Builds a handler from a process-env snapshot using {@value #ASK_DEFAULT_ENV}.
     *
     * <p>
     * The supported values are {@code "allow"} and {@code "deny"} (case-insensitive). Any other value &mdash; including
     * the variable being absent &mdash; resolves to {@link Decision#DENY} (fail-safe default).
     *
     * @param env
     *            process-env snapshot (must not be null; typically {@code System.getenv()})
     * @return a handler that returns the resolved decision for every prompt
     */
    static AskPromptHandler fromEnv(Map<String, String> env) {
        Objects.requireNonNull(env, "env cannot be null");
        final String raw = env.getOrDefault(ASK_DEFAULT_ENV, "");
        final Decision fallback = "allow".equals(raw.toLowerCase(Locale.ROOT)) ? Decision.ALLOW : Decision.DENY;
        return prompt -> fallback;
    }
}
