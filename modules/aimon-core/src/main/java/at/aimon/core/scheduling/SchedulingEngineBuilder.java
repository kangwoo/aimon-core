/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.util.concurrent.atomic.AtomicReference;

import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.base.ExternallyManaged;
import at.aimon.core.scheduling.event.ScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.quota.DefaultTaskQuotaManager;
import at.aimon.core.scheduling.quota.TaskQuotaManager;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.repository.ScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.InMemoryTaskScheduler;
import at.aimon.core.scheduling.scheduler.ScheduledTaskExecutor;
import at.aimon.core.scheduling.scheduler.TaskScheduler;
import at.aimon.core.scheduling.scheduler.TaskSchedulerFactory;

/**
 * Builder for creating {@link SchedulingEngine} instances.
 *
 * <p>
 * This builder allows configuration of all engine components with sensible defaults. The
 * {@link AgentRuntimeRegistry} is provided externally and shared between the engine and other components that
 * need to register/unregister agent runtimes.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>{@code
 * AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
 * SchedulingEngine engine = SchedulingEngineBuilder.create().agentRuntimeRegistry(registry).defaultMaxQuota(20)
 *         .build();
 *
 * engine.start();
 * registry.register(agentRuntime);
 * // ... use engine
 * registry.unregister(agentRuntime.getId());
 * engine.close();
 * }</pre>
 */
public final class SchedulingEngineBuilder {

    private ScheduledTaskRepository taskRepository;
    private ScheduledTaskExecutionHistoryRepository historyRepository;
    private TaskScheduler taskScheduler;
    private TaskSchedulerFactory taskSchedulerFactory;
    private ScheduledTaskEventPublisher eventPublisher;
    private TaskQuotaManager quotaManager;
    private ScheduledExecutionGuard executionGuard;
    private ScheduledTaskInterruptBus interruptBus;
    @ExternallyManaged
    private AgentRuntimeRegistry agentRuntimeRegistry;
    private int defaultMaxQuota = 10;

    private SchedulingEngineBuilder() {
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static SchedulingEngineBuilder create() {
        return new SchedulingEngineBuilder();
    }

    /**
     * Sets the task repository.
     *
     * @param repository
     *            the task repository
     * @return this builder
     */
    public SchedulingEngineBuilder taskRepository(ScheduledTaskRepository repository) {
        taskRepository = repository;
        return this;
    }

    /**
     * Sets the execution history repository.
     *
     * @param repository
     *            the history repository
     * @return this builder
     */
    public SchedulingEngineBuilder historyRepository(ScheduledTaskExecutionHistoryRepository repository) {
        historyRepository = repository;
        return this;
    }

    /**
     * Sets an already-built task scheduler.
     *
     * <p>
     * The scheduler must already dispatch firings to this engine's {@link ScheduledTaskManager}, which does not exist
     * yet when this method is called — so the caller has had to close over a mutable reference and fill it in
     * afterwards. {@link #taskSchedulerFactory(TaskSchedulerFactory)} does that for the caller and is the better
     * choice for anything but a scheduler that is already wired.
     * </p>
     *
     * @param scheduler
     *            the task scheduler
     * @return this builder
     */
    public SchedulingEngineBuilder taskScheduler(TaskScheduler scheduler) {
        taskScheduler = scheduler;
        return this;
    }

    /**
     * Sets a factory that builds the task scheduler once its executor exists.
     *
     * <p>
     * Prefer this over {@link #taskScheduler(TaskScheduler)}: the executor a scheduler has to call is the task manager,
     * and the task manager needs the scheduler. {@link #build()} is where that knot is untied, so a factory lets the
     * scheduler be built on the far side of it and receive the real executor rather than a reference that is still
     * empty.
     * </p>
     *
     * @param factory
     *            the scheduler factory
     * @return this builder
     */
    public SchedulingEngineBuilder taskSchedulerFactory(TaskSchedulerFactory factory) {
        taskSchedulerFactory = factory;
        return this;
    }

    /**
     * Sets the event publisher.
     *
     * @param publisher
     *            the event publisher
     * @return this builder
     */
    public SchedulingEngineBuilder eventPublisher(ScheduledTaskEventPublisher publisher) {
        eventPublisher = publisher;
        return this;
    }

    /**
     * Sets the quota manager.
     *
     * @param manager
     *            the quota manager
     * @return this builder
     */
    public SchedulingEngineBuilder quotaManager(TaskQuotaManager manager) {
        quotaManager = manager;
        return this;
    }

    /**
     * Sets the guard consulted before each fire, which decides whether an execution may begin.
     *
     * <p>
     * If not set, an {@link InMemoryScheduledExecutionGuard} is used: it prevents a task from overlapping itself
     * <em>on this node only</em>. In a scale-out deployment where several nodes schedule the same tasks, pass a
     * distributed guard (one backed by a shared lock/lease store) so a cron time fires the task on exactly one node.
     * </p>
     *
     * @param guard
     *            the execution guard
     * @return this builder
     */
    public SchedulingEngineBuilder executionGuard(ScheduledExecutionGuard guard) {
        executionGuard = guard;
        return this;
    }

    /**
     * Sets the bus that carries "stop this task's runs" to the other nodes.
     *
     * <p>
     * If not set, {@link ScheduledTaskInterruptBus#LOCAL_ONLY} is used: cancelling or interrupting a task stops the
     * runs of it held by <em>this</em> node, which on a single node is all of them. In a scale-out deployment the node
     * a user cancels from is usually not the node the cron fired on, so pass a distributed implementation — otherwise
     * the run there carries on to the end of its steps against a task that no longer exists.
     * {@link InMemoryScheduledTaskInterruptBus} covers the narrower case of several engines sharing one JVM.
     * </p>
     *
     * @param bus
     *            the interrupt bus
     * @return this builder
     */
    public SchedulingEngineBuilder interruptBus(ScheduledTaskInterruptBus bus) {
        interruptBus = bus;
        return this;
    }

    /**
     * Sets the agent runtime registry.
     *
     * <p>
     * If not set, a {@link DefaultAgentRuntimeRegistry} will be created.
     * </p>
     *
     * @param registry
     *            the context registry
     * @return this builder
     */
    public SchedulingEngineBuilder agentRuntimeRegistry(@ExternallyManaged AgentRuntimeRegistry registry) {
        agentRuntimeRegistry = registry;
        return this;
    }

    /**
     * Sets the default maximum quota per agent runtime.
     *
     * <p>
     * This is only used if no custom quota manager is provided.
     * </p>
     *
     * @param quota
     *            the default maximum quota
     * @return this builder
     */
    public SchedulingEngineBuilder defaultMaxQuota(int quota) {
        if (quota <= 0) {
            throw new IllegalArgumentException("Default max quota must be positive");
        }
        defaultMaxQuota = quota;
        return this;
    }

    /**
     * Builds the scheduling engine.
     *
     * <p>
     * The scheduler is built here, from a lazy reference to the {@link ScheduledTaskManager}. This resolves the
     * circular dependency between the scheduler (which needs a task executor) and the task manager (which needs a
     * scheduler, and is itself the executor). The lazy reference is safe because it is guaranteed to be set before
     * {@link SchedulingEngine#start()} is called.
     * </p>
     *
     * <p>
     * Which scheduler gets built depends on what was configured: a {@link #taskSchedulerFactory(TaskSchedulerFactory)}
     * is invoked with the executor, a {@link #taskScheduler(TaskScheduler)} is taken as-is (it was wired by its own
     * caller), and with neither an {@link InMemoryTaskScheduler} is created.
     * </p>
     *
     * @return a new scheduling engine
     * @throws IllegalStateException
     *             if both a scheduler and a scheduler factory were configured
     */
    public SchedulingEngine build() {
        if (taskScheduler != null && taskSchedulerFactory != null) {
            throw new IllegalStateException("Configure either taskScheduler or taskSchedulerFactory, not both");
        }
        if (taskRepository == null) {
            taskRepository = new InMemoryScheduledTaskRepository();
        }
        if (historyRepository == null) {
            historyRepository = new InMemoryScheduledTaskExecutionHistoryRepository();
        }
        if (eventPublisher == null) {
            eventPublisher = new SimpleScheduledTaskEventPublisher();
        }
        if (quotaManager == null) {
            quotaManager = new DefaultTaskQuotaManager(defaultMaxQuota);
        }
        if (executionGuard == null) {
            executionGuard = new InMemoryScheduledExecutionGuard();
        }
        if (interruptBus == null) {
            interruptBus = ScheduledTaskInterruptBus.LOCAL_ONLY;
        }
        if (agentRuntimeRegistry == null) {
            agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();
        }

        // Create routine executor with context registry for dynamic tool resolution
        final RoutineExecutor routineExecutor = new RoutineExecutor(agentRuntimeRegistry, eventPublisher);

        // Resolve circular dependency: TaskScheduler needs ScheduledTaskExecutor,
        // ScheduledTaskManager needs TaskScheduler, and ScheduledTaskManager IS the executor.
        // The hole is dug once, here, so that no caller has to dig its own.
        final AtomicReference<ScheduledTaskManager> taskManagerRef = new AtomicReference<>();
        final TaskScheduler scheduler = resolveScheduler(taskId -> taskManagerRef.get().executeTask(taskId));

        final ScheduledTaskManager taskManager = ScheduledTaskManager.builder().taskRepository(taskRepository)
                .historyRepository(historyRepository).routineExecutor(routineExecutor).taskScheduler(scheduler)
                .eventPublisher(eventPublisher).quotaManager(quotaManager).executionGuard(executionGuard)
                .interruptBus(interruptBus).build();
        taskManagerRef.set(taskManager);

        return new SchedulingEngine(taskManager, routineExecutor, scheduler, eventPublisher, interruptBus);
    }

    private TaskScheduler resolveScheduler(ScheduledTaskExecutor executor) {
        if (taskSchedulerFactory != null) {
            final TaskScheduler created = taskSchedulerFactory.create(executor);
            if (created == null) {
                throw new IllegalStateException("Task scheduler factory returned null");
            }
            return created;
        }
        // An already-built scheduler was wired to its executor by whoever built it; this one is not ours to feed.
        return taskScheduler != null ? taskScheduler : new InMemoryTaskScheduler(executor);
    }
}
