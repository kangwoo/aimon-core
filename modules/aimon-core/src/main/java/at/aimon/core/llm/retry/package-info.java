/**
 * Immutable policy value objects describing how LLM calls should be retried and how model fallback should be selected
 * when failures occur.
 *
 * <h2>Scope</h2>
 *
 * <p>
 * This package contains <strong>models only</strong>. It does not wrap, invoke, or otherwise depend on
 * {@link at.aimon.core.llm.LlmClient}. The client wrapping layer (a gateway that consumes these policies to drive
 * retries and fallbacks) is a separate task (RETRY-03) and lives in its own package.
 *
 * <p>
 * Keeping the policies decoupled from the client has two benefits:
 * <ul>
 * <li>Policies can be composed, serialised, and unit-tested in isolation from any provider SDK.
 * <li>The eventual gateway implementation can depend on the {@link at.aimon.core.llm.LlmClient} abstraction without
 * back-pressure from policy internals.
 * </ul>
 *
 * <h2>Core Types</h2>
 * <ul>
 * <li>{@link at.aimon.core.llm.retry.LlmRetryPolicy} — describes how many attempts a transient-error retry loop should
 * perform and how to compute the backoff delay between attempts.
 * <li>{@link at.aimon.core.llm.retry.LlmFallbackPolicy} — describes the ordered list of alternative
 * {@link at.aimon.core.llm.LlmModel} configurations the caller should try when the current model fails with an
 * activating exception.
 * </ul>
 *
 * <h2>Design Principles</h2>
 *
 * <p>
 * Both policies are immutable, thread-safe, and built via the builder pattern (see
 * {@code at.aimon.core.agent.AgentContent} for the reference style). Exception matching uses subclass assignment
 * ({@link java.lang.Class#isAssignableFrom(java.lang.Class)}) so registering a supertype implicitly matches every
 * subclass.
 *
 * <h2>Related Packages</h2>
 * <ul>
 * <li>{@link at.aimon.core.llm.exception} — the exception taxonomy these policies branch on.
 * <li>{@link at.aimon.core.llm} — the core {@link at.aimon.core.llm.LlmClient} and {@link at.aimon.core.llm.LlmModel}
 * abstractions.
 * </ul>
 *
 * @since 0.0.36
 */
package at.aimon.core.llm.retry;
