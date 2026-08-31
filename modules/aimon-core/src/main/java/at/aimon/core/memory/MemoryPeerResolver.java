package at.aimon.core.memory;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.base.Principal;

/**
 * Decides whose memory an execution reads — the peer a {@link MemoryContextProvider} looks up for a given
 * {@link MemoryContextRequest}.
 *
 * <h2>Why this is a strategy and not a nullable default</h2>
 *
 * <p>
 * There are two coherent deployments, and they answer "who is the peer here?" differently:
 *
 * <ul>
 * <li>A <b>single-peer process</b> — the CLI — has one configured identity. Everything written to the representation
 * store was written under it, so everything read must be read under it too. The caller identity on a request, if any,
 * is not the peer.
 * <li>A <b>multi-caller server</b> has one identity per request, and reading under any other would hand one user's
 * representation to another.
 * </ul>
 *
 * <p>
 * Written as {@code request.principal() orElse configuredPeer} — the obvious spelling — those two collapse into a
 * third arrangement that is neither: identified turns read one peer's memory while anonymous turns read another's,
 * against a store written entirely under the second. Nothing fails; the answers are just quietly wrong for half the
 * traffic. Naming the two modes keeps that state unreachable.
 *
 * <p>
 * Returning {@link Optional#empty()} means "no peer for this execution", and the provider contributes no memory part
 * at all. That is the correct answer for an anonymous request under {@link #caller()}: a shared fallback peer would be
 * a cross-user read.
 *
 * <p>
 * Implementations must be thread-safe and side-effect free — they are called during prompt assembly, concurrently.
 */
@FunctionalInterface
public interface MemoryPeerResolver {

    /**
     * Resolves the peer whose memory this execution should read.
     *
     * @param request
     *            the execution's identity (must not be null)
     * @return the peer, or {@link Optional#empty()} when this execution has none
     */
    Optional<Principal> resolve(MemoryContextRequest request);

    /**
     * Always resolves to {@code peer}, whatever the request says.
     *
     * <p>
     * The single-peer mode: use it when one identity owns everything in the representation store, as in a CLI process
     * configured with a single {@code peerId}.
     *
     * @param peer
     *            the process-wide peer (must not be null)
     * @return a resolver that answers {@code peer} for every request
     * @throws NullPointerException
     *             if {@code peer} is null
     */
    static MemoryPeerResolver fixed(Principal peer) {
        Objects.requireNonNull(peer, "peer cannot be null");
        return request -> Optional.of(peer);
    }

    /**
     * Resolves to the identity the transport attached to the request, and to nothing when it attached none.
     *
     * <p>
     * The multi-caller mode. Anonymous executions — including subagent forks, which carry no principal — read no
     * memory under this resolver, which is the only safe answer when the store holds more than one peer.
     *
     * @return a resolver that answers the request's own principal
     */
    static MemoryPeerResolver caller() {
        return MemoryContextRequest::getPrincipal;
    }
}
