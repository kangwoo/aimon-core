package at.aimon.spring.boot.autoconfigure;

import static at.aimon.spring.boot.autoconfigure.AimonProperties.SCHEDULING_BACKEND;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.SCHEDULING_BACKEND_QUARTZ;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.SCHEDULING_QUARTZ_USE_APPLICATION_SCHEDULER;

import org.quartz.Scheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.bootstrap.spec.SchedulingSpec;
import at.aimon.core.scheduling.RoutineExecutionAttributes;
import at.aimon.core.scheduling.ScheduledExecutionGuard;
import at.aimon.core.scheduling.ScheduledTaskInterruptBus;
import at.aimon.core.scheduling.ScheduledTaskPolicy;
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
 * — the starter itself configures none — so a durable job store on its own
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
     * Names of the two beans this slice publishes, which the SPIs they gather are made dependencies of.
     *
     * <p>
     * Constants so the {@code @Bean}s and the edges registered against them cannot drift apart.
     */
    static final String SCHEDULING_SPEC_BEAN = "aimonSchedulingSpec";

    /** @see #SCHEDULING_SPEC_BEAN */
    static final String QUARTZ_TASK_SCHEDULER_FACTORY_BEAN = "aimonQuartzTaskSchedulerFactory";

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
     * same kind, guarding the opposite end — which node is allowed to <em>start</em> a fire. A
     * {@link RoutineExecutionAttributes} bean is the fourth: the host's answer, per routine run, to what
     * {@code SubmitOptions.executionAttribute(...)} answers per turn, so an environment provider can place routine
     * runs too. A {@link ScheduledTaskPolicy} bean is the fifth: the host's rules for what the scheduling tools may
     * schedule, list and cancel — the only check scheduled work gets, since routine steps run without hooks.
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
     * @param routineAttributes
     *            the routine execution attributes resolver, when the application published one
     * @param taskPolicies
     *            the scheduling tools' host policy, when the application published one
     * @param beanFactory
     *            the context's bean factory, used to register the destruction edges
     * @return the spec
     */
    @Bean(SCHEDULING_SPEC_BEAN)
    @ConditionalOnMissingBean(SchedulingSpec.class)
    @SuppressWarnings("checkstyle:ParameterNumber")
    SchedulingSpec aimonSchedulingSpec(AimonProperties properties, ObjectProvider<TaskSchedulerFactory> factories,
            ObjectProvider<ScheduledTaskRepository> repositories,
            ObjectProvider<ScheduledTaskInterruptBus> interruptBuses,
            ObjectProvider<ScheduledExecutionGuard> executionGuards,
            ObjectProvider<RoutineExecutionAttributes> routineAttributes,
            ObjectProvider<ScheduledTaskPolicy> taskPolicies, ConfigurableListableBeanFactory beanFactory) {
        final SchedulingBackend backend = properties.getScheduling().getBackend();
        final TaskSchedulerFactory factory = ApplicationBeans.resolve(factories, TaskSchedulerFactory.class,
                beanFactory, SCHEDULING_SPEC_BEAN);
        final ScheduledTaskRepository repository = ApplicationBeans.resolve(repositories, ScheduledTaskRepository.class,
                beanFactory, SCHEDULING_SPEC_BEAN);
        final ScheduledTaskInterruptBus interruptBus = ApplicationBeans.resolve(interruptBuses,
                ScheduledTaskInterruptBus.class, beanFactory, SCHEDULING_SPEC_BEAN);
        final ScheduledExecutionGuard executionGuard = ApplicationBeans.resolve(executionGuards,
                ScheduledExecutionGuard.class, beanFactory, SCHEDULING_SPEC_BEAN);
        final RoutineExecutionAttributes routineExecutionAttributes = ApplicationBeans.resolve(routineAttributes,
                RoutineExecutionAttributes.class, beanFactory, SCHEDULING_SPEC_BEAN);
        final ScheduledTaskPolicy taskPolicy = ApplicationBeans.resolve(taskPolicies, ScheduledTaskPolicy.class,
                beanFactory, SCHEDULING_SPEC_BEAN);
        final ApplicationSchedulingBeans beans = new ApplicationSchedulingBeans(repository, interruptBus,
                executionGuard, routineExecutionAttributes, taskPolicy);

        if (backend == SchedulingBackend.QUARTZ) {
            if (factory == null) {
                throw new IllegalStateException(
                        SCHEDULING_BACKEND + "=quartz but no " + TaskSchedulerFactory.class.getSimpleName()
                                + " bean is defined. That bean comes from this starter's Quartz branch,"
                                + " which needs aimon-scheduling-quartz on the classpath — add the module, or set "
                                + SCHEDULING_BACKEND + " to in-memory or none.");
            }
            return withApplicationBeans(SchedulingSpec.enabled(factory), beans);
        }
        if (factory != null) {
            throw new IllegalStateException(
                    "A " + TaskSchedulerFactory.class.getSimpleName() + " bean is defined but " + SCHEDULING_BACKEND
                            + "=" + AimonProperties.asPropertyValue(backend) + " builds no scheduler from it. Set "
                            + SCHEDULING_BACKEND + "=quartz to use it, or remove the bean.");
        }
        if (backend == SchedulingBackend.IN_MEMORY) {
            return withApplicationBeans(SchedulingSpec.enabled(), beans);
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
        if (routineExecutionAttributes != null) {
            throw new IllegalStateException("A " + RoutineExecutionAttributes.class.getSimpleName()
                    + " bean is defined but " + SCHEDULING_BACKEND + "=" + AimonProperties.asPropertyValue(backend)
                    + " builds no scheduling engine to ask it. Name a backend, or remove the bean.");
        }
        if (taskPolicy != null) {
            throw new IllegalStateException("A " + ScheduledTaskPolicy.class.getSimpleName() + " bean is defined but "
                    + SCHEDULING_BACKEND + "=" + AimonProperties.asPropertyValue(backend)
                    + " builds no scheduling engine whose tools would consult it. Name a backend, or remove the"
                    + " bean.");
        }
        return SchedulingSpec.disabled();
    }

    private static SchedulingSpec withApplicationBeans(SchedulingSpec spec, ApplicationSchedulingBeans beans) {
        SchedulingSpec result = beans.repository == null ? spec : spec.withTaskRepository(beans.repository);
        result = beans.interruptBus == null ? result : result.withInterruptBus(beans.interruptBus);
        result = beans.executionGuard == null ? result : result.withExecutionGuard(beans.executionGuard);
        result = beans.routineExecutionAttributes == null
                ? result
                : result.withRoutineExecutionAttributes(beans.routineExecutionAttributes);
        return beans.taskPolicy == null ? result : result.withTaskPolicy(beans.taskPolicy);
    }

    /** The optional application beans a spec carries, any of which may be null. */
    private static final class ApplicationSchedulingBeans {
        private final ScheduledTaskRepository repository;
        private final ScheduledTaskInterruptBus interruptBus;
        private final ScheduledExecutionGuard executionGuard;
        private final RoutineExecutionAttributes routineExecutionAttributes;
        private final ScheduledTaskPolicy taskPolicy;

        private ApplicationSchedulingBeans(ScheduledTaskRepository repository, ScheduledTaskInterruptBus interruptBus,
                ScheduledExecutionGuard executionGuard, RoutineExecutionAttributes routineExecutionAttributes,
                ScheduledTaskPolicy taskPolicy) {
            this.repository = repository;
            this.interruptBus = interruptBus;
            this.executionGuard = executionGuard;
            this.routineExecutionAttributes = routineExecutionAttributes;
            this.taskPolicy = taskPolicy;
        }
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

        @Bean(QUARTZ_TASK_SCHEDULER_FACTORY_BEAN)
        @ConditionalOnMissingBean(TaskSchedulerFactory.class)
        TaskSchedulerFactory aimonQuartzTaskSchedulerFactory(AimonProperties properties,
                ObjectProvider<Scheduler> schedulers, ConfigurableListableBeanFactory beanFactory) {
            final AimonProperties.Quartz quartz = properties.getScheduling().getQuartz();
            if (!quartz.usesApplicationScheduler()) {
                return executor -> buildScheduler(quartz, executor);
            }
            // Resolved now rather than inside the lambda, so a missing bean fails at startup rather than at the
            // moment the stack is assembled. Resolving is not what registers the edge destruction is ordered by
            // — this line used to say it was — so ApplicationBeans registers it, which matters more here than
            // almost anywhere: the scheduler is the application's and the jobs running on it are AIMON's.
            // This is also the entry that proved the registration incomplete: spring-boot-starter-quartz
            // publishes the Scheduler through a SchedulerFactoryBean, and matching only the singleton cache
            // found the factory rather than its product, so the one entry named as mattering most was the one
            // silently left without an edge. ApplicationBeans reads both caches for that reason.
            final Scheduler application = ApplicationBeans.resolve(schedulers, Scheduler.class, beanFactory,
                    QUARTZ_TASK_SCHEDULER_FACTORY_BEAN);
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
