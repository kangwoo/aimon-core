package at.aimon.core.agent.compact;

/**
 * What a compaction did to the view, for observability (context-engine §10).
 *
 * <p>
 * A rolling engine compacts often and in small steps, so an operator tuning it needs to tell its moves apart;
 * {@link CompactionMetadata#getKind()} carries the answer.
 */
public enum CompactionKind {

    /** Tool result bodies were elided from the view; nothing was summarized (context-engine §5.5). */
    PRUNE,

    /** A rolling span was widened: the middle of the view was summarized, head and tail kept verbatim. */
    ROLLING,

    /** The whole view was summarized into one boundary / summary pair — the default engine's compaction. */
    FULL,

    /**
     * The engine declined to compact because even its best cut would not bring the view under the threshold, and
     * warned instead (context-engine §5.6). A metadata of this kind describes a decision, not a summary.
     */
    FALLBACK
}
