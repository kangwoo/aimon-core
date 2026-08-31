package at.aimon.core.tools.memory;

import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * Single source of truth for the {@link ToolContextKey}s memory tools share.
 *
 * <p>
 * Every memory tool ({@link ObserveTool}, {@link MemoryRecallTool},
 * {@link MemorySearchTool}, {@link MemoryChatTool}) and the
 * {@link MemoryToolContextEnricher} reads from the same context slots. Defining
 * the keys here prevents the four-way duplication that previously existed and
 * makes it impossible for a future renamer to introduce a silent split (e.g.,
 * {@code memory.workspace} vs {@code memory.workspaceId}) that would break
 * cross-tool wiring without a compile error.
 */
public final class MemoryToolContextKeys {

    /** Workspace the memory operation runs in. */
    public static final ToolContextKey<Workspace> WORKSPACE = ToolContextKey.of("memory.workspace", Workspace.class);

    /** Peer doing the observing/recalling/searching (typically the agent itself). */
    public static final ToolContextKey<PeerView> OBSERVER = ToolContextKey.of("memory.observer", PeerView.class);

    /** Peer the operation is about; tools default to {@link #OBSERVER} when absent. */
    public static final ToolContextKey<PeerView> SUBJECT = ToolContextKey.of("memory.subject", PeerView.class);

    /** Optional session id correlating the operation to a specific session. */
    public static final ToolContextKey<String> SESSION_ID = ToolContextKey.of("memory.sessionId", String.class);

    private MemoryToolContextKeys() {
    }
}
