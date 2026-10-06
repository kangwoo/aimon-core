package at.aimon.session.testkit;

import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;

/**
 * Runs the task repository contract against the reference implementation, so the contract itself is checked daemonless.
 */
class InMemoryScheduledTaskRepositoryContractTest extends AbstractScheduledTaskRepositoryContractTest {

    private final ScheduledTaskRepository repository = new InMemoryScheduledTaskRepository();

    @Override
    protected ScheduledTaskRepository repository() {
        return repository;
    }
}
