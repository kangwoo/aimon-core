package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;

class DefaultHookRegistryTest {

    // Mock hook implementations for testing
    private static class TestOnStartHook implements OnStartHook {
        @Override
        public HookResult execute(OnStartContext context) {
            return HookResult.success();
        }
    }

    private static class TestPreToolHook implements PreToolHook {
        @Override
        public HookResult execute(PreToolContext context) {
            return HookResult.success();
        }
    }

    private static class TestPostToolHook implements PostToolHook {
        @Override
        public HookResult execute(PostToolContext context) {
            return HookResult.success();
        }
    }

    private static class TestOnStopHook implements OnStopHook {
        @Override
        public HookResult execute(OnStopContext context) {
            return HookResult.success();
        }
    }

    @Test
    void testAddHook_ValidOnStartHook_HookAdded() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        OnStartHook hook = new TestOnStartHook();

        // Act
        registry.register(HookEventType.ON_START, hook);

        // Assert
        assertThat(registry.getHooks(HookEventType.ON_START)).hasSize(1).contains(hook);
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void testAddHook_ValidPreToolHook_HookAdded() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        PreToolHook hook = new TestPreToolHook();

        // Act
        registry.register(HookEventType.PRE_TOOL, hook);

        // Assert
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).hasSize(1).contains(hook);
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void testAddHook_ValidPostToolHook_HookAdded() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        PostToolHook hook = new TestPostToolHook();

        // Act
        registry.register(HookEventType.POST_TOOL, hook);

        // Assert
        assertThat(registry.getHooks(HookEventType.POST_TOOL)).hasSize(1).contains(hook);
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void testAddHook_ValidOnStopHook_HookAdded() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        OnStopHook hook = new TestOnStopHook();

        // Act
        registry.register(HookEventType.ON_STOP, hook);

        // Assert
        assertThat(registry.getHooks(HookEventType.ON_STOP)).hasSize(1).contains(hook);
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void testAddHook_NullOnStartHook_ThrowsException() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();

        // Act & Assert
        assertThatThrownBy(() -> registry.register(HookEventType.ON_START, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Hook cannot be null");
    }

    @Test
    void testAddHook_NullPreToolHook_ThrowsException() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();

        // Act & Assert
        assertThatThrownBy(() -> registry.register(HookEventType.PRE_TOOL, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Hook cannot be null");
    }

    @Test
    void testAddHook_NullPostToolHook_ThrowsException() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();

        // Act & Assert
        assertThatThrownBy(() -> registry.register(HookEventType.POST_TOOL, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Hook cannot be null");
    }

    @Test
    void testAddHook_NullOnStopHook_ThrowsException() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();

        // Act & Assert
        assertThatThrownBy(() -> registry.register(HookEventType.ON_STOP, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Hook cannot be null");
    }

    @Test
    void testRemoveHook_ExistingOnStartHook_ReturnsTrue() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        OnStartHook hook = new TestOnStartHook();
        registry.register(HookEventType.ON_START, hook);

        // Act
        boolean removed = registry.unregister(HookEventType.ON_START, hook);

        // Assert
        assertThat(removed).isTrue();
        assertThat(registry.getHooks(HookEventType.ON_START)).isEmpty();
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void testRemoveHook_ExistingPreToolHook_ReturnsTrue() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        PreToolHook hook = new TestPreToolHook();
        registry.register(HookEventType.PRE_TOOL, hook);

        // Act
        boolean removed = registry.unregister(HookEventType.PRE_TOOL, hook);

        // Assert
        assertThat(removed).isTrue();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEmpty();
    }

    @Test
    void testRemoveHook_ExistingPostToolHook_ReturnsTrue() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        PostToolHook hook = new TestPostToolHook();
        registry.register(HookEventType.POST_TOOL, hook);

        // Act
        boolean removed = registry.unregister(HookEventType.POST_TOOL, hook);

        // Assert
        assertThat(removed).isTrue();
        assertThat(registry.getHooks(HookEventType.POST_TOOL)).isEmpty();
    }

    @Test
    void testRemoveHook_ExistingOnStopHook_ReturnsTrue() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        OnStopHook hook = new TestOnStopHook();
        registry.register(HookEventType.ON_STOP, hook);

        // Act
        boolean removed = registry.unregister(HookEventType.ON_STOP, hook);

        // Assert
        assertThat(removed).isTrue();
        assertThat(registry.getHooks(HookEventType.ON_STOP)).isEmpty();
    }

    @Test
    void testRemoveHook_NonExistingOnStartHook_ReturnsFalse() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        OnStartHook hook = new TestOnStartHook();

        // Act
        boolean removed = registry.unregister(HookEventType.ON_START, hook);

        // Assert
        assertThat(removed).isFalse();
    }

    @Test
    void testRemoveHook_NonExistingPreToolHook_ReturnsFalse() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        PreToolHook hook = new TestPreToolHook();

        // Act
        boolean removed = registry.unregister(HookEventType.PRE_TOOL, hook);

        // Assert
        assertThat(removed).isFalse();
    }

    @Test
    void testRemoveHook_NullOnStartHook_ThrowsException() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();

        // Act & Assert
        assertThatThrownBy(() -> registry.unregister(HookEventType.ON_START, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Hook cannot be null");
    }

    @Test
    void testRemoveHook_NullPreToolHook_ThrowsException() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();

        // Act & Assert
        assertThatThrownBy(() -> registry.unregister(HookEventType.PRE_TOOL, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Hook cannot be null");
    }

    @Test
    void testClearOnStartHooks_MultipleHooks_AllCleared() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.ON_START, new TestOnStartHook());
        registry.register(HookEventType.ON_START, new TestOnStartHook());
        registry.register(HookEventType.ON_START, new TestOnStartHook());

        // Act
        registry.clearAll();

        // Assert
        assertThat(registry.getHooks(HookEventType.ON_START)).isEmpty();
    }

    @Test
    void testClearPreToolHooks_MultipleHooks_AllCleared() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.PRE_TOOL, new TestPreToolHook());
        registry.register(HookEventType.PRE_TOOL, new TestPreToolHook());

        // Act
        registry.clearAll();

        // Assert
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEmpty();
    }

    @Test
    void testClearPostToolHooks_MultipleHooks_AllCleared() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.POST_TOOL, new TestPostToolHook());
        registry.register(HookEventType.POST_TOOL, new TestPostToolHook());

        // Act
        registry.clearAll();

        // Assert
        assertThat(registry.getHooks(HookEventType.POST_TOOL)).isEmpty();
    }

    @Test
    void testClearOnStopHooks_MultipleHooks_AllCleared() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.ON_STOP, new TestOnStopHook());
        registry.register(HookEventType.ON_STOP, new TestOnStopHook());

        // Act
        registry.clearAll();

        // Assert
        assertThat(registry.getHooks(HookEventType.ON_STOP)).isEmpty();
    }

    @Test
    void testClearAll_MultipleTypes_AllCleared() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.ON_START, new TestOnStartHook());
        registry.register(HookEventType.PRE_TOOL, new TestPreToolHook());
        registry.register(HookEventType.POST_TOOL, new TestPostToolHook());
        registry.register(HookEventType.ON_STOP, new TestOnStopHook());

        // Act
        registry.clearAll();

        // Assert
        assertThat(registry.getHooks(HookEventType.ON_START)).isEmpty();
        assertThat(registry.getHooks(HookEventType.PRE_TOOL)).isEmpty();
        assertThat(registry.getHooks(HookEventType.POST_TOOL)).isEmpty();
        assertThat(registry.getHooks(HookEventType.ON_STOP)).isEmpty();
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void testConcurrentModification_AddDuringIteration_Safe() throws InterruptedException {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.ON_START, new TestOnStartHook());
        registry.register(HookEventType.ON_START, new TestOnStartHook());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        List<Exception> exceptions = new ArrayList<>();

        // Act
        // Thread 1: Iterate over hooks
        executor.submit(() -> {
            try {
                for (OnStartHook hook : registry.getHooks(HookEventType.ON_START)) {
                    // Verify hook is not null during iteration
                    assertThat(hook).isNotNull();
                    Thread.sleep(10); // Simulate hook execution
                }
            } catch (Exception e) {
                exceptions.add(e);
            } finally {
                latch.countDown();
            }
        });

        // Thread 2: Add hooks during iteration
        executor.submit(() -> {
            try {
                registry.register(HookEventType.ON_START, new TestOnStartHook());
                registry.register(HookEventType.ON_START, new TestOnStartHook());
            } catch (Exception e) {
                exceptions.add(e);
            } finally {
                latch.countDown();
            }
        });

        // Assert
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // No ConcurrentModificationException should occur
        assertThat(completed).isTrue();
        assertThat(exceptions).isEmpty();
        assertThat(registry.getHooks(HookEventType.ON_START)).hasSize(4);
    }

    @Test
    void testGetHooks_ReturnsImmutableCopy() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        OnStartHook hook = new TestOnStartHook();
        registry.register(HookEventType.ON_START, hook);

        // Act
        List<OnStartHook> hooks = registry.getHooks(HookEventType.ON_START);

        // Assert - Returned list should be immutable
        assertThatThrownBy(() -> hooks.add(new TestOnStartHook())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testMultipleHooksExecutionOrder() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        OnStartHook hook1 = new TestOnStartHook();
        OnStartHook hook2 = new TestOnStartHook();
        OnStartHook hook3 = new TestOnStartHook();

        // Act
        registry.register(HookEventType.ON_START, hook1);
        registry.register(HookEventType.ON_START, hook2);
        registry.register(HookEventType.ON_START, hook3);

        // Assert - Order should be preserved
        List<OnStartHook> hooks = registry.getHooks(HookEventType.ON_START);
        assertThat(hooks).hasSize(3).containsExactly(hook1, hook2, hook3);
    }

    @Test
    void testRemoveFirstOccurrence_DuplicateHooks() {
        // Arrange
        HookRegistry registry = new DefaultHookRegistry();
        OnStartHook hook = new TestOnStartHook();
        registry.register(HookEventType.ON_START, hook);
        registry.register(HookEventType.ON_START, hook);
        registry.register(HookEventType.ON_START, hook);

        // Act - Remove should only remove first occurrence
        boolean removed = registry.unregister(HookEventType.ON_START, hook);

        // Assert
        assertThat(removed).isTrue();
        assertThat(registry.getHooks(HookEventType.ON_START)).hasSize(2).contains(hook);
    }
}
