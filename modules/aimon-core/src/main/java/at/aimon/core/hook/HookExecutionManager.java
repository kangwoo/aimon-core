package at.aimon.core.hook;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.event.OnSessionEndContext;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.PermissionDeniedContext;
import at.aimon.core.hook.event.PermissionRequestContext;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.hook.execution.HookExecutionPolicy;
import at.aimon.core.hook.execution.HookExecutor;
import at.aimon.core.hook.execution.HookResult;

/**
 * Manages hook execution with configurable policies for different hook types.
 *
 * <p>
 * This class coordinates hook execution through a {@link HookExecutor} and applies different
 * {@link HookExecutionPolicy} strategies for each hook type (OnStart, PreTool, PostTool, OnStop). This allows
 * fine-grained control over error handling and execution flow for different lifecycle stages.
 *
 * <p>
 * <b>Default Policies:</b>
 * <ul>
 * <li><b>OnStart:</b> Continue on exception, never stop (monitoring only)</li>
 * <li><b>PreTool:</b> Continue on exception, stop on blocked (allows hook-based tool permission control)</li>
 * <li><b>PostTool:</b> Continue on exception, never stop (monitoring only)</li>
 * <li><b>OnStop:</b> Continue on exception, never stop (guaranteed execution for cleanup)</li>
 * </ul>
 *
 * <p>
 * <b>Thread Safety:</b> Thread-safe. All hook executions are delegated to the underlying {@link HookExecutor}.
 *
 * <p>
 * <b>Example usage:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create manager with default policies
 *     HookExecutionManager manager = new DefaultHookExecutionManager();
 *
 *     // Execute PreTool hooks before tool execution
 *     PreToolContext context = PreToolContext.builder().invokerType(InvokerType.MAIN_AGENT)
 *             .invokerName("default-agent").hookRegistry(hookRegistry).toolName("ReadTool").build();
 *
 *     List<HookResult> results = manager.executePreTool(context);
 *
 *     // Check if any hook blocked the tool execution
 *     if (manager.hasBlockedResult(results)) {
 *         // Tool execution should be prevented
 *         List<String> feedbacks = manager.collectFeedback(results);
 *         // Send feedback to user/LLM
 *         return;
 *     }
 *
 *     // Proceed with tool execution
 * }
 * </pre>
 *
 * @see HookExecutor
 * @see HookExecutionPolicy
 * @see DefaultHookRegistry
 */
public interface HookExecutionManager {

    /**
     * Executes all OnStart hooks.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    List<HookResult> executeOnStart(OnStartContext context);

    /**
     * Executes all PreTool hooks.
     *
     * <p>
     * If any hook blocks, execution should be prevented.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    List<HookResult> executePreTool(PreToolContext context);

    /**
     * Executes all PostTool hooks.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    List<HookResult> executePostTool(PostToolContext context);

    /**
     * Executes all OnStop hooks.
     *
     * <p>
     * Guaranteed to execute even if execution failed.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    List<HookResult> executeOnStop(OnStopContext context);

    /**
     * Executes all PreCompact hooks.
     *
     * <p>
     * For {@link at.aimon.core.agent.compact.CompactionTrigger#AUTO} triggers any blocked result aborts compaction; for
     * {@link at.aimon.core.agent.compact.CompactionTrigger#MANUAL} triggers blocks are downgraded to warnings (the user
     * explicitly requested the compaction). The implementation chooses an appropriate policy based on
     * {@code context.getTrigger()}.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    default List<HookResult> executePreCompact(PreCompactContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        return List.of();
    }

    /**
     * Executes all PostCompact hooks.
     *
     * <p>
     * Non-blocking. Results are typically only used for logging/metrics; any feedback strings can be appended to the
     * conversation as advisory notes.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    default List<HookResult> executePostCompact(PostCompactContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        return List.of();
    }

    /**
     * Executes all PermissionRequest hooks before tool execution.
     *
     * <p>
     * Permission hooks are tri-state ({@code ALLOW} / {@code ASK} / {@code DENY}). {@code ASK} results are promoted to
     * {@code ALLOW} or {@code DENY} by the dispatcher's configured ask-prompt handler before being returned.
     *
     * <p>
     * If the merged decision is {@code DENY}, callers should short-circuit tool dispatch and fire the corresponding
     * {@link #executePermissionDenied(PermissionDeniedContext)} chain.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    default List<HookResult> executePermissionRequest(PermissionRequestContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        return List.of();
    }

    /**
     * Executes all PermissionDenied hooks after a permission request was denied.
     *
     * <p>
     * Advisory chain — results are typically used for audit/logging. Hooks must not block (any blocked results are
     * ignored by the caller).
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    default List<HookResult> executePermissionDenied(PermissionDeniedContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        return List.of();
    }

    /**
     * Executes all SubagentStart hooks immediately before a subagent dispatched via the Task tool begins execution.
     *
     * <p>
     * Advisory chain — hooks must not block the subagent dispatch.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    default List<HookResult> executeSubagentStart(SubagentStartContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        return List.of();
    }

    /**
     * Executes all SubagentStop hooks after a subagent dispatched via the Task tool finishes.
     *
     * <p>
     * Advisory chain — fired for both successful and failed dispatches.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    default List<HookResult> executeSubagentStop(SubagentStopContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        return List.of();
    }

    /**
     * Executes all OnSessionStart hooks immediately after a {@code LiveSession} is opened.
     *
     * <p>
     * Advisory chain — hooks must not block session creation.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    default List<HookResult> executeOnSessionStart(OnSessionStartContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        return List.of();
    }

    /**
     * Executes all OnSessionEnd hooks when a {@code LiveSession} is closed.
     *
     * <p>
     * Advisory chain — fired for both clean and abnormal terminations; hooks must not block.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    default List<HookResult> executeOnSessionEnd(OnSessionEndContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        return List.of();
    }

    /**
     * Executes all OnConfigReload hooks after the hook configuration has been reloaded.
     *
     * <p>
     * Application-scoped, advisory chain — fired by {@code HookConfigWatcher} for both successful reloads and
     * rollbacks. Hooks must not block.
     *
     * @param context
     *            The context (must not be null)
     * @return List of results, one per hook (never null)
     * @throws NullPointerException
     *             if context is null
     */
    default List<HookResult> executeOnConfigReload(OnConfigReloadContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        return List.of();
    }

    /**
     * Checks if any result is blocked.
     *
     * <p>
     * Typically used with PreTool hook results to determine if tools execution should be prevented.
     *
     * @param results
     *            The hook results (must not be null)
     * @return true if any result is blocked, false otherwise
     * @throws NullPointerException
     *             if results is null
     */
    default boolean hasBlockedResult(List<HookResult> results) {
        Objects.requireNonNull(results, "Results cannot be null");
        return results.stream().anyMatch(HookResult::isBlocked);
    }

    /**
     * Collects all feedback messages from results.
     *
     * <p>
     * Feedback is surfaced to the LLM by the firing site: tool-scoped chains append it to the tool result (see
     * {@code SingleToolInvoker}), lifecycle chains add it as a user message. Callers that fire a chain which can deny
     * should filter {@link HookResult#isBlocked() blocked} results out first — their feedback is the deny reason and
     * is already rendered by the deny path.
     *
     * @param results
     *            The hook results (must not be null)
     * @return List of feedback messages (never null, can be empty)
     * @throws NullPointerException
     *             if results is null
     */
    default List<String> collectFeedback(List<HookResult> results) {
        Objects.requireNonNull(results, "Results cannot be null");
        return results.stream().map(HookResult::getFeedback).filter(Optional::isPresent).map(Optional::get).toList();
    }

    /**
     * Returns the final accumulated {@link ToolInput} produced by PreTool hook chain mutations, if any.
     *
     * <p>
     * Scans results from last to first looking for a populated {@link HookResult#getUpdatedInput()}. The
     * {@code DefaultHookExecutor} materialises the cumulative input on the last emitted result, so this is typically a
     * single-step lookup; the scan provides a forward-compatible safety net.
     *
     * @param results
     *            the hook results (must not be null)
     * @return the final accumulated input, or {@link Optional#empty()} if no PreTool hook updated the input
     * @throws NullPointerException
     *             if results is null
     */
    default Optional<ToolInput> finalUpdatedInput(List<HookResult> results) {
        Objects.requireNonNull(results, "Results cannot be null");
        for (int i = results.size() - 1; i >= 0; i--) {
            final Optional<ToolInput> updated = results.get(i).getUpdatedInput();
            if (updated.isPresent()) {
                return updated;
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the final accumulated {@link ToolResult} produced by PostTool hook chain mutations, if any.
     *
     * <p>
     * Scans results from last to first looking for a populated {@link HookResult#getUpdatedOutput()}. The
     * {@code DefaultHookExecutor} materialises the cumulative output on the last emitted result, so this is typically a
     * single-step lookup; the scan provides a forward-compatible safety net.
     *
     * @param results
     *            the hook results (must not be null)
     * @return the final accumulated output, or {@link Optional#empty()} if no PostTool hook updated the output
     * @throws NullPointerException
     *             if results is null
     */
    default Optional<ToolResult> finalUpdatedOutput(List<HookResult> results) {
        Objects.requireNonNull(results, "Results cannot be null");
        for (int i = results.size() - 1; i >= 0; i--) {
            final Optional<ToolResult> updated = results.get(i).getUpdatedOutput();
            if (updated.isPresent()) {
                return updated;
            }
        }
        return Optional.empty();
    }

    /**
     * 차단된 결과의 사유 메시지를 수집한다.
     *
     * @param results
     *            훅 실행 결과 목록 (null 불가)
     * @return 차단 사유 메시지 목록 (null 아님, 비어 있을 수 있음)
     */
    default List<String> collectBlockedReasons(List<HookResult> results) {
        Objects.requireNonNull(results, "Results cannot be null");
        return results.stream().filter(HookResult::isBlocked)
                .map(result -> result.getFeedback().orElse("No reason provided")).toList();
    }

}
