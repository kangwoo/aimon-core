package at.aimon.core.tools.memory;

import java.util.Objects;

import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolContextEnrichmentInfo;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * Wires the workspace, observer peer, and the current session id into the
 * {@link ToolContext} so {@link MemoryRecallTool} (and other memory-aware tools)
 * can locate the correct {@link at.aimon.core.memory.Representation}.
 *
 * <p>
 * The enricher is workspace-and-peer scoped: a single instance binds the agent
 * to one workspace and one observer identity (typically the agent itself). The
 * subject defaults to the same observer — recall on demand can override it via
 * a richer enricher when multi-peer scenarios appear.
 *
 * <p>
 * The session id is the <b>invoking</b> session where there is one, falling back to the run's own — so a new session
 * produces a new {@code memory.sessionId} without extra plumbing, while a subagent fork recalls against the session
 * that spawned it. Invoker-first is not a nicety: memory is only ever written under the id of a user-facing session
 * (the CLI derives it on REPL exit), so a fork keyed on its own identity would look up something that by construction
 * has never been written and {@code MemoryRecall(mode=LOCAL)} would miss every time. Reads only — nothing here writes
 * memory, so pointing a fork at the parent's session cannot merge fork state back into it.
 *
 * <p>
 * A run with neither id — a scheduled routine, an uninvoked fork — gets <b>no</b> {@code memory.sessionId} at all,
 * rather than the run's own identity dressed up as a session. That is the better answer of the two on this key's own
 * terms: absent, {@code MemoryRecall} matches local representations across sessions, which is a superset of what a
 * never-written id could ever return.
 *
 * <p>
 * Thread-safe and idempotent. Safe to register once per {@link OrcaAgentRuntime}
 * via the factory's {@code withToolContextEnrichers}.
 */
public final class MemoryToolContextEnricher implements ToolContextEnricher {

    private final Workspace workspace;
    private final PeerView observer;

    public MemoryToolContextEnricher(Workspace workspace, PeerView observer) {
        this.workspace = Objects.requireNonNull(workspace, "workspace cannot be null");
        this.observer = Objects.requireNonNull(observer, "observer cannot be null");
        if (!observer.getWorkspace().equals(workspace)) {
            throw new IllegalArgumentException("observer workspace (" + observer.getWorkspace().getId()
                    + ") does not match enricher workspace (" + workspace.getId() + ")");
        }
    }

    @Override
    public void enrich(ToolContext.Builder builder, ToolContextEnrichmentInfo info) {
        Objects.requireNonNull(builder, "builder cannot be null");
        Objects.requireNonNull(info, "info cannot be null");

        builder.put(MemoryRecallTool.WORKSPACE_KEY, workspace);
        builder.put(MemoryRecallTool.OBSERVER_KEY, observer);
        builder.put(MemoryRecallTool.SUBJECT_KEY, observer);
        info.getInvokingSessionId().or(info::getSessionId).map(SessionId::value)
                .ifPresent(id -> builder.put(MemoryRecallTool.SESSION_ID_KEY, id));
    }
}
