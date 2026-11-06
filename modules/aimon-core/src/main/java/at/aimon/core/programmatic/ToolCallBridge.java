package at.aimon.core.programmatic;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import at.aimon.core.agent.tool.ToolDispatchResult;

/**
 * The one way out of an engine: a program's call of a tool, handed to the host
 * ({@code docs/design/tool/programmatic-tool-calling.md} §9).
 *
 * <p>
 * The bridge owns everything engine-independent — the frozen callable set, the code-path rules, the quota, the
 * concurrency lanes, the nested tool-use ids, the child cancellation signal, the {@code CLOSED} state, the dispatcher
 * call and the collection of notices and hook feedback. An engine only turns a function call of its language runtime
 * into {@link #callAsync} and the completed result back into a value of that runtime.
 *
 * <p>
 * Thread-safe. One bridge serves one {@code ExecuteCode} call.
 */
public interface ToolCallBridge {

    /**
     * Calls a tool on the program's behalf. Never throws for a refused or failed call: those complete normally with an
     * error result, whose text the program receives. The future completes on a host thread; an engine moves the
     * completion onto its own runtime thread itself.
     *
     * @param toolName
     *            the tool's name (must not be null)
     * @param input
     *            the arguments, detached from the engine's runtime — plain maps, lists, strings, numbers, booleans and
     *            nulls (must not be null)
     * @return the result (never null)
     */
    CompletableFuture<ToolDispatchResult> callAsync(String toolName, Map<String, Object> input);

    /**
     * Returns why the bridge stopped the program, when it did — a limit the program crossed that ends it at once (the
     * nested-call limit, the fork's tool-call quota). The bridge trips the execution's cancellation signal at the same
     * moment, so an engine that sees the signal reports {@link CodeExecutionStatus#LIMIT_EXCEEDED} with this reason
     * instead of {@link CodeExecutionStatus#CANCELLED}.
     *
     * @return the reason, empty while the program may go on
     */
    Optional<String> limitExceeded();

    /**
     * Marks every call the bridge has dispatched for this program, and every one it completes afterwards, as possibly
     * not the program's own — "unattributed" in the record ({@code docs/design/tool/programmatic-tool-calling-shell.md}
     * §7). An engine calls it when it detects a second writer on its channel (a protocol violation): calls dispatched
     * before the detection may have been injected. The default records nothing.
     */
    default void markUnattributed() {
    }
}
