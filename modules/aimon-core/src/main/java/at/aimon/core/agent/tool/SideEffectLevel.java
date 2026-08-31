package at.aimon.core.agent.tool;

import java.util.Objects;

/**
 * Grades the observable side effects a {@link Tool} produces when executed, on an ordered scale.
 *
 * <p>
 * This is the <em>safety</em> axis: what the tool does to the world. It is deliberately separate from
 * {@link ConcurrencyBehavior}, which is the <em>thread-safety</em> axis (whether two tools may run at the same
 * instant).
 * The two correlate but neither can be derived from the other:
 *
 * <ul>
 * <li>a {@link #READ_ONLY} tool may still be {@link ConcurrencyBehavior#SEQUENTIAL} — for example when it contends on a
 * rate-limited endpoint;
 * <li>a {@link #MUTATING} tool may still be {@link ConcurrencyBehavior#CONCURRENT_SAFE} — for example when every call
 * writes to a destination the caller named, so two calls cannot collide.
 * </ul>
 *
 * <p>
 * Tools declare their level through {@link Tool#getSideEffectLevel()}. The declaration follows the same pattern as
 * {@link ConcurrencyBehavior} and {@link at.aimon.core.agent.interrupt.InterruptBehavior}: the tool states a
 * capability,
 * and a separate component decides how to act on it. Consumers in this codebase are the
 * {@code DefaultToolExecutionManager} gate (which refuses to execute tools exceeding a configured ceiling),
 * {@code OrcaAgentExecutor} (which withholds their definitions from the LLM entirely), and
 * {@link at.aimon.core.toolinvocation.approval.SideEffectApprovalGate} (which asks the user).
 *
 * <h2>Why {@code Level} and not {@code Policy}</h2>
 *
 * <p>
 * A tool does not <em>hold</em> a policy here; it states a fact about itself, and something else decides what to do
 * about that fact. The genuine policies are the ceiling and the approval gate named above. This type is also the only
 * one of the three tool-declaration enums that is <em>ordered</em> — it carries a rank and is compared through
 * {@link #permits(SideEffectLevel)} — and a policy has no magnitude, which is why {@code maxSideEffectLevel} reads as
 * a sentence and {@code maxSideEffectPolicy} did not. The convention that falls out: {@code *Behavior} names an
 * unordered trait a tool declares, {@code *Level} names an ordered one.
 *
 * <h2>The default is the conservative end</h2>
 *
 * <p>
 * {@link #MUTATING} is the default for every tool, so a tool that has never been audited is treated as if it changes
 * the world. Under-declaring is safe — it only costs availability under a restrictive ceiling. Over-declaring is not:
 * a tool that claims {@link #READ_ONLY} while writing files will execute in a mode that promised the user nothing
 * would change.
 *
 * <h2>Why exactly two rungs</h2>
 *
 * <p>
 * This scale answers one question — <em>does the tool write?</em> — and nothing else. Everything else worth knowing
 * about a write (is it replay-safe, is it destructive, does it reach outside the system) is <em>unordered</em> and does
 * not belong on a ladder: a destructive tool is not "more side-effecting" than a benign one, it is differently
 * side-effecting. Those belong in separate {@code *Behavior} declarations alongside this one, mapped one-to-one onto
 * MCP's {@code destructiveHint} / {@code idempotentHint} / {@code openWorldHint} tool annotations.
 *
 * <p>
 * An {@code IDEMPOTENT} rung sat between the two of these until it was removed. It read as "less side-effecting than
 * mutating", which it is not — an idempotent tool writes just as much, it is only safe to replay — and a ceiling set
 * to it therefore waved through idempotent-but-destructive tools such as a delete. See
 * {@code docs/design/tool/side-effect-axes.md} for the full argument and for where idempotency is headed.
 *
 * @see Tool#getSideEffectLevel()
 * @see Tool#isReadOnly()
 * @see ConcurrencyBehavior
 */
public enum SideEffectLevel {

    /**
     * The tool changes nothing outside the executing agent. It may read files, query stores, or call remote endpoints,
     * but leaves no trace an observer outside this execution could detect — no file written, no record persisted, no
     * cursor advanced in a shared registry, no message emitted to the user.
     *
     * <p>
     * Note that "reads something" is not sufficient. A tool that returns only output produced <em>since the last
     * call</em> advances a cursor and is therefore {@link #MUTATING}, not read-only.
     *
     * <p>
     * <b>The test is semantic, not literal:</b> could an observer outside this execution tell the tool ran, other
     * than by it having been slower or faster? Two kinds of write therefore do not disqualify a tool, and both occur
     * in this codebase:
     *
     * <ul>
     * <li><b>Bookkeeping in the tool's own {@link ToolContext}</b> — {@code ReadTool} records which files it read so
     * {@code EditTool} can require a prior read. That state is scoped to the execution, discarded with it, and can
     * only ever unlock a {@link #MUTATING} tool that a {@link #READ_ONLY} ceiling already refuses.
     * <li><b>A transparent read-through cache</b> — {@code WebFetch} and {@code WebSearch} persist responses keyed by
     * the request. The entry changes only how long the next identical call takes, never its meaning.
     * </ul>
     *
     * <p>
     * Neither exemption generalises to "the write is small" or "nobody looks at it". If removing the write could
     * change what a later call <em>returns</em>, the tool is not read-only.
     */
    READ_ONLY(0),

    /**
     * The default. The tool changes something outside the executing agent, or its effects have not been audited. Use
     * for file writes, command execution, scheduling, and anything whose effect is unknown.
     *
     * <p>
     * This rung says only <em>that</em> the tool writes, never how badly. A tool that appends a log line and a tool
     * that deletes a volume both land here; telling them apart is the job of a separate declaration, not of a higher
     * rung.
     */
    MUTATING(1);

    private final int level;

    SideEffectLevel(int level) {
        this.level = level;
    }

    /**
     * Tests whether this level, read as a <em>ceiling</em>, admits a tool declaring {@code required}.
     *
     * <p>
     * The ordering is {@link #READ_ONLY} &lt; {@link #MUTATING}, so a ceiling admits its own level and everything
     * below it. {@code MUTATING.permits(x)} is therefore always {@code true} — which is why {@code MUTATING} is the
     * default ceiling and imposes no restriction.
     *
     * <pre>
     * {@code
     * SideEffectLevel.MUTATING.permits(SideEffectLevel.READ_ONLY);  // true  — no restriction
     * SideEffectLevel.READ_ONLY.permits(SideEffectLevel.READ_ONLY); // true
     * SideEffectLevel.READ_ONLY.permits(SideEffectLevel.MUTATING);  // false — blocked
     * }
     * </pre>
     *
     * @param required
     *            the level declared by the tool being considered (must not be null)
     * @return true if a tool declaring {@code required} may run under this ceiling
     * @throws NullPointerException
     *             if required is null
     */
    public boolean permits(SideEffectLevel required) {
        Objects.requireNonNull(required, "Required level cannot be null");
        return required.level <= this.level;
    }
}
