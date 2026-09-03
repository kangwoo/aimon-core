package at.aimon.core.memory;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * Default {@link ExecutionMemorySink}: resolves the peer, then hands the execution's messages to the
 * {@link MemoryCapability#INGEST} tier.
 *
 * <p>
 * Peer resolution goes through {@link MemoryPeerResolver}, the same strategy the read seam uses, so the two seams
 * cannot end up answering for different peers. Under {@link MemoryPeerResolver#fixed} that is the configured peer;
 * under {@link MemoryPeerResolver#caller()} it is nothing at all, because the seam carries no principal — which is why
 * a per-caller deployment has no ingest and the assembly records that rather than leaving it to be discovered.
 *
 * <h2>What is dropped, and why each is dropped rather than guessed</h2>
 *
 * <ul>
 * <li><b>No messages.</b> Either the execution added nothing, or its history was rewritten underneath it and no delta
 * could be taken. Both mean "nothing to say"; see
 * {@link at.aimon.core.agent.session.transcript.TranscriptBuffer#messagesSinceIngestMark()}.
 * <li><b>No peer.</b> Nothing to attribute the conversation to. Attributing it to a default peer would put one
 * caller's conversation in another's memory.
 * <li><b>No session.</b> A session-less execution — a subagent fork, a skill fork — has no session name to file the
 * messages under, and no guarantee that a fork's conversation is a fact about the parent's peer at all. Fork ingest is
 * off, and turning it on is a change to what a fork means, not a flag.
 * </ul>
 *
 * <p>
 * Failures are swallowed with a warning. An execution's answer is not worth losing because a memory backend is down.
 */
public final class IngestingExecutionMemorySink implements ExecutionMemorySink {

    private static final Logger log = LoggerFactory.getLogger(IngestingExecutionMemorySink.class);

    private final MemoryIngestor ingestor;
    private final Workspace workspace;
    private final MemoryPeerResolver peerResolver;

    /**
     * Creates a sink.
     *
     * @param ingestor
     *            the tier messages are fed to — already wrapped for redaction by the assembly (must not be null)
     * @param workspace
     *            the tenant every resolved peer belongs to (must not be null)
     * @param peerResolver
     *            decides whose memory an execution is attributed to (must not be null)
     */
    public IngestingExecutionMemorySink(MemoryIngestor ingestor, Workspace workspace, MemoryPeerResolver peerResolver) {
        this.ingestor = Objects.requireNonNull(ingestor, "ingestor cannot be null");
        this.workspace = Objects.requireNonNull(workspace, "workspace cannot be null");
        this.peerResolver = Objects.requireNonNull(peerResolver, "peerResolver cannot be null");
    }

    @Override
    public void afterExecution(ExecutionMemoryUpdate update) {
        Objects.requireNonNull(update, "update cannot be null");
        if (update.getMessages().isEmpty()) {
            return;
        }
        final Optional<SessionId> sessionId = update.getSessionId();
        if (sessionId.isEmpty()) {
            log.debug("Memory ingest skipped: session-less execution has no session to file {} message(s) under",
                    update.getMessages().size());
            return;
        }
        final Optional<Principal> peer = peerResolver.resolve(MemoryContextRequest.builder().sessionId(sessionId.get())
                .principal(update.getPrincipal().orElse(null)).build());
        if (peer.isEmpty()) {
            log.debug("Memory ingest skipped: no peer resolved for this execution");
            return;
        }
        try {
            final MemoryIngestReceipt receipt = ingestor
                    .ingest(MemoryIngestRequest.builder().observer(PeerView.of(workspace, peer.get()))
                            .sessionId(sessionId.get().value()).messages(update.getMessages()).build());
            log.debug("Memory ingest: session={} accepted={} derived={}", sessionId.get().value(),
                    receipt.getAccepted(), receipt.isDerived());
        } catch (RuntimeException e) {
            // Fire-and-forget: the execution has already produced its answer, and losing it because a memory backend
            // is unreachable would be the wrong trade in every deployment.
            log.warn("Memory ingest failed for session {}: {}", sessionId.get().value(), e.getMessage());
        }
    }
}
