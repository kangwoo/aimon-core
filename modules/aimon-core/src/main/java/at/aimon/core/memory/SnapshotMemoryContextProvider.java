package at.aimon.core.memory;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.prompt.Staticness;
import at.aimon.core.agent.prompt.SystemPromptPart;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * Default {@link MemoryContextProvider}, backed by the {@link MemoryCapability#SNAPSHOT} tier.
 *
 * <p>
 * Resolution order on each {@link #provide(MemoryContextRequest)} call:
 *
 * <ol>
 * <li>Ask the {@link MemoryPeerResolver} whose memory this execution reads. No peer — an anonymous request under
 * {@link MemoryPeerResolver#caller()} — ends here with {@link Optional#empty()}.</li>
 * <li>Ask the {@link MemorySnapshotReader} for the peer's snapshot under {@link MemorySnapshotScope#LOCAL_THEN_GLOBAL}:
 * the local view for {@code (peer, peer, sessionId)} if there is one, the global portrait otherwise. A session-less
 * execution passes {@code null}, which the default backend reads as "recorded without a session".</li>
 * <li>If the backend holds nothing, return {@link Optional#empty()} and the executor skips the prompt part.</li>
 * </ol>
 *
 * <p>
 * <b>It stands on the tier, not on a store.</b> The previous name — {@code RepresentationMemoryContextProvider} — said
 * it read {@link Representation}s, and a remote memory backend has no such type: it returns rendered prose and a few
 * flags. A class named after a type that need not exist is a class whose name is wrong for every backend but one, so
 * the name follows the tier instead. Behaviour is unchanged for the default backend.
 *
 * <p>
 * <b>Nothing about the scope is captured at construction.</b> One instance is agent-scoped and serves every session of
 * that agent; the session and the peer both arrive with the request. An earlier version took both as constructor
 * arguments, which meant a single-session process worked and every other deployment injected one peer's representation
 * into everyone's prompt. Note that fixing only the session id would not have closed it — the GLOBAL fallback in step 2
 * is keyed on the subject alone, so a frozen peer leaks through it whatever the session says.
 *
 * <p>
 * Subject and observer are the same peer, so the "same workspace" invariant the constructor used to check is now
 * structural. When they need to diverge — an agent holding a view of a peer other than the caller — the distinction
 * belongs on {@link MemoryContextRequest}, which every session already passes, and not back on the constructor, which
 * none of them can vary.
 *
 * <p>
 * Rendering depends on {@link MemoryInjectionMode}: {@code SUMMARY_ONLY} (default) emits a compact summary block
 * regardless of budget, {@code FULL} emits summary + observations and drops observations when the snapshot's
 * {@code tokenCount} exceeds {@code maxTokens} (positive). Set {@code maxTokens} to {@code 0} to disable budgeting.
 */
public final class SnapshotMemoryContextProvider implements MemoryContextProvider {

    private static final Logger log = LoggerFactory.getLogger(SnapshotMemoryContextProvider.class);

    private static final String PART_KIND = "memory";

    private final MemorySnapshotReader snapshotReader;
    private final Workspace workspace;
    private final MemoryPeerResolver peerResolver;
    private final MemoryInjectionMode mode;
    private final int maxTokens;

    /** Guards the "no peer" notice so an unidentified transport reports itself once rather than once per execution. */
    private final AtomicBoolean unresolvedPeerReported = new AtomicBoolean();

    /**
     * Creates a provider.
     *
     * @param snapshotReader
     *            the tier snapshots are read from (must not be null)
     * @param workspace
     *            the tenant every resolved peer belongs to (must not be null)
     * @param peerResolver
     *            decides whose memory each execution reads (must not be null)
     * @param mode
     *            how a resolved snapshot is rendered (must not be null)
     * @param maxTokens
     *            observation budget for {@link MemoryInjectionMode#FULL}; {@code 0} disables budgeting
     * @throws NullPointerException
     *             if any reference argument is null
     * @throws IllegalArgumentException
     *             if {@code maxTokens} is negative
     */
    public SnapshotMemoryContextProvider(MemorySnapshotReader snapshotReader, Workspace workspace,
            MemoryPeerResolver peerResolver, MemoryInjectionMode mode, int maxTokens) {
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader cannot be null");
        this.workspace = Objects.requireNonNull(workspace, "workspace cannot be null");
        this.peerResolver = Objects.requireNonNull(peerResolver, "peerResolver cannot be null");
        this.mode = Objects.requireNonNull(mode, "mode cannot be null");
        if (maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens must be >= 0, got " + maxTokens);
        }
        this.maxTokens = maxTokens;
    }

    /**
     * Builds the SNAPSHOT tier over a {@link RepresentationStore}, for callers assembling the default backend by hand.
     *
     * <p>
     * A second constructor taking the store directly would be ambiguous with the one above for any caller passing a
     * {@code null} — and the two overloads would have said the store and the tier are interchangeable, which is the
     * one thing this rename exists to deny. A named factory keeps the constructor single and the altitude explicit.
     *
     * @param representationStore
     *            where representations are read from (must not be null)
     * @return a reader over that store
     */
    public static MemorySnapshotReader readerOver(RepresentationStore representationStore) {
        Objects.requireNonNull(representationStore, "representationStore cannot be null");
        return StoreBackedPeerMemory.builder().representationStore(representationStore).build().snapshotReader()
                .orElseThrow();
    }

    @Override
    public Optional<SystemPromptPart> provide(MemoryContextRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        final Optional<Principal> peer = peerResolver.resolve(request);
        if (peer.isEmpty()) {
            reportUnresolvedPeerOnce();
            return Optional.empty();
        }

        final PeerView subject = PeerView.of(workspace, peer.get());
        final String sessionId = request.getSessionId().map(SessionId::value).orElse(null);

        final Optional<MemorySnapshot> latest = snapshotReader
                .read(MemorySnapshotQuery.builder().subject(subject).observer(subject).sessionId(sessionId)
                        .scope(MemorySnapshotScope.LOCAL_THEN_GLOBAL).mode(mode).maxTokens(maxTokens).build());
        if (latest.isEmpty()) {
            log.debug("Memory context absent: subject={}, sessionId={}", subject.key(), sessionId);
            return Optional.empty();
        }
        String content = render(subject, latest.get(), maxTokens);
        log.debug("Memory context resolved: subject={}, scope={}, mode={}, contentChars={}", subject.key(),
                latest.get().getResolvedScope(), mode, content.length());
        SystemPromptPart part = SystemPromptPart.builder().content(content).staticness(Staticness.DYNAMIC)
                .kind(PART_KIND).build();
        return Optional.of(part);
    }

    /**
     * Says once that executions are arriving without a peer, and then stays quiet.
     *
     * <p>
     * It is worth saying at all: an operator who configured a memory backend and sees no memory in any prompt has no
     * other signal, because "no peer" and "no snapshot yet" both look like a prompt with no memory block. It is worth
     * saying only once because the cause is a transport that never attaches an identity, so the second line carries no
     * information the first did not — and it would arrive on every execution, forever.
     */
    private void reportUnresolvedPeerOnce() {
        if (unresolvedPeerReported.compareAndSet(false, true)) {
            log.warn("Memory context skipped: no peer resolved for this execution, so no snapshot can be read."
                    + " Under MemoryPeerResolver.caller() this means the transport attached no principal;"
                    + " configure MemoryPeerResolver.fixed(...) for a single-peer process."
                    + " Reported once per provider.");
        }
    }

    static String render(PeerView subject, MemorySnapshot snapshot, int maxTokens) {
        StringBuilder out = new StringBuilder(256);
        out.append("Known about ").append(subject.key()).append('\n');
        out.append("scope: ").append(snapshot.getResolvedScope() == MemorySnapshotScope.GLOBAL ? "global" : "local")
                .append('\n');
        out.append("generatedAt: ").append(snapshot.getGeneratedAt()).append('\n');
        out.append("tokenCount: ").append(snapshot.getTokenCount());
        if (snapshot.isTruncated()) {
            out.append(" (over budget=").append(maxTokens).append(", observations omitted)");
        }
        out.append('\n').append('\n');
        out.append("Summary:\n");
        out.append(snapshot.getRenderedText().isEmpty() ? "(empty)" : snapshot.getRenderedText()).append('\n');

        if (!snapshot.getObservations().isEmpty()) {
            out.append('\n');
            out.append("Observations (").append(snapshot.getObservations().size()).append("):\n");
            for (Observation obs : snapshot.getObservations()) {
                out.append("- [").append(obs.getId().getLocalId()).append("] ").append(obs.getContent());
                if (snapshot.isConfidenceAvailable()) {
                    out.append(" (confidence=").append(String.format(Locale.ROOT, "%.2f", obs.getConfidence()))
                            .append(")");
                }
                out.append('\n');
            }
        }
        return out.toString();
    }
}
