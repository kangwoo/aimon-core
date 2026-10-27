package at.aimon.scheduling.testkit;

import at.aimon.core.scheduling.InMemoryScheduledExecutionGuard;
import at.aimon.core.scheduling.ScheduledExecutionGuard;

/** The reference guard against the suite: node-local, task-id keyed, so one instance plays both nodes. */
class InMemoryScheduledExecutionGuardContractTest extends AbstractScheduledExecutionGuardContractTest {

    private final ScheduledExecutionGuard guard = new InMemoryScheduledExecutionGuard();

    @Override
    protected ScheduledExecutionGuard guardFor(String nodeName) {
        return guard;
    }

    @Override
    protected boolean dedupsOccurrences() {
        return false;
    }
}
