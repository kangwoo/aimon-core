package at.aimon.core.skill.hook.declarative;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.hook.execution.HookResult;

/**
 * What an {@code http} or {@code mcp} hook action came to: a verdict, or the reason there is none.
 *
 * <p>
 * The two are different things to a guard. A policy endpoint that answers "deny" — or "allow", or nothing in
 * particular — <em>gave a verdict</em>, and the hook returns it. One that could not be asked (unreachable, timed out,
 * a non-2xx status, an MCP server that is not connected), or whose answer cannot be read as a decision, gave none; the
 * caller then decides what the missing verdict means, exactly as it does for a shell command that produced no exit
 * status. The "no verdict" side is therefore carried as a
 * {@link ShellHookOutcome#notRun(ShellHookOutcome.Unrun, String)
 * not-run outcome} and judged by the same rule ({@code ShellHookVerdicts}) rather than by a second one.
 *
 * <p>
 * The detail of a not-run outcome is a fixed phrase, an HTTP status or an exception's type — never a URL, a header, a
 * response body or an exception message: on {@code preTool} it becomes a deny reason, and its reader is the party the
 * guard constrains.
 *
 * <p>
 * Immutable; thread-safe.
 */
public final class ActionCallOutcome {

    private final HookResult verdict;
    private final HookResult advisory;
    private final ShellHookOutcome unrun;

    private ActionCallOutcome(HookResult verdict, HookResult advisory, ShellHookOutcome unrun) {
        this.verdict = verdict;
        this.advisory = advisory;
        this.unrun = unrun;
    }

    /**
     * Creates the outcome of a call that produced a verdict.
     *
     * @param verdict
     *            what the endpoint decided, already mapped to a hook result (must not be null)
     * @return the outcome (never null)
     * @throws NullPointerException
     *             if verdict is null
     */
    public static ActionCallOutcome verdict(HookResult verdict) {
        Objects.requireNonNull(verdict, "verdict cannot be null");
        return new ActionCallOutcome(verdict, verdict, null);
    }

    /**
     * Creates the outcome of a call whose verdict is a question for the user ({@code ask}).
     *
     * <p>
     * A guard event has the question answered. An event that cannot block asks nobody, and there the question's
     * prompt must not be mistaken for the endpoint's advice to the model, so the two readings are carried apart.
     *
     * @param ask
     *            the verdict, an {@code ASK} result whose feedback is the prompt (must not be null)
     * @param advisory
     *            what the same answer says where nothing is asked: a success carrying the document's own feedback,
     *            or a bare success (must not be null)
     * @return the outcome (never null)
     * @throws NullPointerException
     *             if either argument is null
     */
    static ActionCallOutcome ask(HookResult ask, HookResult advisory) {
        return new ActionCallOutcome(Objects.requireNonNull(ask, "ask cannot be null"),
                Objects.requireNonNull(advisory, "advisory cannot be null"), null);
    }

    /**
     * Creates the outcome of a call that produced no verdict.
     *
     * @param cause
     *            why there is no verdict (must not be null)
     * @param detail
     *            a fixed phrase, a status or an exception type naming what happened (may be null or blank); never a
     *            URL, a body or an exception message
     * @return the outcome (never null)
     * @throws NullPointerException
     *             if cause is null
     */
    public static ActionCallOutcome notRun(ShellHookOutcome.Unrun cause, String detail) {
        return new ActionCallOutcome(null, null, ShellHookOutcome.notRun(cause, detail));
    }

    /**
     * @return the verdict, or empty when the call produced none
     */
    public Optional<HookResult> getVerdict() {
        return Optional.ofNullable(verdict);
    }

    /**
     * @return why the call produced no verdict, as a not-run outcome; empty when it {@linkplain #getVerdict() did}
     */
    public Optional<ShellHookOutcome> getUnrun() {
        return Optional.ofNullable(unrun);
    }

    /**
     * Returns the verdict as an event that cannot block reads it. A missing one is success: there is nothing to
     * block, and the executor has already logged why the call gave no answer. An {@code ask} is success too, carrying
     * the answer's own feedback when it has any &mdash; nothing resolves a question on such an event, and its prompt
     * ("confirm this call?") would otherwise reach the model as advice about a tool that already ran. A deny is
     * returned as it is; the caller decides what a block means where nothing can be blocked.
     *
     * @return the advisory reading of the outcome (never null, never an {@code ASK})
     */
    public HookResult orSuccess() {
        return advisory != null ? advisory : HookResult.success();
    }

    @Override
    public String toString() {
        return verdict != null
                ? "ActionCallOutcome{verdict=" + verdict.getStatus() + '}'
                : "ActionCallOutcome{" + unrun + '}';
    }
}
