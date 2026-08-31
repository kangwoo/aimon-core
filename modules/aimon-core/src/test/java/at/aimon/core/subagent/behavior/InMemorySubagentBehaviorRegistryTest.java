package at.aimon.core.subagent.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InMemorySubagentBehaviorRegistry Tests")
class InMemorySubagentBehaviorRegistryTest {

    private static final SubagentBehavior BEHAVIOR_A = (ctx, req, support) -> support.success("a");
    private static final SubagentBehavior BEHAVIOR_B = (ctx, req, support) -> support.success("b");

    @Test
    @DisplayName("register then getBehavior returns the registered behavior")
    void registerThenGet() {
        InMemorySubagentBehaviorRegistry registry = new InMemorySubagentBehaviorRegistry();
        registry.register("clock", BEHAVIOR_A);

        assertThat(registry.getBehavior("clock")).contains(BEHAVIOR_A);
        assertThat(registry.behaviorNames()).containsExactly("clock");
    }

    @Test
    @DisplayName("getBehavior returns empty when not found")
    void getMissingReturnsEmpty() {
        assertThat(new InMemorySubagentBehaviorRegistry().getBehavior("nope")).isEmpty();
    }

    @Test
    @DisplayName("register replaces on name collision")
    void replaceOnCollision() {
        InMemorySubagentBehaviorRegistry registry = new InMemorySubagentBehaviorRegistry();
        registry.register("x", BEHAVIOR_A);
        registry.register("x", BEHAVIOR_B);

        assertThat(registry.getBehavior("x")).contains(BEHAVIOR_B);
        assertThat(registry.behaviorNames()).containsExactly("x");
    }

    @Test
    @DisplayName("unregister removes and returns the behavior; empty when absent")
    void unregister() {
        InMemorySubagentBehaviorRegistry registry = new InMemorySubagentBehaviorRegistry();
        registry.register("x", BEHAVIOR_A);

        assertThat(registry.unregister("x")).contains(BEHAVIOR_A);
        assertThat(registry.getBehavior("x")).isEmpty();
        assertThat(registry.unregister("x")).isEmpty();
    }

    @Test
    @DisplayName("behaviorNames is a defensive copy")
    void behaviorNamesIsCopy() {
        InMemorySubagentBehaviorRegistry registry = new InMemorySubagentBehaviorRegistry();
        registry.register("a", BEHAVIOR_A);

        var snapshot = registry.behaviorNames();
        registry.register("b", BEHAVIOR_B);

        assertThat(snapshot).containsExactly("a");
    }

    @Test
    @DisplayName("null guards on register / unregister / getBehavior")
    void nullGuards() {
        InMemorySubagentBehaviorRegistry registry = new InMemorySubagentBehaviorRegistry();

        assertThatNullPointerException().isThrownBy(() -> registry.register(null, BEHAVIOR_A));
        assertThatNullPointerException().isThrownBy(() -> registry.register("x", null));
        assertThatNullPointerException().isThrownBy(() -> registry.unregister(null));
        assertThatNullPointerException().isThrownBy(() -> registry.getBehavior(null));
    }

    @Test
    @DisplayName("SubagentBehaviorRegistry.empty() is an immutable always-empty registry")
    void emptyRegistry() {
        SubagentBehaviorRegistry empty = SubagentBehaviorRegistry.empty();

        assertThat(empty.getBehavior("anything")).isEmpty();
        assertThat(empty.behaviorNames()).isEmpty();
        // empty() returns the shared immutable instance
        assertThat(empty).isSameAs(SubagentBehaviorRegistry.empty());
    }
}
