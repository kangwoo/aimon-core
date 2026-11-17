package at.aimon.core.agent.compact;

/**
 * What a compaction did to the view, for observability (context-engine › 선택과 관측).
 *
 * <p>
 * A rolling engine compacts often and in small steps, so an operator tuning it needs to tell its moves apart;
 * {@link CompactionMetadata#getKind()} carries the answer.
 */
public enum CompactionKind {

    /** Tool result bodies were elided from the view; nothing was summarized (rolling-context-engine › L0 prune). */
    PRUNE,

    /** A rolling span was widened: the middle of the view was summarized, head and tail kept verbatim. */
    ROLLING,

    /**
     * The view was summarized into one boundary / summary pair — the default engine's compaction. In view mode the
     * messages the model had not answered yet are left after the pair, verbatim.
     */
    FULL,

    /**
     * The engine declined to compact because even its best cut would not bring the view under the threshold, and
     * warned instead (rolling-context-engine › 언제 — 후퇴와 FALLBACK). A metadata of this kind describes a decision, not a
     * summary.
     */
    FALLBACK
}
