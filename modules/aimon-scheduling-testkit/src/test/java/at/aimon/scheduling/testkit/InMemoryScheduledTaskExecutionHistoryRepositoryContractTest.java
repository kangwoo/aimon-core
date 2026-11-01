package at.aimon.scheduling.testkit;

import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.ScheduledTaskExecutionHistoryRepository;

/** The reference history repository against the suite. */
class InMemoryScheduledTaskExecutionHistoryRepositoryContractTest
        extends
            AbstractScheduledTaskExecutionHistoryRepositoryContractTest {

    private final ScheduledTaskExecutionHistoryRepository repository = new InMemoryScheduledTaskExecutionHistoryRepository();

    @Override
    protected ScheduledTaskExecutionHistoryRepository repository() {
        return repository;
    }
}
