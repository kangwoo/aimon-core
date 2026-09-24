package at.aimon.core.agent.context;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.llm.exception.LlmPromptTooLongException;

/**
 * The {@link ContextEngine#passthrough()} engine: sends the transcript as it is, never compacts, never recovers.
 *
 * <p>
 * Stateless and thread-safe.
 */
final class PassthroughContextEngine implements ContextEngine {

    static final PassthroughContextEngine INSTANCE = new PassthroughContextEngine();

    private PassthroughContextEngine() {
    }

    @Override
    public ContextDecision prepare(ContextRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        return ContextDecision.builder().view(ContextView.of(request.getTranscriptBuffer().getMessages()))
                .action(CompactionDecision.Action.NONE).reason("compaction disabled").build();
    }

    @Override
    public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(error, "error cannot be null");
        return Optional.empty();
    }

    @Override
    public CompactionResult compactNow(ContextRequest request, String instructions) {
        Objects.requireNonNull(request, "request cannot be null");
        final Instant now = Instant.now();
        return CompactionResult.failure(new UnsupportedOperationException("compaction disabled"),
                CompactionMetadata.builder().trigger(CompactionTrigger.MANUAL).startedAt(now).completedAt(now).build());
    }

    @Override
    public String toString() {
        return "ContextEngine.passthrough()";
    }
}
