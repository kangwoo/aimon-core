package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook executed before a conversation compaction is performed.
 *
 * <p>
 * PreCompactHook is the only compaction-lifecycle hook that may block: returning {@code HookResult.block(reason)}
 * causes {@link at.aimon.core.agent.compact.CompactionEngine} to abort the compaction with a
 * {@link at.aimon.core.agent.compact.CompactionBlockedByHookException}.
 *
 * <p>
 * Block policy:
 *
 * <ul>
 * <li>For {@link at.aimon.core.agent.compact.CompactionTrigger#AUTO} triggers, the block is honored.
 * <li>For {@link at.aimon.core.agent.compact.CompactionTrigger#MANUAL} triggers, the block is downgraded to a warning
 * and compaction proceeds (the user explicitly asked for it).
 * </ul>
 *
 * <p>
 * Hooks may also return {@code HookResult.withFeedback(text)} to inject custom summary instructions; the engine merges
 * feedback strings (subject to a 2000-character limit per the design) into the summary prompt as advisory guidance.
 */
@FunctionalInterface
public interface PreCompactHook extends ExecutionHook<PreCompactContext> {
    // Inherits execute(PreCompactContext) from ExecutionHook
}
