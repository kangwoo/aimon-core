package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.SchedulingSpec;
import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.bootstrap.spec.VirtualFileSystemFactory;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.TaskScheduler;
import at.aimon.core.scheduling.scheduler.TaskSchedulerFactory;
import at.aimon.scheduling.quartz.QuartzTaskScheduler;
import at.aimon.session.routing.DeploymentMode;

/**
 * Tests for the three slices that contribute specs rather than components.
 *
 * <p>
 * These run each slice on its own, without {@code AimonAutoConfiguration}, so nothing assembles a stack. That is
 * the point: a supplied {@link VirtualFileSystem} here is a Mockito mock, and a stack built against a mock file
 * system would fail for reasons that have nothing to do with what is being asserted. What these check is that
 * the right *material* reaches the spec — the assembly of that material is
 * {@code AimonAutoConfigurationTest}'s subject.
 */
class AimonSpecSliceTest {

    /**
     * Both runners carry the two properties {@code AimonProperties} validates on binding.
     *
     * <p>
     * Even a slice that has no use for an agent name gets one: the properties bean is shared, so its validation
     * runs wherever it is enabled. That is deliberate — the alternative is a per-slice properties class whose
     * validation depends on which slices happen to be active.
     */
    private static ApplicationContextRunner runnerFor(Class<?> autoConfiguration, Path workspace) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(autoConfiguration))
                .withPropertyValues("aimon.workspace.root=" + workspace,
                        "aimon.agent-defaults.default-agent=test-agent");
    }

    private static ApplicationContextRunner fileSystemRunner(Path workspace) {
        return runnerFor(AimonFileSystemAutoConfiguration.class, workspace);
    }

    private static ApplicationContextRunner sessionRunner(Path workspace) {
        return runnerFor(AimonSessionAutoConfiguration.class, workspace);
    }

    private static ApplicationContextRunner schedulingRunner(Path workspace) {
        return runnerFor(AimonSchedulingAutoConfiguration.class, workspace);
    }

    @Test
    @DisplayName("scheduling is off unless a backend is named")
    void schedulingIsOffByDefault(@TempDir Path workspace) {
        // Not a conservative default so much as an honest one: the engine holds a scheduler thread pool and its
        // shutdown drains running jobs, and a stack with no cron task should pay for neither.
        schedulingRunner(workspace).run(ctx -> {
            final SchedulingSpec spec = ctx.getBean(SchedulingSpec.class);
            assertThat(spec.isEnabled()).isFalse();
            assertThat(spec.getTaskSchedulerFactory()).isEmpty();
        });
    }

    @Test
    @DisplayName("in-memory turns the engine on and supplies no scheduler of its own")
    void inMemoryEnablesTheEngineWithoutAScheduler(@TempDir Path workspace) {
        // Both halves matter. A factory here would mean the engine's own default was being replaced by something
        // this slice invented, and the empty factory is what says it is not.
        schedulingRunner(workspace).withPropertyValues("aimon.scheduling.backend=in-memory").run(ctx -> {
            final SchedulingSpec spec = ctx.getBean(SchedulingSpec.class);
            assertThat(spec.isEnabled()).isTrue();
            assertThat(spec.getTaskSchedulerFactory()).isEmpty();
            assertThat(spec.getTaskScheduler()).isEmpty();
        });
    }

    @Test
    @DisplayName("quartz borrows the application's scheduler, and the factory is called with the engine's executor")
    void quartzBorrowsTheApplicationScheduler(@TempDir Path workspace) {
        schedulingRunner(workspace).withPropertyValues("aimon.scheduling.backend=quartz")
                .withUserConfiguration(ApplicationQuartzConfiguration.class).run(ctx -> {
                    final TaskSchedulerFactory factory = ctx.getBean(SchedulingSpec.class).getTaskSchedulerFactory()
                            .orElseThrow();
                    // Calling it here is the point: a factory that could only be built once the executor existed is
                    // the whole reason the spec carries a factory rather than a scheduler.
                    final TaskScheduler scheduler = factory.create(taskId -> {
                    });
                    assertThat(scheduler).isInstanceOf(QuartzTaskScheduler.class);
                    assertThat(((QuartzTaskScheduler) scheduler).getQuartzScheduler())
                            .isSameAs(ApplicationQuartzConfiguration.INSTANCE);
                });
    }

    @Test
    @DisplayName("quartz builds its own scheduler when told not to borrow")
    void quartzBuildsItsOwnSchedulerWhenAsked(@TempDir Path workspace) {
        schedulingRunner(workspace).withPropertyValues("aimon.scheduling.backend=quartz",
                "aimon.scheduling.quartz.use-application-scheduler=false",
                "aimon.scheduling.quartz.instance-name=test-scheduler", "aimon.scheduling.quartz.thread-count=2")
                .withUserConfiguration(ApplicationQuartzConfiguration.class).run(ctx -> {
                    final QuartzTaskScheduler scheduler = (QuartzTaskScheduler) ctx.getBean(SchedulingSpec.class)
                            .getTaskSchedulerFactory().orElseThrow().create(taskId -> {
                            });
                    try {
                        // A Scheduler bean is present and deliberately not used — the property, not the bean, is
                        // what decides, so an application that has Quartz for its own reasons can still say
                        // "build me a separate one".
                        assertThat(scheduler.getQuartzScheduler()).isNotSameAs(ApplicationQuartzConfiguration.INSTANCE);
                        assertThat(scheduler.getQuartzScheduler().getSchedulerName()).isEqualTo("test-scheduler");
                    } finally {
                        scheduler.getQuartzScheduler().shutdown(false);
                    }
                });
    }

    @Test
    @DisplayName("borrowing without a Scheduler bean fails naming the starter that supplies one")
    void borrowingWithoutASchedulerBeanFailsByName(@TempDir Path workspace) {
        // The symptom without this is an AIMON stack that assembles cleanly and never fires anything, because the
        // engine would have had to be handed a scheduler that does not exist.
        schedulingRunner(workspace).withPropertyValues("aimon.scheduling.backend=quartz")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SCHEDULING_QUARTZ_USE_APPLICATION_SCHEDULER)
                        .hasStackTraceContaining("spring-boot-starter-quartz"));
    }

    @Test
    @DisplayName("selecting quartz without the adapter on the classpath fails naming the module")
    void quartzSelectorWithoutTheAdapterFails(@TempDir Path workspace) {
        // The @ConditionalOnClass branch backs off silently, as it must; this is the bean that turns that silence
        // back into a sentence about a missing dependency instead of a scheduler that never appears.
        schedulingRunner(workspace).withPropertyValues("aimon.scheduling.backend=quartz")
                .withClassLoader(new FilteredClassLoader(QuartzTaskScheduler.class))
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SCHEDULING_BACKEND)
                        .hasStackTraceContaining("aimon-scheduling-quartz"));
    }

    @Test
    @DisplayName("a scheduler factory bean under a backend that cannot use it is refused")
    void schedulerFactoryUnderTheWrongBackendIsRefused(@TempDir Path workspace) {
        // Ignoring it would be the worse outcome by a distance: a deployment that wrote a clustered scheduler
        // factory would get the in-memory one, and would find out at the first double-fire across two nodes.
        schedulingRunner(workspace).withPropertyValues("aimon.scheduling.backend=in-memory")
                .withUserConfiguration(ApplicationSchedulerFactoryConfiguration.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SCHEDULING_BACKEND)
                        .hasStackTraceContaining(TaskSchedulerFactory.class.getSimpleName()));
    }

    @Test
    @DisplayName("a task repository bean reaches the spec on every backend that builds an engine")
    void taskRepositoryBeanReachesTheSpec(@TempDir Path workspace) {
        // There is no property for this and there cannot be — the only implementation that ships is in-memory, so a
        // durable one is written rather than selected, and a bean is how a written thing arrives.
        schedulingRunner(workspace).withPropertyValues("aimon.scheduling.backend=in-memory")
                .withUserConfiguration(ApplicationTaskRepositoryConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(SchedulingSpec.class).getTaskRepository())
                        .containsSame(ApplicationTaskRepositoryConfiguration.INSTANCE));

        schedulingRunner(workspace)
                .withPropertyValues("aimon.scheduling.backend=quartz",
                        "aimon.scheduling.quartz.use-application-scheduler=false")
                .withUserConfiguration(ApplicationTaskRepositoryConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(SchedulingSpec.class).getTaskRepository())
                        .containsSame(ApplicationTaskRepositoryConfiguration.INSTANCE));
    }

    @Test
    @DisplayName("a task repository bean under a backend that builds no engine is refused")
    void taskRepositoryWithoutAnEngineIsRefused(@TempDir Path workspace) {
        // Same shape as the stray factory above: the property is the selector, so a repository nothing will ever
        // read means either the bean or the backend is a mistake, and this class cannot tell which.
        schedulingRunner(workspace).withPropertyValues("aimon.scheduling.backend=none")
                .withUserConfiguration(ApplicationTaskRepositoryConfiguration.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SCHEDULING_BACKEND)
                        .hasStackTraceContaining(ScheduledTaskRepository.class.getSimpleName()));
    }

    @Test
    @DisplayName("an application scheduling spec replaces the slice entirely")
    void applicationSchedulingSpecWins(@TempDir Path workspace) {
        // The escape hatch for everything this slice declines to expose — a JDBC job store, a scheduler from
        // somewhere else entirely. The properties are then not consulted at all, which is why the backend stays
        // at its default here and the spec still comes back enabled.
        schedulingRunner(workspace).withUserConfiguration(ApplicationSchedulingSpecConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(SchedulingSpec.class))
                        .isSameAs(ApplicationSchedulingSpecConfiguration.INSTANCE));
    }

    @Test
    @DisplayName("the workspace root becomes a stack-owned local file system, created if absent")
    void workspaceRootBecomesStackOwned(@TempDir Path parent) {
        final Path workspace = parent.resolve("not-yet-there");
        fileSystemRunner(workspace).run(ctx -> {
            final FileSystemSpec spec = ctx.getBean(FileSystemSpec.class);
            assertThat(spec.isStackOwned()).isTrue();
            assertThat(spec.getWorkspaceRoot()).contains(workspace.toString());
            assertThat(spec.getInstance()).isEmpty();
            assertThat(Files.isDirectory(workspace)).isTrue();
        });
    }

    @Test
    @DisplayName("a workspace root that is a regular file fails naming both properties involved")
    void unusableWorkspaceRootNamesTheProperties(@TempDir Path parent) throws Exception {
        final Path file = Files.createFile(parent.resolve("a-file"));
        fileSystemRunner(file).run(
                ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(AimonProperties.WORKSPACE_ROOT)
                        .hasStackTraceContaining(AimonProperties.WORKSPACE_ENSURE_WRITABLE));
    }

    @Test
    @DisplayName("an application file system is borrowed, never owned")
    void applicationFileSystemIsBorrowed(@TempDir Path workspace) {
        // isStackOwned() false is the whole assertion: the stack must not close a file system it was handed,
        // because the application that created it is still using it after the stack goes away.
        fileSystemRunner(workspace).withUserConfiguration(ApplicationFileSystemConfiguration.class).run(ctx -> {
            final FileSystemSpec spec = ctx.getBean(FileSystemSpec.class);
            assertThat(spec.isStackOwned()).isFalse();
            assertThat(spec.getInstance()).contains(ApplicationFileSystemConfiguration.INSTANCE);
        });
    }

    @Test
    @DisplayName("a file system factory is stack-owned, because nobody else can close what it makes")
    void applicationFileSystemFactoryIsStackOwned(@TempDir Path workspace) {
        // The mirror image of the case above, and the reason ownership cannot simply follow "did the
        // application supply it". A factory is asked once per runtime id, so its products are per-tenant objects
        // the bean container never saw; if the stack did not close them, nothing would. getInstance() being
        // empty is the same claim from the other side — there is no one file system to hand out.
        fileSystemRunner(workspace).withUserConfiguration(ApplicationFileSystemFactoryConfiguration.class).run(ctx -> {
            final FileSystemSpec spec = ctx.getBean(FileSystemSpec.class);
            assertThat(spec.isStackOwned()).isTrue();
            assertThat(spec.getInstance()).isEmpty();
            assertThat(spec.getFactory()).contains(ApplicationFileSystemFactoryConfiguration.INSTANCE);
        });
    }

    @Test
    @DisplayName("a file system and a factory together are rejected by naming both types")
    void fileSystemAndFactoryTogetherAreRejected(@TempDir Path workspace) {
        // Picking one would be the worse failure: choosing the instance silently gives every tenant one root,
        // which is precisely what a deployment defines a factory to avoid.
        fileSystemRunner(workspace)
                .withUserConfiguration(ApplicationFileSystemConfiguration.class,
                        ApplicationFileSystemFactoryConfiguration.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(VirtualFileSystem.class.getName())
                        .hasStackTraceContaining(VirtualFileSystemFactory.class.getName()));
    }

    @Test
    @DisplayName("the in-memory default leaves the record store unset for the stack to notice")
    void inMemoryLeavesTheRecordStoreUnset(@TempDir Path workspace) {
        // Setting an explicit InMemorySessionRecordStore here would satisfy the builder's check and swallow the
        // session-durability degradation, which is the only signal a production deployment gets.
        sessionRunner(workspace).run(ctx -> {
            final SessionSpec spec = ctx.getBean(SessionSpec.class);
            assertThat(spec.getRecordStore()).isEmpty();
            assertThat(spec.getDrainTimeout()).isEqualTo(SessionSpec.DEFAULT_DRAIN_TIMEOUT);
            assertThat(spec.getIdleTtl()).contains(Duration.ofMinutes(30));
            assertThat(spec.getMaxCachedSessions()).contains(1000);
        });
    }

    @Test
    @DisplayName("selecting a durable store without the module on the classpath fails by name")
    void durableSelectorWithoutAStoreFails(@TempDir Path workspace) {
        // The alternative is to start on in-memory anyway, which turns a missing dependency into data that
        // disappears on restart — discovered in production rather than at startup.
        sessionRunner(workspace).withPropertyValues("aimon.session.store=postgres")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SESSION_STORE).hasStackTraceContaining("postgres"));
    }

    @Test
    @DisplayName("a typo in the store selector is a binding failure, not a silent fallback")
    void typoInTheSelectorFailsBinding(@TempDir Path workspace) {
        sessionRunner(workspace).withPropertyValues("aimon.session.store=postgress").run(
                ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(AimonProperties.SESSION_STORE));
    }

    @Test
    @DisplayName("an application record store is adopted and the selector agrees")
    void applicationRecordStoreIsAdopted(@TempDir Path workspace) {
        sessionRunner(workspace).withPropertyValues("aimon.session.store=postgres")
                .withUserConfiguration(ApplicationRecordStoreConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(SessionSpec.class).getRecordStore())
                        .contains(ApplicationRecordStoreConfiguration.INSTANCE));
    }

    @Test
    @DisplayName("cache properties reach the spec")
    void cachePropertiesReachTheSpec(@TempDir Path workspace) {
        sessionRunner(workspace).withPropertyValues("aimon.session.cache.max-entries=7",
                "aimon.session.cache.idle-ttl=90s", "aimon.session.shutdown-drain-timeout=5s").run(ctx -> {
                    final SessionSpec spec = ctx.getBean(SessionSpec.class);
                    assertThat(spec.getMaxCachedSessions()).contains(7);
                    assertThat(spec.getIdleTtl()).contains(Duration.ofSeconds(90));
                    assertThat(spec.getDrainTimeout()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    @DisplayName("distributed mode without the four beans names all four, with where they come from")
    void distributedWithoutTheSpiBeansNamesAllFour(@TempDir Path workspace) {
        // Turning the mode on is one property; satisfying it is four beans from a module the application may
        // not even have on the classpath yet. Reporting them one restart at a time would be four restarts.
        distributedRunner(workspace).withUserConfiguration(ApplicationRecordStoreConfiguration.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SESSION_MODE)
                        .hasStackTraceContaining(SessionLeaseStore.class.getName())
                        .hasStackTraceContaining(SessionSignalBus.class.getName())
                        .hasStackTraceContaining(SessionInbox.class.getName())
                        .hasStackTraceContaining(IdempotencyStore.class.getName())
                        .hasStackTraceContaining("RedisSessionLeaseStore"));
    }

    @Test
    @DisplayName("only the beans that are actually missing are named")
    void onlyTheMissingSpiBeansAreNamed(@TempDir Path workspace) {
        // The message counts, so a partially wired deployment must not be told to define what it has already
        // defined — that reads as "the bean I wrote is not being seen" and sends the reader after the wrong bug.
        distributedRunner(workspace)
                .withUserConfiguration(ApplicationRecordStoreConfiguration.class, ThreeOfFourSpiConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(ctx.getStartupFailure()).getMessage())
                            .contains("1 of the 4").contains(IdempotencyStore.class.getName())
                            .doesNotContain(SessionLeaseStore.class.getName());
                });
    }

    @Test
    @DisplayName("a complete distributed configuration reaches the spec whole")
    void distributedConfigurationReachesTheSpec(@TempDir Path workspace) {
        distributedRunner(workspace).withUserConfiguration(ApplicationRecordStoreConfiguration.class,
                ThreeOfFourSpiConfiguration.class, IdempotencyStoreConfiguration.class).run(ctx -> {
                    final SessionSpec spec = ctx.getBean(SessionSpec.class);
                    assertThat(spec.getMode()).isEqualTo(DeploymentMode.DISTRIBUTED);
                    assertThat(spec.getNodeId()).contains("pod-a");
                    assertThat(spec.getRecordStore()).contains(ApplicationRecordStoreConfiguration.INSTANCE);
                    assertThat(spec.getLeaseStore()).contains(ThreeOfFourSpiConfiguration.LEASE_STORE);
                    assertThat(spec.getSignalBus()).contains(ThreeOfFourSpiConfiguration.SIGNAL_BUS);
                    assertThat(spec.getInbox()).contains(ThreeOfFourSpiConfiguration.INBOX);
                    assertThat(spec.getIdempotencyStore()).contains(IdempotencyStoreConfiguration.INSTANCE);
                });
    }

    @Test
    @DisplayName("an in-memory record store bean is refused even though the selector and the beans all agree")
    void inMemoryRecordStoreBeanIsRefusedInDistributedMode(@TempDir Path workspace) {
        // Everything this slice can see is correct here: store=redis, four SPI beans, a node id. The bean is an
        // InMemorySessionRecordStore all the same — the deployment supplied one, so no selector says otherwise.
        // SessionSpec is the last layer that can tell, and it does.
        distributedRunner(workspace)
                .withUserConfiguration(InMemoryRecordStoreConfiguration.class, ThreeOfFourSpiConfiguration.class,
                        IdempotencyStoreConfiguration.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(InMemorySessionRecordStore.class.getSimpleName()));
    }

    @Test
    @DisplayName("single node passes supplied SPI beans through rather than dropping them")
    void singleNodePassesSuppliedSpisThrough(@TempDir Path workspace) {
        // Dropping them would make "mode=distributed" mean something different from "the beans that mode needs",
        // and would silently un-share a lease store the application deliberately made shared.
        sessionRunner(workspace).withUserConfiguration(ThreeOfFourSpiConfiguration.class).run(ctx -> {
            final SessionSpec spec = ctx.getBean(SessionSpec.class);
            assertThat(spec.getMode()).isEqualTo(DeploymentMode.SINGLE_NODE);
            assertThat(spec.getLeaseStore()).contains(ThreeOfFourSpiConfiguration.LEASE_STORE);
            assertThat(spec.getIdempotencyStore()).isEmpty();
        });
    }

    /** Distributed mode with the two properties {@code AimonProperties} insists on before any bean is looked at. */
    private static ApplicationContextRunner distributedRunner(Path workspace) {
        return sessionRunner(workspace).withPropertyValues("aimon.session.mode=distributed",
                "aimon.session.node-id=pod-a", "aimon.session.store=redis");
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationFileSystemConfiguration {

        static final VirtualFileSystem INSTANCE = mock(VirtualFileSystem.class);

        @Bean
        VirtualFileSystem applicationFileSystem() {
            return INSTANCE;
        }
    }

    /** Stands in for a host that gives each tenant its own storage — a bucket prefix, a GridFS database. */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationFileSystemFactoryConfiguration {

        static final VirtualFileSystemFactory INSTANCE = agentRuntimeId -> mock(VirtualFileSystem.class);

        @Bean
        VirtualFileSystemFactory applicationFileSystemFactory() {
            return INSTANCE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationRecordStoreConfiguration {

        static final SessionRecordStore INSTANCE = mock(SessionRecordStore.class);

        @Bean
        SessionRecordStore applicationRecordStore() {
            return INSTANCE;
        }
    }

    /**
     * A record store that is real rather than a mock, and node-local.
     *
     * <p>
     * Mocking it would not do: the check it exists to trip is an {@code instanceof}, so the type has to be the
     * actual one a deployment would reach for when it wants "sessions, but I have not picked a database yet".
     */
    @Configuration(proxyBeanMethods = false)
    static class InMemoryRecordStoreConfiguration {

        @Bean
        SessionRecordStore inMemoryRecordStore() {
            return new InMemorySessionRecordStore();
        }
    }

    /** Three of the four session SPIs — the fourth is added separately so its absence can be asserted. */
    @Configuration(proxyBeanMethods = false)
    static class ThreeOfFourSpiConfiguration {

        static final SessionLeaseStore LEASE_STORE = mock(SessionLeaseStore.class);

        static final SessionSignalBus SIGNAL_BUS = mock(SessionSignalBus.class);

        static final SessionInbox INBOX = mock(SessionInbox.class);

        @Bean
        SessionLeaseStore applicationLeaseStore() {
            return LEASE_STORE;
        }

        @Bean
        SessionSignalBus applicationSignalBus() {
            return SIGNAL_BUS;
        }

        @Bean
        SessionInbox applicationInbox() {
            return INBOX;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IdempotencyStoreConfiguration {

        static final IdempotencyStore INSTANCE = mock(IdempotencyStore.class);

        @Bean
        IdempotencyStore applicationIdempotencyStore() {
            return INSTANCE;
        }
    }

    /**
     * The {@code Scheduler} bean {@code spring-boot-starter-quartz} would have contributed.
     *
     * <p>
     * A real one rather than a mock, because the borrowed path registers its executor in the scheduler's context
     * and a mock would accept that silently while proving nothing.
     */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationQuartzConfiguration {

        static final Scheduler INSTANCE = createApplicationScheduler();

        @Bean
        Scheduler applicationScheduler() {
            return INSTANCE;
        }

        private static Scheduler createApplicationScheduler() {
            final Properties properties = new Properties();
            properties.setProperty("org.quartz.scheduler.instanceName", "application-scheduler");
            properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
            properties.setProperty("org.quartz.threadPool.threadCount", "1");
            properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
            try {
                return new StdSchedulerFactory(properties).getScheduler();
            } catch (SchedulerException e) {
                throw new IllegalStateException("Could not build the stand-in application scheduler", e);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationSchedulerFactoryConfiguration {

        @Bean
        TaskSchedulerFactory applicationSchedulerFactory() {
            return executor -> mock(TaskScheduler.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationSchedulingSpecConfiguration {

        static final SchedulingSpec INSTANCE = SchedulingSpec.enabled();

        @Bean
        SchedulingSpec applicationSchedulingSpec() {
            return INSTANCE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationTaskRepositoryConfiguration {

        static final ScheduledTaskRepository INSTANCE = new InMemoryScheduledTaskRepository();

        @Bean
        ScheduledTaskRepository applicationTaskRepository() {
            return INSTANCE;
        }
    }
}
