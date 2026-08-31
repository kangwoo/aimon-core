package at.aimon.core.agent.tool;

/**
 * What a {@link Tool} does to state that <em>already exists</em>, once {@link SideEffectLevel} has established that the
 * tool writes at all.
 *
 * <p>
 * {@link SideEffectLevel} answers one question — does the tool write? — and deliberately stops there. This type answers
 * the next one. The two are not rungs of one scale: a tool that deletes a volume is not "more side-effecting" than one
 * that appends a log line, it is <em>differently</em> side-effecting, and folding the two questions into one ordered
 * ladder is exactly the mistake that produced the removed {@code IDEMPOTENT} rung. So this is an unordered trait, named
 * {@code *Behavior} by the convention {@link SideEffectLevel} sets out, and there is no {@code permits()} on it.
 *
 * <h2>Only meaningful for a tool that writes</h2>
 *
 * <p>
 * Consumers read this declaration <b>only when the tool declares {@link SideEffectLevel#MUTATING}</b>. A
 * {@link SideEffectLevel#READ_ONLY} tool changes nothing, so it can destroy nothing, and the question does not arise.
 * Keeping the axes separate but reading the second one conditionally is what makes the contradictory state
 * "read-only and destructive" unreachable without a validity check spanning two declarations.
 *
 * <h2>The default is the conservative end</h2>
 *
 * <p>
 * {@link #DESTRUCTIVE} is the default, so a writing tool nobody has audited is assumed to be able to destroy something.
 * This matches {@link SideEffectLevel#MUTATING} being the default on the other axis — an undeclared tool is treated as
 * the worst case on both — and it matches MCP, whose {@code destructiveHint} also defaults to {@code true}. Because the
 * defaults line up on both sides, an MCP tool that sends no annotations needs no special case: absent means the AIMON
 * default, and the AIMON default is the MCP default.
 *
 * <h2>Where the line falls</h2>
 *
 * <p>
 * {@link #NON_DESTRUCTIVE} means <em>additive only</em>, the same test MCP applies: the tool may create records and
 * append to them, but it does not overwrite, remove, or invalidate state that was already there. "Recoverable"
 * destruction — a delete that moves to a trash bin, a soft delete, a delete with a snapshot behind it — is not a third
 * value here. Recoverability is usually a property of the <em>environment</em> (does this cluster keep snapshots? does
 * this filesystem have a trash?), which the tool cannot honestly declare, and a third value would force every consumer
 * to decide separately whether it triggers approval. A tool that implements the recovery mechanism <em>itself</em> is
 * the case that would justify one; see {@code docs/design/tool/side-effect-axes.md} §6.6.
 *
 * @see Tool#getDestructiveBehavior()
 * @see SideEffectLevel
 */
public enum DestructiveBehavior {

    /**
     * The tool's writes are additive: it creates or appends, and leaves existing state intact. Scheduling a new task,
     * recording an observation, and creating a file that did not exist are additive; overwriting that file is not.
     *
     * <p>
     * Declaring this is a claim that the tool <em>cannot</em> destroy, not that it usually does not. A tool that
     * overwrites only sometimes — on a flag, on a particular input — is {@link #DESTRUCTIVE}, because this axis is
     * about the tool and not about the arguments of one call. Per-call judgement belongs to
     * {@code CustomToolPermissionAware} and its rules, which do see the arguments.
     */
    NON_DESTRUCTIVE,

    /**
     * The default. The tool may overwrite, remove, or invalidate state that already exists — or nobody has checked
     * whether it can.
     *
     * <p>
     * The approval gate asks about a tool declaring this <em>regardless of its exemption threshold</em>, which is the
     * one rule an operator cannot configure away. A run with nobody to ask therefore fails rather than proceeding; see
     * {@link at.aimon.core.toolinvocation.approval.SideEffectApprovalGate}.
     */
    DESTRUCTIVE
}
