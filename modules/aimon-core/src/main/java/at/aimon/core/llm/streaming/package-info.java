/**
 * Provider-neutral streaming primitives for partial LLM text delivery.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides the low-level abstractions that provider modules (e.g.,
 * {@code aimon-llm-openai}, {@code aimon-llm-anthropic}) use to publish partial LLM responses
 * in a provider-agnostic way. Upper layers ({@link at.aimon.core.llm.invoke.LlmCallGateway} and
 * {@link at.aimon.core.agent.impl.orca.OrcaAgentExecutor}) consume {@link at.aimon.core.llm.streaming.LlmStreamChunk}
 * instances without knowing which provider produced them.
 *
 * <h2>Core Components</h2>
 * <ul>
 * <li>{@link at.aimon.core.llm.streaming.LlmStreamChunk} — immutable, provider-neutral chunk model
 * (text delta or stream-end marker).</li>
 * <li>{@link at.aimon.core.llm.streaming.LlmStreamSink} — functional callback consumed by provider
 * clients to publish chunks.</li>
 * <li>{@link at.aimon.core.llm.streaming.ChunkAggregator} — accumulates text deltas and tool-call
 * partial JSON into a final {@link at.aimon.core.llm.LlmResponse}, and exposes a thread-safe
 * {@code peekText()} for callers that need the partial text on cancellation.</li>
 * <li>{@link at.aimon.core.llm.streaming.LlmStreamingOptions} — caller-side knobs (buffering
 * during retries, request usage from provider).</li>
 * <li>{@link at.aimon.core.llm.streaming.BufferingStreamSink} — sink decorator used by the gateway
 * when {@code bufferUntilFirstSuccess=true} to isolate chunks per attempt.</li>
 * </ul>
 *
 * <h2>Design Principles</h2>
 * <ul>
 * <li><b>Provider-neutral</b> — SDK types (OpenAI {@code ChatCompletionChunk}, Anthropic
 * {@code MessageStreamEvent}, …) never leak out of their provider modules. The chunk model
 * is intentionally minimal: text delta, stream end, optional usage / finish reason.</li>
 * <li><b>Opt-in</b> — the streaming path is a default-method overload on
 * {@link at.aimon.core.llm.LlmClient}, so providers that do not support streaming still work
 * via a one-chunk fallback derived from {@code sendMessage(…)}.</li>
 * <li><b>No external dependencies</b> — only {@code java.util.concurrent} primitives. Reactive
 * integrations (Reactor, RxJava, Mutiny) are out of scope; callers that need them can wrap the
 * sink with their own adapter.</li>
 * <li><b>Immutable values</b> — chunk and options are {@code final} classes with builders; the
 * aggregator is the only stateful component and guards {@code peekText()} with locking.</li>
 * </ul>
 *
 * <h2>Relation to {@code at.aimon.core.agent.stream}</h2>
 *
 * <p>
 * This package covers the <b>wire-level</b> abstraction between provider clients and the LLM
 * gateway. The agent execution event layer ({@code at.aimon.core.agent.stream}) is an entirely
 * separate concern: it publishes high-level {@code AssistantTextDelta} / {@code ...Reset} /
 * {@code ...Completed} events to subscribers (REPL, SDK consumers). The Orca executor is the
 * single place where these two layers meet.
 *
 * @since 0.0.37
 */
package at.aimon.core.llm.streaming;
