package at.aimon.spring.boot.autoconfigure;

import static at.aimon.spring.boot.autoconfigure.AimonProperties.SCHEDULING_BACKEND;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.SCHEDULING_BACKEND_QUARTZ;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.SCHEDULING_QUARTZ_USE_APPLICATION_SCHEDULER;

import org.quartz.Scheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.bootstrap.spec.SchedulingSpec;
import at.aimon.core.scheduling.ScheduledExecutionGuard;
import at.aimon.core.scheduling.ScheduledTaskInterruptBus;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.ScheduledTaskExecutor;
import at.aimon.core.scheduling.scheduler.TaskScheduler;
import at.aimon.core.scheduling.scheduler.TaskSchedulerFactory;
import at.aimon.scheduling.quartz.QuartzTaskScheduler;
import at.aimon.scheduling.quartz.QuartzTaskSchedulerBuilder;

/**
 * Decides whether the stack runs a scheduling engine, and on which scheduler.
 *
 * <p>
 * Off by default. The engine is not free — it holds a thread pool and its shutdown waits — and a stack that
 * never registers a cron task should pay for neither. {@code in-memory} turns it on with the engine's own
 * scheduler; {@code quartz} turns it on over the {@code aimon-scheduling-quartz} adapter, which is
 * {@code compileOnly} here so that an application not asking for it does not carry Quartz.
 *
 * <h2>A factory, not a scheduler</h2>
 *
 * <p>
 * The bean this slice contributes is a {@link TaskSchedulerFactory}, and the indirection is load-bearing. A
 * {@link TaskScheduler} needs the executor it dispatches to; that executor is the engine's task manager; the task
 * manager needs the scheduler. Building the scheduler here, before the stack exists, means closing over a reference
 * nothing has filled in — and getting that wrong is silent, because the scheduler starts and every firing dies alone
 * on a background thread. Handing over a factory puts the scheduler's construction where the executor already exists.
 *
 * <h2>Borrowing the application's Quartz scheduler</h2>
 *
 * <p>
 * {@code aimon.scheduling.quartz.use-application-scheduler} defaults to {@code true}: AIMON registers its jobs with
 * the {@code Scheduler} bean the application already has, rather than standing a second Quartz instance and second
 * thread pool up beside it. A borrowed scheduler is neither started nor stopped by AIMON, which has one consequence
 * worth stating — an application that leaves its scheduler in standby ({@code spring.quartz.auto-startup=false}) gets
 * no AIMON cron firings, and nothing here will complain, because the jobs register perfectly well and simply wait.
 *
 * <p>
 * The remaining {@code aimon.scheduling.quartz.*} properties configure a scheduler AIMON builds, so setting them
 * while borrowing is refused rather than ignored — {@code AimonProperties.validateScheduling()} does that, and the
 * reason it can is that each of those properties is nullable, which makes "set to the default" distinguishable from
 * "not set".
 *
 * <h2>What this slice deliberately does not expose</h2>
 *
 * <p>
 * There is no property for Quartz's JDBC job store or its clustering, though the adapter's builder has both. The
 * scheduled <em>tasks</em> are held in memory unless the application supplies a {@link ScheduledTaskRepository} bean
 * — {@code InMemoryScheduledTaskRepository} is the only implementation that ships — so a durable job store on its own
 * would preserve the triggers and lose everything they point at, and each one would fire after a restart into "task
 * not found". That is worse than no durability, because it looks like durability. The stack says so at startup
 * through its {@code scheduling-durability} degradation, and this slice declines to offer the property that would
 * make the mismatch reachable by configuration alone.
 *
 * <p>
 * A bean is a different matter from a property, which is why the repository arrives as one. A property offers a
 * durable-sounding switch over machinery that is not there; supplying a repository means the application wrote the
 * durable half itself, and refusing to accept it would not prevent any mismatch — it would only push the deployment
 * off {@code AimonStack} and onto a hand-built engine, losing the stack's ordered teardown on the way out.
 */
@AutoConfiguration
@ConditionalOnProperty(name = AimonProperties.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AimonProperties.class)
public class AimonSchedulingAutoConfiguration {

    /**
     * Turns {@code aimon.scheduling.backend} into the spec the stack builds its engine from.
     *
     * <p>
     * The factory is resolved through a provider because only the Quartz branch below publishes one. Finding one
     * under a backend that cannot use it is a contradiction between a property and a bean, and it is refused for the
     * same reason the session slice refuses its own: the property is the selector, so a bean that disagrees with it
     * means one of the two is a mistake and neither this class nor the deployment can tell which.
     *
     * <p>
     * A {@link ScheduledTaskRepository} bean, if the application publishes one, is carried into the spec. There is no
     * property for it and there cannot be: the only implementation that ships is in-memory, so choosing a durable one
     * means writing it, and a bean is how a written thing arrives. Publishing one under a backend that builds no
     * engine is refused for the same reason a stray factory is — the property is the selector, and a bean that
     * disagrees with it means one of the two is a mistake.
     *
     * <p>
     * A {@link ScheduledTaskInterruptBus} bean is carried in on the same terms and for the same reason — the only
     * implementations that ship reach one node and one JVM respectively, so a cluster-wide one is written by the
     * application. Without it a cancellation stops the run on the node it was entered on and leaves the run on the
     * node that fired to work through its remaining steps. A {@link ScheduledExecutionGuard} bean is the third of the
     * same kind, guarding the opposite end — which node is allowed to <em>start</em> a fire.
     *
     * @param properties
     *            the bound configuration
     * @param factories
     *            the scheduler factory, when the Quartz branch or the application published one
     * @param repositories
     *            the task repository, when the application published one
     * @param interruptBuses
     *            the cross-node interrupt bus, when the application published one
     * @param executionGuards
     *            the distributed execution guard, when the application published one
     * @return the spec
     */
    @Bean
    @ConditionalOnMissingBean(SchedulingSpec.class)
    SchedulingSpec aimonSchedulingSpec(AimonProperties properties, ObjectProvider<TaskSchedulerFactory> factories,
            ObjectProvider<ScheduledTaskRepository> repositories,
            ObjectProvider<ScheduledTaskInterruptBus> interruptBuses,
            ObjectProvider<ScheduledExecutionGuard> executionGuards) {
        final SchedulingBackend backend = properties.getScheduling().getBackend();
        final TaskSchedulerFactory factory = factories.getIfAvailable();
        final ScheduledTaskRepository repository = repositories.getIfAvailable();
        final ScheduledTaskInterruptBus interruptBus = interruptBuses.getIfAvailable();
        final ScheduledExecutionGuard executionGuard = executionGuards.getIfAvailable();

        if (backend == SchedulingBackend.QUARTZ) {
            if (factory == null) {
                throw new IllegalStateException(
                        SCHEDULING_BACKEND + "=quartz but no " + TaskSchedulerFactory.class.getSimpleName()
                                + " bean is defined. That bean comes from this starter's Quartz branch,"
                                + " which needs aimon-scheduling-quartz on the classpath — add the module, or set "
                                + SCHEDULING_BACKEND + " to in-memory or none.");
            }
            return withApplicationBeans(SchedulingSpec.enabled(factory), repository, interruptBus, executionGuard);
        }
        if (factory != null) {
            throw new IllegalStateException(
                    "A " + TaskSchedulerFactory.class.getSimpleName() + " bean is defined but " + SCHEDULING_BACKEND
                            + "=" + AimonProperties.asPropertyValue(backend) + " builds no scheduler from it. Set "
                            + SCHEDULING_BACKEND + "=quartz to use it, or remove the bean.");
        }
        if (backend == SchedulingBackend.IN_MEMORY) {
            return withApplicationBeans(SchedulingSpec.enabled(), repository, interruptBus, executionGuard);
        }
        if (repository != null) {
            throw new IllegalStateException("A " + ScheduledTaskRepository.class.getSimpleName()
                    + " bean is defined but " + SCHEDULING_BACKEND + "=" + AimonProperties.asPropertyValue(backend)
                    + " builds no scheduling engine to read it. Name a backend, or remove the bean.");
        }
        if (interruptBus != null) {
            throw new IllegalStateException("A " + ScheduledTaskInterruptBus.class.getSimpleName()
                    + " bean is defined but " + SCHEDULING_BACKEND + "=" + AimonProperties.asPropertyValue(backend)
                    + " builds no scheduling engine to publish or subscribe on it. Name a backend, or remove the"
                    + " bean.");
        }
        if (executionGuard != null) {
            throw new IllegalStateException("A " + ScheduledExecutionGuard.class.getSimpleName()
                    + " bean is defined but " + SCHEDULING_BACKEND + "=" + AimonProperties.asPropertyValue(backend)
                    + " builds no scheduling engine to consult it. Name a backend, or remove the bean.");
        }
        return SchedulingSpec.disabled();
    }

    private static SchedulingSpec withApplicationBeans(SchedulingSpec spec, ScheduledTaskRepository repository,
            ScheduledTaskInterruptBus interruptBus, ScheduledExecutionGuard executionGuard) {
        SchedulingSpec result = repository == null ? spec : spec.withTaskRepository(repository);
        result = interruptBus == null ? result : result.withInterruptBus(interruptBus);
        return executionGuard == null ? result : result.withExecutionGuard(executionGuard);
    }

    /**
     * Quartz branch.
     *
     * <p>
     * Nested and {@code @ConditionalOnClass}-guarded so the references to Quartz types are never loaded when the
     * adapter is absent — Spring reads the condition from bytecode and skips the class without asking the
     * classloader for anything inside it. The bean it publishes is typed as the core's {@link TaskSchedulerFactory}
     * rather than anything from the adapter, which is what lets the outer method above name that type without
     * needing Quartz itself.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({QuartzTaskScheduler.class, Scheduler.class})
    @ConditionalOnProperty(name = SCHEDULING_BACKEND, havingValue = SCHEDULING_BACKEND_QUARTZ)
    static class QuartzConfiguration {

        @Bean
        @ConditionalOnMissingBean(TaskSchedulerFactory.class)
        TaskSchedulerFactory aimonQuartzTaskSchedulerFactory(AimonProperties properties,
                ObjectProvider<Scheduler> schedulers) {
            final AimonProperties.Quartz quartz = properties.getScheduling().getQuartz();
            if (!quartz.usesApplicationScheduler()) {
                return executor -> buildScheduler(quartz, executor);
            }
            // Resolved now rather than inside the lambda, so a missing bean fails at startup rather than at the
            // moment the stack is assembled — and so the dependency edge that orders destruction is registered.
            final Scheduler application = schedulers.getIfAvailable();
            if (application == null) {
                throw new IllegalStateException(SCHEDULING_QUARTZ_USE_APPLICATION_SCHEDULER + "=true but no "
                        + Scheduler.class.getName() + " bean is defined. That bean comes from"
                        + " spring-boot-starter-quartz — add it, or set " + SCHEDULING_QUARTZ_USE_APPLICATION_SCHEDULER
                        + "=false to have AIMON build and own a scheduler of its own.");
            }
            return executor -> QuartzTaskScheduler.borrowing(application, executor);
        }

        private static TaskScheduler buildScheduler(AimonProperties.Quartz quartz, ScheduledTaskExecutor executor) {
            final QuartzTaskSchedulerBuilder builder = QuartzTaskSchedulerBuilder.create().taskExecutor(executor)
                    .waitForJobsOnShutdown(quartz.waitsForJobsOnShutdown());
            if (quartz.getInstanceName() != null) {
                builder.instanceName(quartz.getInstanceName());
            }
            if (quartz.getThreadCount() != null) {
                builder.threadCount(quartz.getThreadCount());
            }
            if (quartz.getDaemonThreads() != null) {
                builder.daemonThreads(quartz.getDaemonThreads());
            }
            return builder.build();
        }
    }
}
