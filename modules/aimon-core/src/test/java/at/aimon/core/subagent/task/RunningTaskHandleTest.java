package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

@DisplayName("RunningTaskHandle — node-local execution levers for cooperative stop")
class RunningTaskHandleTest {

    @Test
    @DisplayName("getSignal exposes the coordinator's signal; stop flag defaults false; future/worker start empty")
    void initialState() {
        InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        RunningTaskHandle handle = new RunningTaskHandle("t1", coordinator);

        assertThat(handle.getTaskId()).isEqualTo("t1");
        assertThat(handle.getSignal()).isSameAs(coordinator.getSignal());
        assertThat(handle.isStopRequested()).isFalse();
        assertThat(handle.getSignal().isCancelled()).isFalse();
        assertThat(handle.getFuture()).isEmpty();

        coordinator.close();
    }

    @Test
    @DisplayName("attachFuture exposes the result future")
    void attachFuture() {
        InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        RunningTaskHandle handle = new RunningTaskHandle("t1", coordinator);
        CompletableFuture<SubagentExecutionResult> future = new CompletableFuture<>();

        handle.attachFuture(future);

        assertThat(handle.getFuture()).containsSame(future);
        coordinator.close();
    }

    @Test
    @DisplayName("requestStop sets the flag and trips the per-task cancellation signal as PARENT_CANCELLED")
    void requestStopTripsSignal() {
        InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        RunningTaskHandle handle = new RunningTaskHandle("t1", coordinator);

        handle.requestStop();

        assertThat(handle.isStopRequested()).isTrue();
        assertThat(handle.getSignal().isCancelled()).isTrue();
        assertThat(handle.getSignal().getReason()).contains(InterruptReason.PARENT_CANCELLED);
        coordinator.close();
    }

    @Test
    @DisplayName("requestStop interrupts the attached worker thread")
    void requestStopInterruptsWorker() throws InterruptedException {
        InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        RunningTaskHandle handle = new RunningTaskHandle("t1", coordinator);

        CompletableFuture<Boolean> observedInterrupt = new CompletableFuture<>();
        CompletableFuture<Void> workerStarted = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            handle.attachWorker(Thread.currentThread());
            workerStarted.complete(null);
            try {
                Thread.sleep(10_000);
                observedInterrupt.complete(false);
            } catch (InterruptedException e) {
                observedInterrupt.complete(true);
            }
        });
        worker.setDaemon(true);
        worker.start();
        workerStarted.join();

        handle.requestStop();

        assertThat(observedInterrupt.join()).isTrue();
        worker.join(1_000);
        coordinator.close();
    }

    @Test
    @DisplayName("requestStop is idempotent (repeated calls are harmless)")
    void requestStopIdempotent() {
        InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        RunningTaskHandle handle = new RunningTaskHandle("t1", coordinator);

        handle.requestStop();
        handle.requestStop();

        assertThat(handle.isStopRequested()).isTrue();
        assertThat(handle.getSignal().isCancelled()).isTrue();
        coordinator.close();
    }

    @Test
    @DisplayName("null constructor arguments are rejected")
    void rejectsNulls() {
        InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        assertThatNullPointerException().isThrownBy(() -> new RunningTaskHandle(null, coordinator));
        assertThatNullPointerException().isThrownBy(() -> new RunningTaskHandle("t1", null));

        RunningTaskHandle handle = new RunningTaskHandle("t1", coordinator);
        assertThatNullPointerException().isThrownBy(() -> handle.attachFuture(null));
        assertThatNullPointerException().isThrownBy(() -> handle.attachWorker(null));
        coordinator.close();
    }
}
