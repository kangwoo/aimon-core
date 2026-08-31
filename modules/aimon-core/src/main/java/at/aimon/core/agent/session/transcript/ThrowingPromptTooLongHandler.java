package at.aimon.core.agent.session.transcript;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.invoke.HandlerOutcome;
import at.aimon.core.llm.invoke.PromptTooLongEvent;
import at.aimon.core.llm.invoke.PromptTooLongHandler;

/**
 * Default {@link PromptTooLongHandler} that preserves pre-compaction behaviour by rethrowing the triggering
 * {@link LlmPromptTooLongException} unchanged.
 *
 * <h2>Purpose</h2>
 *
 * <p>
 * This handler is the opt-in "do-nothing" default for callers that have not yet wired a real compaction strategy. It
 * satisfies the {@link PromptTooLongHandler} contract by propagating the original exception, which causes the enclosing
 * {@link at.aimon.core.llm.invoke.LlmCallGateway} to fail the call exactly as it would if no handler had been installed
 * at all. The cause chain of the original exception is preserved because the event-provided instance is rethrown
 * verbatim rather than wrapped.
 *
 * <h2>Scope (RETRY-05)</h2>
 *
 * <p>
 * RETRY-05 introduces only this stub. Wiring the handler into the gateway default or into Orca is intentionally left to
 * RETRY-04; this class does not install itself anywhere. The real compaction-driven handler (tentatively named
 * {@code CompactionTriggeringHandler}) is tracked in the transcript-compaction design document and will supersede
 * this class once that work lands.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * {@code
 * LlmCallGateway<TranscriptBuffer> gateway =
 *         LlmCallGateway.<TranscriptBuffer>builder()
 *                 .client(client)
 *                 .retryPolicy(retry)
 *                 .fallbackPolicy(fallback)
 *                 .promptTooLongHandler(ThrowingPromptTooLongHandler.INSTANCE)
 *                 .build();
 * }
 * </pre>
 *
 * <h2>Threading</h2>
 *
 * <p>
 * The handler is stateless and immutable, so the shared {@link #INSTANCE} is safe to reuse across threads and gateways.
 * Callers should prefer {@link #INSTANCE} over constructing new objects.
 */
public final class ThrowingPromptTooLongHandler implements PromptTooLongHandler<TranscriptBuffer> {

    /**
     * Shared singleton instance. Safe to reuse across threads because the handler is stateless.
     */
    public static final ThrowingPromptTooLongHandler INSTANCE = new ThrowingPromptTooLongHandler();

    private static final Logger log = LoggerFactory.getLogger(ThrowingPromptTooLongHandler.class);

    private ThrowingPromptTooLongHandler() {
    }

    /**
     * Logs the event and rethrows the captured {@link LlmPromptTooLongException} verbatim.
     *
     * <p>
     * The handler never inspects or mutates the memory snapshot, which keeps it safe to use with callers that do not
     * attach one.
     *
     * @param event
     *            the event describing the failing call (must not be {@code null})
     * @return this method never returns normally
     * @throws LlmClientException
     *             always — the triggering {@link LlmPromptTooLongException} is rethrown verbatim so its cause chain is
     *             preserved
     */
    @Override
    public Optional<HandlerOutcome> handle(PromptTooLongEvent<TranscriptBuffer> event) throws LlmClientException {
        Objects.requireNonNull(event, "event must not be null");
        log.info("prompt too long — no compaction handler configured, re-throwing. attempt={}, model={}",
                event.getAttempt(), event.getModel());
        throw event.getException();
    }
}
