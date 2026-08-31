package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.StepKey;
import at.aimon.core.workflow.StepOutcome;
import at.aimon.core.workflow.StepResultCache;

@DisplayName("StepResultCache implementations — NO_OP, InMemory, Scoped")
class StepResultCacheImplTest {

    private static final RunId RUN = RunId.from("audit");
    private static final AgentRuntimeId CTX_A = AgentRuntimeId.fromName("agent-a");
    private static final AgentRuntimeId CTX_B = AgentRuntimeId.fromName("agent-b");

    private static StepOutcome outcome(String text) {
        return StepOutcome.builder().text(text).completionReason(CompletionReason.COMPLETED).inputHash("h")
                .structureFingerprint("fp").build();
    }

    @Nested
    @DisplayName("NO_OP")
    class NoOp {

        @Test
        @DisplayName("always misses and stores nothing")
        void alwaysMisses() {
            final StepKey key = StepKey.of(RUN, CTX_A, "a0");
            StepResultCache.NO_OP.save(key, outcome("x"));
            assertThat(StepResultCache.NO_OP.load(key)).isEmpty();
        }
    }

    @Nested
    @DisplayName("InMemoryStepResultCache")
    class InMemory {

        private final InMemoryStepResultCache cache = new InMemoryStepResultCache();

        @Test
        @DisplayName("save then load round-trips; evict removes")
        void saveLoadEvict() {
            final StepKey key = StepKey.of(RUN, CTX_A, "a0");
            final StepOutcome out = outcome("answer");

            cache.save(key, out);
            assertThat(cache.load(key)).contains(out);

            cache.evict(key);
            assertThat(cache.load(key)).isEmpty();
        }

        @Test
        @DisplayName("distinct keys are independent; unknown key misses")
        void independentKeys() {
            cache.save(StepKey.of(RUN, CTX_A, "p0/0/a0"), outcome("first"));
            assertThat(cache.load(StepKey.of(RUN, CTX_A, "p0/1/a0"))).isEmpty();
        }

        @Test
        @DisplayName("LRU-bounded: the least-recently-used outcome is evicted past the cap")
        void lruEviction() {
            final InMemoryStepResultCache small = new InMemoryStepResultCache(2);
            final StepKey k0 = StepKey.of(RUN, CTX_A, "a0");
            final StepKey k1 = StepKey.of(RUN, CTX_A, "a1");
            final StepKey k2 = StepKey.of(RUN, CTX_A, "a2");

            small.save(k0, outcome("0"));
            small.save(k1, outcome("1"));
            small.load(k0); // touch k0 so k1 becomes least-recently-used
            small.save(k2, outcome("2")); // over cap → evicts k1

            assertThat(small.load(k0)).isPresent();
            assertThat(small.load(k1)).isEmpty();
            assertThat(small.load(k2)).isPresent();
        }

        @Test
        @DisplayName("rejects null key/outcome and maxSteps < 1")
        void nullAndBounds() {
            assertThatThrownBy(() -> cache.load(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> cache.save(null, outcome("x"))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> cache.save(StepKey.of(RUN, CTX_A, "a0"), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new InMemoryStepResultCache(0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("ScopedStepResultCache")
    class Scoped {

        private final InMemoryStepResultCache backend = new InMemoryStepResultCache();
        private final StepResultCache scoped = new ScopedStepResultCache(backend, CTX_A);

        @Test
        @DisplayName("loads only a same-context key; a foreign-context key is hidden as a miss")
        void confinesLoad() {
            backend.save(StepKey.of(RUN, CTX_A, "a0"), outcome("mine"));
            backend.save(StepKey.of(RUN, CTX_B, "a0"), outcome("theirs"));

            assertThat(scoped.load(StepKey.of(RUN, CTX_A, "a0"))).isPresent();
            assertThat(scoped.load(StepKey.of(RUN, CTX_B, "a0"))).isEmpty();
        }

        @Test
        @DisplayName("an untagged (no-context) key is hidden — unverifiable ownership")
        void hidesUntagged() {
            backend.save(StepKey.of(RUN, null, "a0"), outcome("untagged"));
            assertThat(scoped.load(StepKey.of(RUN, null, "a0"))).isEmpty();
        }

        @Test
        @DisplayName("save and evict delegate unchanged")
        void saveEvictDelegate() {
            final StepKey key = StepKey.of(RUN, CTX_A, "a0");
            scoped.save(key, outcome("x"));
            assertThat(backend.load(key)).isPresent();
            scoped.evict(key);
            assertThat(backend.load(key)).isEmpty();
        }

        @Test
        @DisplayName("scopeOrPassThrough returns the delegate unchanged when the context is empty")
        void passThroughWhenEmpty() {
            assertThat(ScopedStepResultCache.scopeOrPassThrough(backend, Optional.empty())).isSameAs(backend);
            assertThat(ScopedStepResultCache.scopeOrPassThrough(backend, Optional.of(CTX_A)))
                    .isInstanceOf(ScopedStepResultCache.class);
        }
    }
}
