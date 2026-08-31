package at.aimon.core.subagent.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;

@DisplayName("SubagentBehaviorRegistrar Tests")
class SubagentBehaviorRegistrarTest {

    private static final SubagentBehavior BEHAVIOR = (ctx, req, support) -> support.success("ok");

    @Test
    @DisplayName("register populates both registries keyed by the subagent's own name (no drift)")
    void registersPairUnderSameName() {
        InMemorySubagentRegistry dataRegistry = new InMemorySubagentRegistry();
        InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        Subagent subagent = Subagent.builder().name("clock").description("time").systemPrompt("(code)").build();

        SubagentBehaviorRegistrar.register(subagent, BEHAVIOR, dataRegistry, behaviorRegistry);

        assertThat(dataRegistry.getSubagent("clock")).contains(subagent);
        assertThat(behaviorRegistry.getBehavior("clock")).contains(BEHAVIOR);
        // behavior is keyed by subagent.getName(), so the two entries can never drift apart
        assertThat(behaviorRegistry.behaviorNames()).containsExactly("clock");
    }

    @Test
    @DisplayName("null guards on every argument")
    void nullGuards() {
        InMemorySubagentRegistry data = new InMemorySubagentRegistry();
        InMemorySubagentBehaviorRegistry behavior = new InMemorySubagentBehaviorRegistry();
        Subagent subagent = Subagent.builder().name("x").systemPrompt("p").build();

        assertThatNullPointerException()
                .isThrownBy(() -> SubagentBehaviorRegistrar.register(null, BEHAVIOR, data, behavior));
        assertThatNullPointerException()
                .isThrownBy(() -> SubagentBehaviorRegistrar.register(subagent, null, data, behavior));
        assertThatNullPointerException()
                .isThrownBy(() -> SubagentBehaviorRegistrar.register(subagent, BEHAVIOR, null, behavior));
        assertThatNullPointerException()
                .isThrownBy(() -> SubagentBehaviorRegistrar.register(subagent, BEHAVIOR, data, null));
    }
}
