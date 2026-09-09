package at.aimon.core.llms.anthropic;

/**
 * Reports, at most once per distinct signature, that the provider is not sending a configured value as given.
 *
 * <p>
 * This exists so that the reporting behaviour stays in {@link AnthropicLlmClient} while the code that <em>notices</em>
 * a divergence can live in a collaborator. Both halves matter: the once-only semantics live in a per-client set, and
 * the WARN has to be emitted on {@code AnthropicLlmClient}'s own logger — that is the logger the divergence tests
 * attach their appender to, and the set is what makes "exactly one warning across two sends" true. A collaborator that
 * reported for itself would have neither.
 *
 * <p>
 * The single production implementation is {@code AnthropicLlmClient::reportDivergence}.
 */
@FunctionalInterface
interface AnthropicDivergenceReporter {

    /**
     * Reports one divergence.
     *
     * @param signature
     *            parameter and value — the key that decides whether this has already been said
     * @param message
     *            SLF4J-formatted message
     * @param args
     *            values for the message placeholders
     */
    void report(String signature, String message, Object... args);
}
