package at.aimon.session.testkit;

import at.aimon.core.scheduling.InMemoryScheduledTaskInterruptBus;
import at.aimon.core.scheduling.ScheduledTaskInterruptBus;

/**
 * Runs the interrupt bus contract against the reference implementation, so the contract itself is checked daemonless.
 */
class InMemoryScheduledTaskInterruptBusContractTest extends AbstractScheduledTaskInterruptBusContractTest {

    /** One instance for both nodes: sharing the instance is how this bus is shared. */
    private final ScheduledTaskInterruptBus bus = new InMemoryScheduledTaskInterruptBus();

    @Override
    protected ScheduledTaskInterruptBus busFor(String nodeName) {
        return bus;
    }
}
