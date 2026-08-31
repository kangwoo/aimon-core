/**
 * Cost estimation and per-model usage aggregation for LLM calls.
 *
 * <p>
 * This package layers a small, provider-agnostic pricing SPI on top of the existing token-metering types in
 * {@link at.aimon.core.llm}. It turns raw {@link at.aimon.core.llm.TokenUsage token counts} into monetary
 * {@link at.aimon.core.llm.cost.Money amounts} using a configurable {@link at.aimon.core.llm.cost.ModelPriceTable
 * price table}, and accumulates the result per model into an immutable {@link at.aimon.core.llm.cost.CostSummary}.
 *
 * <p>
 * The types here are pure value objects and stateless strategies: they depend only on {@code at.aimon.core.llm},
 * {@code at.aimon.core.base}, and the Java standard library, so they introduce no cycles and can be reused by any
 * caller that already meters token usage (executor loop, {@code MeteringLlmClient}, offline reporting, ...).
 *
 * <p>
 * Everything is opt-in. The framework default {@link at.aimon.core.llm.cost.CostEstimator#NOOP} prices every call at
 * {@link at.aimon.core.llm.cost.Money#zeroUsd() zero}, so wiring nothing leaves behaviour unchanged.
 */
package at.aimon.core.llm.cost;
