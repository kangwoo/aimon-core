package at.aimon.core.agent.compact;

import java.util.Objects;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmModel;

/**
 * Default {@link CompactionGuard} implementation that disables compaction entirely.
 *
 * <p>
 * Always returns {@link CompactionDecision#none()}. Used by callers (for example {@code aimon-cli} default wiring) that
 * have not opted in to conversation compaction; the {@link CompactionEngine} is then never invoked from the ReAct loop
 * and behavior matches the pre-compaction framework exactly.
 *
 * <p>
 * Thread-safe and stateless.
 */
public final class NoOpCompactionGuard implements CompactionGuard {

    private static final NoOpCompactionGuard INSTANCE = new NoOpCompactionGuard();

    public static NoOpCompactionGuard instance() {
        return INSTANCE;
    }

    private NoOpCompactionGuard() {
    }

    @Override
    public CompactionDecision maybeCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment) {
        Objects.requireNonNull(memory, "memory cannot be null");
        Objects.requireNonNull(model, "model cannot be null");
        Objects.requireNonNull(hookRegistry, "hookRegistry cannot be null");
        Objects.requireNonNull(environment, "environment cannot be null");
        return CompactionDecision.none("compaction disabled");
    }
}
