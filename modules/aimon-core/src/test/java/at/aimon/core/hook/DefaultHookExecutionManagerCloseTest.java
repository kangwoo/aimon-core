package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.hook.execution.HookExecutionPolicy;
import at.aimon.core.hook.execution.HookExecutor;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.llm.ToolUse;

/**
 * B-4 — {@link DefaultHookExecutionManager} releases the hook thread pool its own builder default created, and nothing
 * else.
 *
 * <p>
 * Neither {@link HookExecutionManager} nor {@link HookExecutor} declares a lifecycle, so the duty rests on this
 * concrete class alone. What these tests pin down is the ownership rule that decides when it applies: the builder's
 * default executor is ours to close, an executor handed to {@link DefaultHookExecutionManager.Builder#executor} is the
 * caller's, and {@code withRewakeService} moves the duty to the copy rather than duplicating or dropping it.
 *
 * <p>
 * Each test that expects a shutdown fires a real hook first. A cached pool creates no thread until the first submit, so
 * a test that skips that step asserts nothing — it would keep passing with an empty {@code close()}.
 */
class DefaultHookExecutionManagerCloseTest {

    /** Upper bound on how long a {@code shutdownNow}-ed worker may take to die — far below the 60s idle reap. */
    private static final long POOL_STOP_TIMEOUT_MS = 5_000L;

    @Test
    @Timeout(20)
    void closeShutsDownThePoolTheBuilderDefaultCreated() throws Exception {
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final DefaultHookExecutionManager manager = DefaultHookExecutionManager.builder()
                .askPromptHandler(AskPromptHandler.denyAll()).build();

        fireOneHook(manager, worker);
        final Thread pooled = worker.get();
        assertThat(pooled).as("the hook body must have run on a pool thread").isNotNull()
                .isNotSameAs(Thread.currentThread());
        assertThat(pooled.isAlive()).isTrue();

        manager.close();

        assertThat(awaitDeath(pooled)).as("the manager owns the pool its builder defaulted to").isTrue();
    }

    @Test
    @Timeout(20)
    void closeLeavesASuppliedExecutorAlone() throws Exception {
        final ClosableRecordingExecutor supplied = new ClosableRecordingExecutor();
        final DefaultHookExecutionManager manager = DefaultHookExecutionManager.builder().executor(supplied)
                .askPromptHandler(AskPromptHandler.denyAll()).build();

        manager.close();

        assertThat(supplied.closed).as("a supplied executor is borrowed, whatever it is made of").isFalse();
    }

    /**
     * {@code withRewakeService} is a replace, not a fan-out: its caller keeps the copy and discards the source. If the
     * duty stayed behind on the source, nothing anyone still holds could release the pool.
     */
    @Test
    @Timeout(20)
    void withRewakeServiceMovesTheOwnershipToTheCopy() throws Exception {
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final DefaultHookExecutionManager copy = DefaultHookExecutionManager.builder()
                .askPromptHandler(AskPromptHandler.denyAll()).build().withRewakeService(mock(RewakeService.class));

        fireOneHook(copy, worker);
        final Thread pooled = worker.get();
        assertThat(pooled).isNotNull();
        assertThat(pooled.isAlive()).isTrue();

        copy.close();

        assertThat(awaitDeath(pooled)).isTrue();
    }

    /** Runs one PreTool hook through the manager and records the thread its body ran on. */
    private static void fireOneHook(HookExecutionManager manager, AtomicReference<Thread> worker) {
        final HookRegistry registry = new DefaultHookRegistry();
        final PreToolHook capture = ctx -> {
            worker.set(Thread.currentThread());
            return HookResult.success();
        };
        registry.register(HookEventType.PRE_TOOL, capture);

        final PreToolContext context = PreToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                .invokerName("agent").hookRegistry(registry).environment(Environment.createDefault())
                .toolUse(ToolUse.of("id", "Bash", Map.of("command", "echo"))).iterationCount(1).build();

        final List<HookResult> results = manager.executePreTool(context);
        assertThat(results).hasSize(1);
    }

    private static boolean awaitDeath(Thread thread) throws InterruptedException {
        thread.join(POOL_STOP_TIMEOUT_MS);
        return !thread.isAlive();
    }

    /**
     * A supplied executor that is itself {@link AutoCloseable} — the case that makes the ownership flag necessary. A
     * type check alone would close this one, even though its creator is still holding it.
     */
    private static final class ClosableRecordingExecutor implements HookExecutor, AutoCloseable {

        private boolean closed;

        @Override
        public <C extends HookContext> List<HookResult> execute(List<? extends ExecutionHook<C>> hooks, C context,
                HookExecutionPolicy policy) {
            return hooks.stream().map(hook -> hook.execute(context)).toList();
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }
}
