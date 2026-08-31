package at.aimon.core.memory;

import java.util.Optional;

import at.aimon.core.agent.prompt.SystemPromptPart;

/**
 * Contributes a memory-derived {@link SystemPromptPart} to the system prompt of every agent execution — the AIMON
 * counterpart to Honcho's session-context auto-injection.
 *
 * <p>
 * Implementations are consulted by the agent executor while it assembles the structured system prompt. They look up
 * the latest {@link Representation} for the peer scope the request resolves to and decide whether to contribute a part
 * for this execution. Returning {@link Optional#empty()} means "no relevant memory available" and the executor skips
 * the part without altering the rest of the prompt.
 *
 * <p>
 * <b>The scope arrives with the call, not with the constructor.</b> A provider is agent-scoped and outlives every
 * session that uses it, so an instance that captured a session id or a peer at construction time would inject that one
 * peer's representation into every session — see {@link MemoryContextRequest} for the shape of the identity that is
 * passed instead, and {@link MemoryPeerResolver} for how a peer is derived from it.
 *
 * <p>
 * Implementations must be thread-safe and side-effect free: {@link #provide(MemoryContextRequest)} can be called
 * concurrently by the executor and must not mutate observable state.
 */
@FunctionalInterface
public interface MemoryContextProvider {

    /**
     * Returns the memory-derived part to inject for the upcoming LLM call, or {@link Optional#empty()} when no
     * representation is available for the scope this request resolves to.
     *
     * @param request
     *            the identity of the execution being assembled (must not be null)
     * @return an optional non-empty {@link SystemPromptPart}; never {@code null}
     */
    Optional<SystemPromptPart> provide(MemoryContextRequest request);
}
