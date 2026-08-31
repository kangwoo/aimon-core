/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.InterruptReason;

/**
 * In-JVM {@link ScheduledTaskInterruptBus}: every listener registered on <em>this instance</em> hears every request
 * published to it.
 *
 * <p>
 * Useful in two places. It is the reference implementation a distributed bus is measured against — same fan-out, same
 * at-least-once delivery, same echo to the publisher — and it is what lets the cross-node contract be tested without a
 * broker, by giving two {@link SchedulingEngine}s in one JVM the same bus instance. It is <b>not</b> a substitute for
 * a distributed bus: the reach of this one ends at the process boundary, so nodes that do not share a heap do not
 * share a bus.
 *
 * <p>
 * Delivery is synchronous on the publishing thread. Listeners here trip in-memory signals and return immediately, and
 * one that misbehaves is contained rather than allowed to swallow the fan-out — the point of a broadcast is that
 * <em>every</em> subscriber hears it, so a listener that throws is logged and the next one is still called.
 */
public final class InMemoryScheduledTaskInterruptBus implements ScheduledTaskInterruptBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryScheduledTaskInterruptBus.class);

    /**
     * Copy-on-write rather than a set: subscriptions are handles, so the same listener instance subscribing twice must
     * yield two entries that close independently, and iteration must not trip over a concurrent unsubscribe.
     */
    private final List<InterruptListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(ScheduledTaskId taskId, InterruptReason reason) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");

        for (InterruptListener listener : listeners) {
            try {
                listener.onInterruptRequested(taskId, reason);
            } catch (RuntimeException e) {
                log.warn("Interrupt listener failed for task '{}' ({}); continuing the fan-out", taskId, reason, e);
            }
        }
    }

    @Override
    public Subscription subscribe(InterruptListener listener) {
        Objects.requireNonNull(listener, "Listener cannot be null");
        listeners.add(listener);
        // remove(Object) drops the first occurrence, so closing one of two identical subscriptions leaves the other.
        return () -> listeners.remove(listener);
    }
}
