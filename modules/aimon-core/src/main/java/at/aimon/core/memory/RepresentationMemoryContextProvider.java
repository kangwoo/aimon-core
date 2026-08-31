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
 * Default {@link MemoryContextProvider} backed by a {@link RepresentationStore}.
 *
 * <p>
 * Resolution order on each {@link #provide(MemoryContextRequest)} call:
 *
 * <ol>
 * <li>Ask the {@link MemoryPeerResolver} whose memory this execution reads. No peer — an anonymous request under
 * {@link MemoryPeerResolver#caller()} — ends here with {@link Optional#empty()}.</li>
 * <li>Look up the latest LOCAL representation for {@code (peer, peer, sessionId)}, where {@code sessionId} is the
 * request's own. A session-less execution passes {@code null}, which the store reads as "representations recorded
 * without a session".</li>
 * <li>If absent, fall back to the latest GLOBAL representation for the peer.</li>
 * <li>If still absent, return {@link Optional#empty()} and the executor skips the prompt part.</li>
 * </ol>
 *
 * <p>
 * <b>Nothing about the scope is captured at construction.</b> One instance is agent-scoped and serves every session of
 * that agent; the session and the peer both arrive with the request. An earlier version took both as constructor
 * arguments, which meant a single-session process worked and every other deployment injected one peer's representation
 * into everyone's prompt. Note that fixing only the session id would not have closed it — the GLOBAL fallback in step 3
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
 * regardless of budget, {@code FULL} emits summary + observations and drops observations when the representation's
 * {@code tokenCount} exceeds {@code maxTokens} (positive). Set {@code maxTokens} to {@code 0} to disable budgeting.
 */
public final class RepresentationMemoryContextProvider implements MemoryContextProvider {

    private static final Logger log = LoggerFactory.getLogger(RepresentationMemoryContextProvider.class);

    private static final String PART_KIND = "memory";

    private final RepresentationStore representationStore;
    private final Workspace workspace;
    private final MemoryPeerResolver peerResolver;
    private final MemoryInjectionMode mode;
    private final int maxTokens;

    /** Guards the "no peer" notice so an unidentified transport reports itself once rather than once per execution. */
    private final AtomicBoolean unresolvedPeerReported = new AtomicBoolean();

    /**
     * Creates a provider.
     *
     * @param representationStore
     *            where representations are read from (must not be null)
     * @param workspace
     *            the tenant every resolved peer belongs to (must not be null)
     * @param peerResolver
     *            decides whose memory each execution reads (must not be null)
     * @param mode
     *            how a resolved representation is rendered (must not be null)
     * @param maxTokens
     *            observation budget for {@link MemoryInjectionMode#FULL}; {@code 0} disables budgeting
     * @throws NullPointerException
     *             if any reference argument is null
     * @throws IllegalArgumentException
     *             if {@code maxTokens} is negative
     */
    public RepresentationMemoryContextProvider(RepresentationStore representationStore, Workspace workspace,
            MemoryPeerResolver peerResolver, MemoryInjectionMode mode, int maxTokens) {
        this.representationStore = Objects.requireNonNull(representationStore, "representationStore cannot be null");
        this.workspace = Objects.requireNonNull(workspace, "workspace cannot be null");
        this.peerResolver = Objects.requireNonNull(peerResolver, "peerResolver cannot be null");
        this.mode = Objects.requireNonNull(mode, "mode cannot be null");
        if (maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens must be >= 0, got " + maxTokens);
        }
        this.maxTokens = maxTokens;
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

        Optional<Representation> latest = representationStore.findLatestLocal(subject, subject, sessionId);
        String resolvedScope = "LOCAL";
        if (latest.isEmpty()) {
            latest = representationStore.findLatestGlobal(subject);
            resolvedScope = "GLOBAL";
        }
        if (latest.isEmpty()) {
            log.debug("Memory context absent: subject={}, sessionId={}", subject.key(), sessionId);
            return Optional.empty();
        }
        String content = render(latest.get(), mode, maxTokens);
        log.debug("Memory context resolved: subject={}, scope={}, mode={}, contentChars={}", subject.key(),
                resolvedScope, mode, content.length());
        SystemPromptPart part = SystemPromptPart.builder().content(content).staticness(Staticness.DYNAMIC)
                .kind(PART_KIND).build();
        return Optional.of(part);
    }

    /**
     * Says once that executions are arriving without a peer, and then stays quiet.
     *
     * <p>
     * It is worth saying at all: an operator who configured a memory backend and sees no memory in any prompt has no
     * other signal, because "no peer" and "no representation yet" both look like a prompt with no memory block. It is
     * worth saying only once because the cause is a transport that never attaches an identity, so the second line
     * carries no information the first did not — and it would arrive on every execution, forever.
     */
    private void reportUnresolvedPeerOnce() {
        if (unresolvedPeerReported.compareAndSet(false, true)) {
            log.warn("Memory context skipped: no peer resolved for this execution, so no representation can be read."
                    + " Under MemoryPeerResolver.caller() this means the transport attached no principal;"
                    + " configure MemoryPeerResolver.fixed(...) for a single-peer process."
                    + " Reported once per provider.");
        }
    }

    static String render(Representation rep, MemoryInjectionMode mode, int maxTokens) {
        boolean includeObservations = mode == MemoryInjectionMode.FULL
                && (maxTokens == 0 || rep.getTokenCount() <= maxTokens);
        boolean overBudget = mode == MemoryInjectionMode.FULL && maxTokens > 0 && rep.getTokenCount() > maxTokens;

        StringBuilder out = new StringBuilder(256);
        out.append("Known about ").append(rep.getSubject().key()).append('\n');
        out.append("scope: ").append(rep.isGlobal() ? "global" : "local").append('\n');
        out.append("generatedAt: ").append(rep.getGeneratedAt()).append('\n');
        out.append("tokenCount: ").append(rep.getTokenCount());
        if (overBudget) {
            out.append(" (over budget=").append(maxTokens).append(", observations omitted)");
        }
        out.append('\n').append('\n');
        out.append("Summary:\n");
        out.append(rep.getSummary().isEmpty() ? "(empty)" : rep.getSummary()).append('\n');

        if (includeObservations && !rep.getObservations().isEmpty()) {
            out.append('\n');
            out.append("Observations (").append(rep.getObservations().size()).append("):\n");
            for (Observation obs : rep.getObservations()) {
                out.append("- [").append(obs.getId().getLocalId()).append("] ").append(obs.getContent())
                        .append(" (confidence=").append(String.format(Locale.ROOT, "%.2f", obs.getConfidence()))
                        .append(")\n");
            }
        }
        return out.toString();
    }
}
