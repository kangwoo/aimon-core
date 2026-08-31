package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;

@DisplayName("RunningTaskRegistry — node-local registry of live execution handles")
class RunningTaskRegistryTest {

    private RunningTaskRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RunningTaskRegistry();
    }

    private static RunningTaskHandle handle(String taskId) {
        return new RunningTaskHandle(taskId, new DefaultInterruptCoordinator());
    }

    @Test
    @DisplayName("register then find returns the handle; size reflects live tasks")
    void registerThenFind() {
        RunningTaskHandle h = handle("t1");
        registry.register(h);

        assertThat(registry.find("t1")).containsSame(h);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("find of an unknown task is empty")
    void findUnknownIsEmpty() {
        assertThat(registry.find("ghost")).isEmpty();
    }

    @Test
    @DisplayName("remove evicts the handle")
    void remove() {
        registry.register(handle("t1"));
        registry.remove("t1");

        assertThat(registry.find("t1")).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("remove of an unknown task is a harmless no-op")
    void removeUnknownIsNoOp() {
        registry.remove("ghost");
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("taskIds returns a snapshot of all live handle ids")
    void taskIdsSnapshot() {
        registry.register(handle("t1"));
        registry.register(handle("t2"));

        assertThat(registry.taskIds()).containsExactlyInAnyOrder("t1", "t2");
    }

    @Test
    @DisplayName("taskIds is a defensive copy: mutating it does not touch the registry, and later removes are reflected")
    void taskIdsIsDefensiveCopy() {
        registry.register(handle("t1"));
        var ids = registry.taskIds();
        ids.clear();

        assertThat(registry.size()).isEqualTo(1);
        registry.remove("t1");
        assertThat(registry.taskIds()).isEmpty();
    }

    @Test
    @DisplayName("null arguments are rejected")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> registry.register(null));
        assertThatNullPointerException().isThrownBy(() -> registry.find(null));
        assertThatNullPointerException().isThrownBy(() -> registry.remove(null));
    }
}
