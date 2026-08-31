package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.subagent.Subagent;

@DisplayName("AgentTask — isolate / nonCacheable flags (design §6.3)")
class AgentTaskTest {

    private static final Subagent SUB = Subagent.builder().name("w").systemPrompt("(inline)").build();

    @Test
    @DisplayName("defaults: not isolated, cacheable")
    void defaults() {
        final AgentTask task = AgentTask.of(SUB, "g");

        assertThat(task.isIsolate()).isFalse();
        assertThat(task.isNonCacheable()).isFalse();
    }

    @Test
    @DisplayName("isolate=true implies nonCacheable (derived at build time — single authoritative predicate)")
    void isolateImpliesNonCacheable() {
        final AgentTask task = AgentTask.builder().subagent(SUB).goal("g").isolate(true).build();

        assertThat(task.isIsolate()).isTrue();
        assertThat(task.isNonCacheable()).isTrue();
    }

    @Test
    @DisplayName("nonCacheable can be set on its own for a non-isolated base-VFS-mutating step")
    void nonCacheableWithoutIsolate() {
        final AgentTask task = AgentTask.builder().subagent(SUB).goal("g").nonCacheable(true).build();

        assertThat(task.isIsolate()).isFalse();
        assertThat(task.isNonCacheable()).isTrue();
    }
}
