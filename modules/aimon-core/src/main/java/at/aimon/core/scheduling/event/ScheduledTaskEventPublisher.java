/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

/**
 * Interface for publishing scheduled task events.
 */
public interface ScheduledTaskEventPublisher {

    /**
     * Publishes an event to all registered listeners.
     *
     * @param event
     *            the event to publish
     */
    void publish(ScheduledTaskEvent event);

    /**
     * Registers an event listener.
     *
     * @param listener
     *            the listener to register
     */
    void addListener(ScheduledTaskEventListener listener);

    /**
     * Removes an event listener.
     *
     * @param listener
     *            the listener to remove
     */
    void removeListener(ScheduledTaskEventListener listener);
}
