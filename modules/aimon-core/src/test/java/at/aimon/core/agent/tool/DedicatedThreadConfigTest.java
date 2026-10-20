package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DedicatedThreadConfig / DedicatedThreadPermits / DispatchOptions Tests")
class DedicatedThreadConfigTest {

    @Test
    @DisplayName("defaults: disabled, 4 threads, fork depth 1")
    void defaults() {
        final DedicatedThreadConfig config = DedicatedThreadConfig.builder().build();
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getMaxThreads()).isEqualTo(DedicatedThreadConfig.DEFAULT_MAX_THREADS).isEqualTo(4);
        assertThat(config.getMaxForkDepth()).isEqualTo(DedicatedThreadConfig.DEFAULT_MAX_FORK_DEPTH).isEqualTo(1);
    }

    @Test
    @DisplayName("disabled() is one shared instance; enabled(n) sets the bound")
    void factories() {
        assertThat(DedicatedThreadConfig.disabled()).isSameAs(DedicatedThreadConfig.disabled());
        assertThat(DedicatedThreadConfig.disabled().isEnabled()).isFalse();
        final DedicatedThreadConfig enabled = DedicatedThreadConfig.enabled(7);
        assertThat(enabled.isEnabled()).isTrue();
        assertThat(enabled.getMaxThreads()).isEqualTo(7);
        assertThat(enabled.getMaxForkDepth()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejects maxThreads < 1 and maxForkDepth < 1")
    void validation() {
        assertThatThrownBy(() -> DedicatedThreadConfig.builder().maxThreads(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxThreads");
        assertThatThrownBy(() -> DedicatedThreadConfig.builder().maxForkDepth(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxForkDepth");
    }

    @Test
    @DisplayName("toString names every field")
    void toStringNamesFields() {
        assertThat(DedicatedThreadConfig.builder().enabled(true).maxThreads(2).maxForkDepth(3).build().toString())
                .isEqualTo("DedicatedThreadConfig{enabled=true, maxThreads=2, maxForkDepth=3}");
    }

    @Test
    @DisplayName("permits: non-blocking acquire up to the bound; a fresh daemon thread per call")
    void permits() {
        final DedicatedThreadPermits permits = DedicatedThreadPermits.of(DedicatedThreadConfig.enabled(2));
        assertThat(permits.tryAcquire()).isTrue();
        assertThat(permits.tryAcquire()).isTrue();
        assertThat(permits.tryAcquire()).isFalse();
        assertThat(permits.availablePermits()).isZero();
        permits.release();
        assertThat(permits.availablePermits()).isEqualTo(1);

        final Thread a = permits.newThread(() -> {
        });
        final Thread b = permits.newThread(() -> {
        });
        assertThat(a).isNotSameAs(b);
        assertThat(a.isDaemon()).isTrue();
        assertThat(a.getState()).isEqualTo(Thread.State.NEW);
        assertThat(a.getName()).startsWith("tool-dedicated-").isNotEqualTo(b.getName());
    }

    @Test
    @DisplayName("dispatch options: root() is depth 0; negative depth rejected")
    void dispatchOptions() {
        assertThat(DispatchOptions.root().getForkDepth()).isZero();
        assertThat(DispatchOptions.builder().forkDepth(2).build().getForkDepth()).isEqualTo(2);
        assertThatThrownBy(() -> DispatchOptions.builder().forkDepth(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the default five-argument path is what the six-argument default delegates to")
    void sixArgumentDefaultDelegates() {
        final ParallelToolDispatcher sequential = DefaultParallelToolDispatcher.sequential();
        assertThat(sequential.dispatch(java.util.List.of(at.aimon.core.llm.ToolUse.of("a", "X", java.util.Map.of())),
                null, DispatchOptions.builder().forkDepth(3).build(),
                tu -> at.aimon.core.llm.ToolUseResult.success(tu.getId(), "ok"), null, null)).singleElement()
                .satisfies(r -> assertThat(r.getToolUseId()).isEqualTo("a"));
        assertThat(sequential.routesHarvestTail()).isFalse();
    }
}
