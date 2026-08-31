package at.aimon.core.subagent.task;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * No-op {@link TaskStopSignal}: broadcasts go nowhere and subscriptions never fire.
 *
 * <p>
 * The default for a single-node deployment. With this signal installed, {@code Task.stop} still cancels tasks running
 * on
 * the local node directly through the handle registry; only <em>cross-node</em> propagation is disabled, so behaviour
 * is
 * byte-for-byte the pre-cross-node baseline. A scale-out deployment swaps in {@link InMemoryTaskStopSignal} (single
 * JVM)
 * or a shared-backend implementation (Redis pub/sub, ...).
 */
public final class NoopTaskStopSignal implements TaskStopSignal {

    /** Shared stateless instance. */
    public static final NoopTaskStopSignal INSTANCE = new NoopTaskStopSignal();

    private static final Subscription NOOP_SUBSCRIPTION = () -> {
    };

    private NoopTaskStopSignal() {
    }

    @Override
    public void broadcastStop(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        // Intentionally no-op: cross-node propagation is disabled.
    }

    @Override
    public Subscription subscribe(Consumer<String> onStopRequest) {
        Objects.requireNonNull(onStopRequest, "onStopRequest cannot be null");
        return NOOP_SUBSCRIPTION;
    }
}
