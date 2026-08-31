/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.scheduling.ScheduledTaskInterruptBus.Subscription;

/**
 * Pins the fan-out contract every {@link ScheduledTaskInterruptBus} implementation owes its subscribers, on the one
 * implementation that can be tested without a broker.
 */
class InMemoryScheduledTaskInterruptBusTest {

    private final InMemoryScheduledTaskInterruptBus bus = new InMemoryScheduledTaskInterruptBus();
    private final ScheduledTaskId taskId = ScheduledTaskId.generate();

    @Test
    void everySubscriberHearsAPublishedRequest() {
        final List<ScheduledTaskId> first = new ArrayList<>();
        final List<InterruptReason> second = new ArrayList<>();
        bus.subscribe((id, reason) -> first.add(id));
        bus.subscribe((id, reason) -> second.add(reason));

        bus.publish(taskId, InterruptReason.TASK_CANCELLED);

        assertThat(first).containsExactly(taskId);
        assertThat(second).containsExactly(InterruptReason.TASK_CANCELLED);
    }

    @Test
    void aClosedSubscriptionStopsHearing() {
        final List<ScheduledTaskId> heard = new ArrayList<>();
        final Subscription subscription = bus.subscribe((id, reason) -> heard.add(id));

        subscription.close();
        bus.publish(taskId, InterruptReason.TASK_CANCELLED);

        assertThat(heard).isEmpty();
    }

    /**
     * Two subscriptions from one listener instance close independently.
     *
     * <p>
     * A set would collapse them and let the first close silence both — the reason the implementation keeps a list.
     * Nothing in the codebase subscribes the same instance twice today, but a bus whose unsubscribe reaches further
     * than the handle it was called on is the kind of defect that surfaces as a node that stopped honouring
     * cancellations for no visible reason.
     */
    @Test
    void closingOneOfTwoSubscriptionsFromTheSameListenerLeavesTheOther() {
        final List<ScheduledTaskId> heard = new ArrayList<>();
        final ScheduledTaskInterruptBus.InterruptListener listener = (id, reason) -> heard.add(id);
        final Subscription first = bus.subscribe(listener);
        bus.subscribe(listener);

        first.close();
        bus.publish(taskId, InterruptReason.TASK_CANCELLED);

        assertThat(heard).containsExactly(taskId);
    }

    /**
     * One broken subscriber must not silence the rest.
     *
     * <p>
     * The whole value of a broadcast is that every node hears it, so a listener that throws is contained rather than
     * allowed to end the fan-out — otherwise a single unhealthy node would keep runs alive on all the healthy ones,
     * and which nodes those are would depend on iteration order.
     */
    @Test
    void aThrowingSubscriberDoesNotStopTheFanOut() {
        final List<ScheduledTaskId> heard = new ArrayList<>();
        bus.subscribe((id, reason) -> {
            throw new IllegalStateException("this node is having a bad day");
        });
        bus.subscribe((id, reason) -> heard.add(id));

        bus.publish(taskId, InterruptReason.TASK_CANCELLED);

        assertThat(heard).containsExactly(taskId);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatNullPointerException().isThrownBy(() -> bus.publish(null, InterruptReason.TASK_CANCELLED));
        assertThatNullPointerException().isThrownBy(() -> bus.publish(taskId, null));
        assertThatNullPointerException().isThrownBy(() -> bus.subscribe(null));
    }

    /** The single-node default is inert in both directions, and closing its subscription is not an error. */
    @Test
    void localOnlyPublishesNowhereAndItsSubscriptionCloses() {
        final Subscription subscription = ScheduledTaskInterruptBus.LOCAL_ONLY.subscribe((id, reason) -> {
            throw new AssertionError("LOCAL_ONLY must never deliver");
        });

        ScheduledTaskInterruptBus.LOCAL_ONLY.publish(taskId, InterruptReason.TASK_CANCELLED);
        subscription.close();
    }
}
