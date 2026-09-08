package at.aimon.core.llms.openai;

/**
 * Reports, at most once per distinct signature, that the provider is not sending a configured value as given.
 *
 * <p>
 * This exists so that the reporting behaviour stays in {@link OpenAILlmClient} while the code that <em>notices</em> a
 * divergence can live in a collaborator. Both matter: the once-only semantics live in a per-client set, and the WARN
 * has to be emitted on {@code OpenAILlmClient}'s own logger — that is the logger the divergence tests attach their
 * appender to, and the set is what makes "exactly one warning across two sends" true. A collaborator that reported for
 * itself would have neither.
 *
 * <p>
 * The single implementation is {@code OpenAILlmClient::reportDivergence}.
 */
@FunctionalInterface
interface OpenAIDivergenceReporter {

    /**
     * Reports one divergence.
     *
     * @param signature
     *            parameter, value and model — the key that decides whether this has already been said
     * @param message
     *            SLF4J-formatted message
     * @param args
     *            values for the message placeholders
     */
    void report(String signature, String message, Object... args);
}
