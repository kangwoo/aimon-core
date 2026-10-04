package at.aimon.core.tools.bash;

import java.util.Objects;
import java.util.Optional;

/**
 * What {@link BackgroundBashManager#find} knows about a task id, for one caller. One of three:
 * <ul>
 * <li>{@link Kind#LOCAL} — the task runs (or ran) on this node, and its handle is here: output can be read and the
 * command stopped;</li>
 * <li>{@link Kind#ELSEWHERE} — only the store's record is here. The command belongs to another node's process, or to
 * this node before a restart; either way nothing on this node can read its output or stop it;</li>
 * <li>{@link Kind#NOT_FOUND} — no such task, or it belongs to another owner, which reads the same on purpose.</li>
 * </ul>
 */
public final class BackgroundBashLookup {

    /** The three answers. */
    public enum Kind {
        /** The task's handle is on this node. */
        LOCAL,
        /** Only the store's record is on this node. */
        ELSEWHERE,
        /** No task the caller may see has this id. */
        NOT_FOUND
    }

    private static final BackgroundBashLookup NOT_FOUND = new BackgroundBashLookup(Kind.NOT_FOUND, null, null, false,
            false);

    private final Kind kind;
    private final BackgroundBashTask task;
    private final BackgroundBashRecord record;
    private final boolean lostByThisNode;
    private final boolean expired;

    private BackgroundBashLookup(Kind kind, BackgroundBashTask task, BackgroundBashRecord record,
            boolean lostByThisNode, boolean expired) {
        this.kind = kind;
        this.task = task;
        this.record = record;
        this.lostByThisNode = lostByThisNode;
        this.expired = expired;
    }

    static BackgroundBashLookup local(BackgroundBashTask task) {
        return new BackgroundBashLookup(Kind.LOCAL, Objects.requireNonNull(task, "task"), null, false, false);
    }

    static BackgroundBashLookup elsewhere(BackgroundBashRecord record, boolean lostByThisNode, boolean expired) {
        return new BackgroundBashLookup(Kind.ELSEWHERE, null, Objects.requireNonNull(record, "record"), lostByThisNode,
                expired);
    }

    static BackgroundBashLookup notFound() {
        return NOT_FOUND;
    }

    /** @return which of the three answers this is */
    public Kind kind() {
        return kind;
    }

    /** @return the task, for {@link Kind#LOCAL} */
    public Optional<BackgroundBashTask> task() {
        return Optional.ofNullable(task);
    }

    /** @return the store's record, for {@link Kind#ELSEWHERE} */
    public Optional<BackgroundBashRecord> record() {
        return Optional.ofNullable(record);
    }

    /**
     * @return for {@link Kind#ELSEWHERE}: whether the record names this node — the node started the command and no
     *         longer has its handle, which is what a restart over a durable store leaves behind
     */
    public boolean lostByThisNode() {
        return lostByThisNode;
    }

    /**
     * @return for {@link Kind#ELSEWHERE}: whether the record still said "running" after the command's timeout must
     *         have ended it. Its node never reported the end, so the outcome is unknown; the manager has dropped the
     *         record, and the next lookup finds nothing
     */
    public boolean expired() {
        return expired;
    }
}
