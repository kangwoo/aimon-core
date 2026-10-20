package at.aimon.core.agent.compact;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import at.aimon.core.llm.LlmCallMetadata;

/**
 * Test double for {@link CompactionObserver}: records every attempt it begins, and marks the metadata its attempts
 * enrich with an {@link #ATTEMPT_TAG} naming the attempt, so a test can tell which summary calls belonged to which
 * attempt.
 */
public final class RecordingCompactionObserver implements CompactionObserver {

    /** The tag an attempt writes into the metadata it enriches; its value is the attempt's 1-based number. */
    public static final String ATTEMPT_TAG = "test.compaction_attempt";

    private final List<RecordedAttempt> attempts = new CopyOnWriteArrayList<>();

    @Override
    public Attempt begin(CompactionStart start) {
        final RecordedAttempt attempt = new RecordedAttempt(start, attempts.size() + 1);
        attempts.add(attempt);
        return attempt;
    }

    /** Every attempt begun so far, in order. */
    public List<RecordedAttempt> attempts() {
        return attempts;
    }

    /** The only attempt; fails when there is not exactly one. */
    public RecordedAttempt single() {
        if (attempts.size() != 1) {
            throw new AssertionError("expected exactly one compaction attempt, got " + attempts.size());
        }
        return attempts.get(0);
    }

    /** One attempt and what it was told. */
    public static final class RecordedAttempt implements Attempt {
        private final CompactionStart start;
        private final String number;
        private final List<LlmCallMetadata> enriched = new CopyOnWriteArrayList<>();
        private final List<CompactionResult> recorded = new CopyOnWriteArrayList<>();
        private volatile int closes;

        private RecordedAttempt(CompactionStart start, int number) {
            this.start = start;
            this.number = String.valueOf(number);
        }

        @Override
        public LlmCallMetadata enrich(LlmCallMetadata base) {
            enriched.add(base == null ? LlmCallMetadata.empty() : base);
            return (base == null ? LlmCallMetadata.empty() : base).withTags(Map.of(ATTEMPT_TAG, number));
        }

        @Override
        public CompactionResult record(CompactionResult result) {
            recorded.add(result);
            return result;
        }

        @Override
        public void close() {
            closes++;
        }

        public CompactionStart start() {
            return start;
        }

        /** The value of {@link #ATTEMPT_TAG} in the metadata this attempt enriched. */
        public String number() {
            return number;
        }

        /** The bases this attempt was asked to enrich ({@code null} recorded as empty metadata). */
        public List<LlmCallMetadata> enriched() {
            return enriched;
        }

        public List<CompactionResult> recorded() {
            return recorded;
        }

        /** The last recorded result; fails when none was recorded. */
        public CompactionResult result() {
            if (recorded.isEmpty()) {
                throw new AssertionError("no result was recorded");
            }
            return recorded.get(recorded.size() - 1);
        }

        public int closes() {
            return closes;
        }
    }
}
