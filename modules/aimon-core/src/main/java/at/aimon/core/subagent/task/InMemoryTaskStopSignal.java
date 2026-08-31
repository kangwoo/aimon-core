package at.aimon.core.subagent.task;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process, loopback {@link TaskStopSignal}: a {@link #broadcastStop(String)} is delivered synchronously to every
 * handler subscribed on the <em>same</em> instance.
 *
 * <p>
 * The reference implementation for the cross-node seam and the default for a single-JVM deployment that runs more than
 * one {@code SubagentExecutionManager} against a shared signal (e.g. tests). It carries no network hop — sharing one
 * instance across managers lets a stop issued through one manager reach a task owned by another. A true scale-out
 * deployment replaces it with a shared-backend implementation (Redis pub/sub, ...).
 *
 * <p>
 * Handlers are stored in a {@link CopyOnWriteArrayList} so delivery iterates without locking; a throwing handler is
 * isolated (logged, not propagated) so one bad subscriber cannot break delivery to the others.
 */
public final class InMemoryTaskStopSignal implements TaskStopSignal {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTaskStopSignal.class);

    private final List<Consumer<String>> handlers = new CopyOnWriteArrayList<>();

    @Override
    public void broadcastStop(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        for (final Consumer<String> handler : handlers) {
            try {
                handler.accept(taskId);
            } catch (RuntimeException e) {
                log.warn("Task stop handler threw for taskId {}: {}", taskId, e.toString());
            }
        }
    }

    @Override
    public Subscription subscribe(Consumer<String> onStopRequest) {
        Objects.requireNonNull(onStopRequest, "onStopRequest cannot be null");
        handlers.add(onStopRequest);
        return () -> handlers.remove(onStopRequest);
    }
}
