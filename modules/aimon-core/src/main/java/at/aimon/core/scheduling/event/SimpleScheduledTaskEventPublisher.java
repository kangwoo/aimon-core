/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple implementation of {@link ScheduledTaskEventPublisher} using the Observer pattern.
 *
 * <p>
 * This implementation is thread-safe using CopyOnWriteArrayList for listener management.
 * </p>
 */
public class SimpleScheduledTaskEventPublisher implements ScheduledTaskEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SimpleScheduledTaskEventPublisher.class);

    private final List<ScheduledTaskEventListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(ScheduledTaskEvent event) {
        Objects.requireNonNull(event, "Event cannot be null");

        for (ScheduledTaskEventListener listener : listeners) {
            try {
                dispatchEvent(listener, event);
            } catch (Exception e) {
                log.error("Error dispatching event {} to listener {}: {}", event.getClass().getSimpleName(),
                        listener.getClass().getName(), e.getMessage(), e);
            }
        }
    }

    private void dispatchEvent(ScheduledTaskEventListener listener, ScheduledTaskEvent event) {
        if (event instanceof TaskRegisteredEvent e) {
            listener.onTaskRegistered(e);
        } else if (event instanceof TaskStartedEvent e) {
            listener.onTaskStarted(e);
        } else if (event instanceof TaskCompletedEvent e) {
            listener.onTaskCompleted(e);
        } else if (event instanceof TaskFailedEvent e) {
            listener.onTaskFailed(e);
        } else if (event instanceof TaskCancelledEvent e) {
            listener.onTaskCancelled(e);
        } else if (event instanceof TaskInterruptedEvent e) {
            listener.onTaskInterrupted(e);
        } else if (event instanceof StepCompletedEvent e) {
            listener.onStepCompleted(e);
        } else if (event instanceof StepFailedEvent e) {
            listener.onStepFailed(e);
        } else {
            log.warn("Unknown event type: {}", event.getClass().getName());
        }
    }

    @Override
    public void addListener(ScheduledTaskEventListener listener) {
        Objects.requireNonNull(listener, "Listener cannot be null");
        listeners.add(listener);
    }

    @Override
    public void removeListener(ScheduledTaskEventListener listener) {
        listeners.remove(listener);
    }
}
