/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.quartz.SchedulerException;
import org.quartz.utils.ConnectionProvider;

import at.aimon.core.scheduling.scheduler.ScheduledTaskExecutor;

class QuartzTaskSchedulerBuilderTest {

    private static final ScheduledTaskExecutor NO_OP_EXECUTOR = taskId -> {
    };

    @Test
    void testCreate_ReturnsBuilder() {
        // Act
        QuartzTaskSchedulerBuilder builder = QuartzTaskSchedulerBuilder.create();

        // Assert
        assertThat(builder).isNotNull();
    }

    @Test
    void testBuild_WithDefaults_CreatesScheduler() {
        // Act
        QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().taskExecutor(NO_OP_EXECUTOR).build();

        // Assert
        assertThat(scheduler).isNotNull();
        assertThat(scheduler.getQuartzScheduler()).isNotNull();
    }

    @Test
    void testBuild_WithCustomThreadCount_CreatesScheduler() {
        // Act
        QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().taskExecutor(NO_OP_EXECUTOR).threadCount(2)
                .build();

        // Assert
        assertThat(scheduler).isNotNull();
    }

    @Test
    void testThreadCount_InvalidValue_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> QuartzTaskSchedulerBuilder.create().threadCount(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Thread count must be at least 1");
    }

    @Test
    void testInstanceName_NullValue_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> QuartzTaskSchedulerBuilder.create().instanceName(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Instance name cannot be null");
    }

    @Test
    void testInstanceId_NullValue_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> QuartzTaskSchedulerBuilder.create().instanceId(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Instance ID cannot be null");
    }

    @Test
    void testThreadNamePrefix_NullValue_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> QuartzTaskSchedulerBuilder.create().threadNamePrefix(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Thread name prefix cannot be null");
    }

    @Test
    void testJdbcJobStore_NullUrl_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> QuartzTaskSchedulerBuilder.create().jdbcJobStore(null, "org.postgresql.Driver"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("JDBC URL cannot be null");
    }

    @Test
    void testJdbcJobStore_NullDriver_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> QuartzTaskSchedulerBuilder.create().jdbcJobStore("jdbc:h2:mem:test", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("JDBC driver cannot be null");
    }

    @Test
    void testDataSourceClass_NullValue_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> QuartzTaskSchedulerBuilder.create().dataSourceClass(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("DataSource class cannot be null");
    }

    @Test
    void testClusterCheckinInterval_InvalidValue_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> QuartzTaskSchedulerBuilder.create().clusterCheckinInterval(500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cluster check-in interval must be at least 1000ms");
    }

    @Test
    void testBuild_WithAllOptions_CreatesScheduler() {
        // Act
        QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().instanceName("TestScheduler")
                .instanceId("node-1").threadCount(4).threadNamePrefix("test-quartz").taskExecutor(NO_OP_EXECUTOR)
                .build();

        // Assert
        assertThat(scheduler).isNotNull();
    }

    @Test
    void testBuild_WithClustering_CreatesScheduler() {
        // Act - a cluster's nodes are the schedulers sharing a name, so clustering requires naming one
        QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().instanceName("ClusteredTestScheduler")
                .clustered(true).clusterCheckinInterval(15000).taskExecutor(NO_OP_EXECUTOR).build();

        // Assert
        assertThat(scheduler).isNotNull();
    }

    @Test
    void testBuild_ClusteredWithoutInstanceName_ThrowsException() {
        // A derived name would build a scheduler that starts, checks in, and finds no peers — a cluster of one, with
        // every trigger firing on every node and nothing in the logs to say so.
        assertThatThrownBy(
                () -> QuartzTaskSchedulerBuilder.create().clustered(true).taskExecutor(NO_OP_EXECUTOR).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("explicit instanceName");
    }

    @Test
    void testTaskExecutor_NullValue_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> QuartzTaskSchedulerBuilder.create().taskExecutor(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Task executor cannot be null");
    }

    @Test
    void testBuild_WithTaskExecutor_CreatesScheduler() {
        // Act
        QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().taskExecutor(NO_OP_EXECUTOR).build();

        // Assert
        assertThat(scheduler).isNotNull();
    }

    @Test
    void testBuild_WithoutTaskExecutor_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> QuartzTaskSchedulerBuilder.create().build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Task executor is required");
    }

    @Test
    void testBuild_WithJdbcJobStore_CreatesScheduler() {
        // Act - Quartz creates the scheduler with JDBC config (connection is lazy)
        QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().taskExecutor(NO_OP_EXECUTOR)
                .jdbcJobStore("jdbc:h2:mem:testdb", "org.h2.Driver").build();

        // Assert
        assertThat(scheduler).isNotNull();
        assertThat(scheduler.getQuartzScheduler()).isNotNull();
    }

    @Test
    void testBuild_WithDataSourceClass_CreatesScheduler() {
        // Act - dataSourceClass auto-enables JDBC mode. The class has to exist: Quartz loads and instantiates it while
        // building. (Until schedulers stopped sharing a name, this test named a class that does not exist and still
        // passed — the second build never got as far as reading its own configuration.)
        QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().taskExecutor(NO_OP_EXECUTOR)
                .dataSourceClass(StubConnectionProvider.class.getName()).build();

        // Assert
        assertThat(scheduler).isNotNull();
        assertThat(scheduler.getQuartzScheduler()).isNotNull();
    }

    @Test
    void testBuild_WithJdbcAndClustering_CreatesScheduler() {
        // Act - JDBC + clustering configuration
        QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().taskExecutor(NO_OP_EXECUTOR)
                .instanceName("JdbcClusteredTestScheduler")
                .jdbcJobStore("jdbc:postgresql://localhost/quartz", "org.postgresql.Driver").clustered(true)
                .clusterCheckinInterval(15000).build();

        // Assert
        assertThat(scheduler).isNotNull();
        assertThat(scheduler.getQuartzScheduler()).isNotNull();
    }

    @Test
    void testBuild_Twice_CreatesTwoSchedulers() throws SchedulerException {
        // Act
        QuartzTaskScheduler first = QuartzTaskSchedulerBuilder.create().taskExecutor(NO_OP_EXECUTOR).build();
        QuartzTaskScheduler second = QuartzTaskSchedulerBuilder.create().taskExecutor(NO_OP_EXECUTOR).build();

        // Assert - two builds, two schedulers. This is the whole point of not defaulting the name: see the test below
        // for what a shared name does instead.
        assertThat(second.getQuartzScheduler()).isNotSameAs(first.getQuartzScheduler());
        assertThat(second.getQuartzScheduler().getSchedulerName())
                .isNotEqualTo(first.getQuartzScheduler().getSchedulerName());
    }

    @Test
    void testBuild_TwiceUnderOneName_YieldsTheSameScheduler() {
        // Quartz resolves schedulers through a JVM-global repository keyed by instance name, and hands back the
        // existing one rather than building a second. Asserting it here so the reason the default was removed stays
        // visible: under a fixed name, the second build's thread pool, job store and task executor are all discarded,
        // and either owner's shutdown() stops both.
        QuartzTaskScheduler first = QuartzTaskSchedulerBuilder.create().instanceName("SharedNameScheduler")
                .taskExecutor(NO_OP_EXECUTOR).build();
        QuartzTaskScheduler second = QuartzTaskSchedulerBuilder.create().instanceName("SharedNameScheduler")
                .threadCount(1).taskExecutor(NO_OP_EXECUTOR).build();

        assertThat(second.getQuartzScheduler()).isSameAs(first.getQuartzScheduler());
    }

    @Test
    void testBuild_ByDefault_MakesTheSchedulerThreadsDaemons() throws SchedulerException {
        // Act
        QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().instanceName("DaemonScheduler")
                .threadNamePrefix("daemon-scheduler-worker").threadCount(2).taskExecutor(NO_OP_EXECUTOR).build();
        scheduler.start();

        // Assert - a scheduler nobody shuts down must not be the reason the JVM will not exit
        try {
            assertThat(schedulerThreads("DaemonScheduler", "daemon-scheduler-worker")).isNotEmpty()
                    .allMatch(Thread::isDaemon);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void testBuild_WithDaemonThreadsDisabled_MakesThemNonDaemons() throws SchedulerException {
        // Act
        QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().instanceName("NonDaemonScheduler")
                .threadNamePrefix("non-daemon-scheduler-worker").threadCount(2).daemonThreads(false)
                .taskExecutor(NO_OP_EXECUTOR).build();
        scheduler.start();

        // Assert - the opt-out reaches Quartz, so the property is wired rather than merely set
        try {
            assertThat(schedulerThreads("NonDaemonScheduler", "non-daemon-scheduler-worker")).isNotEmpty()
                    .noneMatch(Thread::isDaemon);
        } finally {
            // Must run: these threads would otherwise hold the test JVM open.
            scheduler.shutdown();
        }
    }

    /**
     * A connection provider that exists but never connects — enough for a scheduler to be built around it, since the
     * job store does not open a connection until the scheduler starts.
     */
    public static class StubConnectionProvider implements ConnectionProvider {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("This provider is configuration only");
        }

        @Override
        public void initialize() {
            // nothing to set up
        }

        @Override
        public void shutdown() {
            // nothing to tear down
        }
    }

    /** Returns the live threads Quartz created for one scheduler: its own dispatch thread and its worker pool. */
    private static List<Thread> schedulerThreads(String instanceName, String threadNamePrefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(threadNamePrefix)
                        || thread.getName().startsWith(instanceName + "_QuartzSchedulerThread"))
                .toList();
    }
}
