package at.aimon.bootstrap.assemble;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.bootstrap.runtime.AgentRuntimeLease;
import at.aimon.bootstrap.runtime.AgentRuntimeResolver;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.OpenAttributes;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.session.routing.LiveSessionOpener;

/**
 * Opens a {@link LiveSession} against a runtime the stack has <b>already</b> stood up.
 *
 * <p>
 * This exists rather than the stock {@code LiveSessionFactory} because that factory builds the 4-argument
 * {@link DefaultLiveSession}, leaving the message queue, the hook execution manager and the session record
 * store null. Each null silently removes a behaviour a server deployment needs:
 *
 * <ul>
 * <li>no queue — a second input arriving mid-turn is rejected instead of enqueued;
 * <li>no hook manager — {@code OnSessionStart} / {@code OnSessionEnd} hooks never fire;
 * <li>no record store — {@code sessionTotals} and a runtime budget override are lost on eviction, so a resumed
 * session restarts its counters from zero.
 * </ul>
 *
 * <p>
 * All three are wired here.
 *
 * <h2>It never creates a runtime — it asks the one thing that may</h2>
 *
 * <p>
 * The opener contract is explicit that an implementation must not create a fresh {@code AgentRuntime} per open
 * call, and the reason is the asymmetry at the heart of the scope model: one session record may be served by
 * many live handles over time (idle-TTL eviction, restart, cross-node handoff). A runtime minted per open would
 * be minted per eviction — each with its own tool registry, hook registry and MCP client manager, none of them
 * ever closed.
 *
 * <p>
 * So this class holds no factory and mints nothing. It asks the {@link AgentRuntimeResolver}, which answers a
 * configured agent from the registry and is the only component allowed to build a tenant runtime — once, on
 * first use, shared by every later open. An agent the stack was never configured with is still an error there:
 * inventing one on the spot would hide a configuration bug behind an agent that answers with the wrong tools.
 *
 * <p>
 * What comes back is a {@link AgentRuntimeLease}, and the handle is wrapped so that closing it releases the
 * lease. Between turns a cached handle looks idle to any timer, so without that the resolver could reclaim a
 * runtime a warm session is about to use.
 */
public final class StackLiveSessionOpener implements LiveSessionOpener {

    private static final Logger log = LoggerFactory.getLogger(StackLiveSessionOpener.class);

    private final AgentRuntimeResolver agentRuntimeResolver;
    private final OrcaAgentExecutor agentExecutor;
    private final MessageQueueManager messageQueueManager;
    private final HookExecutionManager hookExecutionManager;
    private final SessionRecordStore sessionRecordStore;

    /**
     * Creates an opener over an already-assembled stack.
     *
     * @param agentRuntimeResolver
     *            resolves runtime ids to runtimes, creating tenant ones on first use (must not be null)
     * @param agentExecutor
     *            the shared executor every session runs turns through (must not be null)
     * @param messageQueueManager
     *            backs auto-enqueue of inputs that arrive mid-turn (must not be null)
     * @param hookExecutionManager
     *            fires the session lifecycle hooks (must not be null)
     * @param sessionRecordStore
     *            holds the durable per-session state the handle hydrates from and writes back to (must not be
     *            null)
     */
    public StackLiveSessionOpener(AgentRuntimeResolver agentRuntimeResolver, OrcaAgentExecutor agentExecutor,
            MessageQueueManager messageQueueManager, HookExecutionManager hookExecutionManager,
            SessionRecordStore sessionRecordStore) {
        this.agentRuntimeResolver = Objects.requireNonNull(agentRuntimeResolver,
                "agentRuntimeResolver must not be null");
        this.agentExecutor = Objects.requireNonNull(agentExecutor, "agentExecutor must not be null");
        this.messageQueueManager = Objects.requireNonNull(messageQueueManager, "messageQueueManager must not be null");
        this.hookExecutionManager = Objects.requireNonNull(hookExecutionManager,
                "hookExecutionManager must not be null");
        this.sessionRecordStore = Objects.requireNonNull(sessionRecordStore, "sessionRecordStore must not be null");
    }

    @Override
    public LiveSession open(SessionId sessionId, AgentRuntimeId agentRuntimeId, LiveSessionOptions options,
            OpenAttributes attributes) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId must not be null");
        Objects.requireNonNull(options, "options must not be null");

        final AgentRuntimeLease lease = agentRuntimeResolver.acquire(agentRuntimeId);
        try {
            if (!(lease.runtime() instanceof OrcaAgentRuntime runtime)) {
                throw new IllegalStateException("The runtime registered for " + agentRuntimeId + " is a "
                        + lease.runtime().getClass().getName() + ", but this opener builds sessions on "
                        + OrcaAgentRuntime.class.getSimpleName() + ".");
            }
            log.debug("Opening live session {} on runtime {}", sessionId, agentRuntimeId);
            final DefaultLiveSession session = new DefaultLiveSession(sessionId, runtime, agentExecutor, options,
                    messageQueueManager, hookExecutionManager, sessionRecordStore);
            return new LeasedLiveSession(session, lease);
        } catch (RuntimeException e) {
            // Nothing else will ever close this handle, so the lease has to come back here or the runtime is
            // pinned until the process exits.
            lease.close();
            throw e;
        }
    }
}
