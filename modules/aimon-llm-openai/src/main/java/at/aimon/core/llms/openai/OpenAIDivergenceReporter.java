package at.aimon.core.llms.openai;

/**
 * Reports that the provider is not sending a configured value as given, or that the traffic lost something it should
 * have carried.
 *
 * <p>
 * This exists so that the reporting behaviour stays in {@link OpenAILlmClient} while the code that <em>notices</em> a
 * divergence can live in a collaborator. Both matter: the once-only semantics live in a per-client set, and the WARN
 * has to be emitted on {@code OpenAILlmClient}'s own logger — that is the logger the divergence tests attach their
 * appender to, and the set is what makes "exactly one warning across two sends" true. A collaborator that reported for
 * itself would have neither.
 *
 * <p>
 * Two implementations, chosen by the call site rather than by a flag: {@code OpenAILlmClient::reportDivergence} (once
 * per signature, for configuration facts) and {@code OpenAILlmClient::reportRecurringDivergence} (1st, 10th, 100th …
 * occurrence, for traffic facts such as a dropped reasoning trace). The collaborator does not need to know which one it
 * was handed.
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
