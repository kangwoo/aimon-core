package at.aimon.core.agent.compact;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.LlmCallMetadata;

/**
 * The two built-in {@link CompactionObserver}s: the shared no-op, and the fail-safe wrapper the request builders put
 * around whatever observer a caller hands them.
 */
final class CompactionObservers {

    private static final Logger log = LoggerFactory.getLogger(CompactionObservers.class);

    /** The attempt that observes nothing; shared, since it holds no state. */
    static final CompactionObserver.Attempt NONE_ATTEMPT = new CompactionObserver.Attempt() {
        @Override
        public LlmCallMetadata enrich(LlmCallMetadata base) {
            return base;
        }

        @Override
        public CompactionResult record(CompactionResult result) {
            return result;
        }

        @Override
        public void close() {
            // nothing was opened
        }
    };

    /** The observer that observes nothing. */
    static final CompactionObserver NONE = start -> NONE_ATTEMPT;

    private CompactionObservers() {
    }

    static CompactionObserver failSafe(CompactionObserver observer) {
        Objects.requireNonNull(observer, "observer cannot be null");
        if (observer == NONE || observer instanceof FailSafe) {
            return observer;
        }
        return new FailSafe(observer);
    }

    /** Swallows what the wrapped observer throws; see {@link CompactionObserver#failSafe}. */
    private static final class FailSafe implements CompactionObserver {
        private final CompactionObserver delegate;

        private FailSafe(CompactionObserver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Attempt begin(CompactionStart start) {
            try {
                final Attempt attempt = delegate.begin(start);
                return attempt == null ? NONE_ATTEMPT : new FailSafeAttempt(attempt);
            } catch (RuntimeException e) {
                log.warn("CompactionObserver.begin failed; not observing this compaction: {}", e.getMessage());
                return NONE_ATTEMPT;
            }
        }
    }

    private static final class FailSafeAttempt implements CompactionObserver.Attempt {
        private final CompactionObserver.Attempt delegate;

        private FailSafeAttempt(CompactionObserver.Attempt delegate) {
            this.delegate = delegate;
        }

        @Override
        public LlmCallMetadata enrich(LlmCallMetadata base) {
            try {
                final LlmCallMetadata enriched = delegate.enrich(base);
                return enriched == null ? base : enriched;
            } catch (RuntimeException e) {
                log.warn("CompactionObserver.Attempt.enrich failed; keeping the caller's metadata: {}", e.getMessage());
                return base;
            }
        }

        @Override
        public CompactionResult record(CompactionResult result) {
            try {
                delegate.record(result);
            } catch (RuntimeException e) {
                log.warn("CompactionObserver.Attempt.record failed: {}", e.getMessage());
            }
            return result;
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } catch (RuntimeException e) {
                log.warn("CompactionObserver.Attempt.close failed: {}", e.getMessage());
            }
        }
    }
}
