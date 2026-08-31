/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.quartz;

import java.util.Objects;
import java.util.Properties;

import org.quartz.Scheduler;

import at.aimon.core.scheduling.scheduler.ScheduledTaskExecutor;

/**
 * Builder for creating {@link QuartzTaskScheduler} instances with custom configuration.
 *
 * <p>
 * Supports configuration for:
 * <ul>
 * <li>Thread pool settings</li>
 * <li>Job store type (RAM or JDBC)</li>
 * <li>Clustering for distributed environments</li>
 * <li>Task executor for scheduled task execution</li>
 * </ul>
 *
 * <p>
 * <b>The instance name is not a label.</b> Quartz resolves schedulers through a JVM-global
 * {@code SchedulerRepository} keyed by that name, and {@code StdSchedulerFactory.getScheduler()} <em>returns the
 * existing one</em> rather than building a second — so two builds under the same name produce one scheduler wearing the
 * first build's thread pool and job store, running both callers' jobs, and stopping for both when either shuts it down.
 * This builder therefore derives a fresh name per {@link #build()} unless {@link #instanceName(String)} says otherwise.
 * It once defaulted to a fixed literal, which is the arrangement above: anything that built twice in one JVM — a second
 * application context, a re-run factory method, a test slice — shared a scheduler without any sign that it had.
 *
 * <p>
 * Clustering inverts the requirement: Quartz treats same-name schedulers over one database as one logical cluster, so
 * there the name must be identical on every node and a derived one would leave each node a cluster of one. Because that
 * failure is silent too — each node checks in, no node sees the others, every trigger fires everywhere — a clustered
 * build with no explicit name is rejected rather than defaulted.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * // Simple RAM-based scheduler
 * QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create()
 *         .taskExecutor(taskId -> taskManager.executeTask(taskId)).threadCount(4).build();
 *
 * // JDBC-based scheduler for clustering
 * QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create().instanceName("MyScheduler")
 *         .taskExecutor(taskId -> taskManager.executeTask(taskId)).clustered(true)
 *         .jdbcJobStore("jdbc:postgresql://localhost/quartz", "org.postgresql.Driver").build();
 * }
 * </pre>
 */
public final class QuartzTaskSchedulerBuilder {

    private String instanceName;
    private String instanceId = "AUTO";
    private boolean daemonThreads = true;
    private boolean waitForJobsOnShutdown = true;
    private int threadCount = Runtime.getRuntime().availableProcessors();
    private String threadNamePrefix = "aimon-quartz";
    private boolean useJdbcJobStore = false;
    private String jdbcUrl;
    private String jdbcDriver;
    private String dataSourceClass;
    private boolean clustered = false;
    private long clusterCheckinInterval = 20000;
    private ScheduledTaskExecutor taskExecutor;

    private QuartzTaskSchedulerBuilder() {
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static QuartzTaskSchedulerBuilder create() {
        return new QuartzTaskSchedulerBuilder();
    }

    /**
     * Sets the scheduler instance name.
     *
     * <p>
     * Two schedulers built under one name in one JVM are one scheduler (see the class javadoc). Leave this unset for an
     * isolated scheduler; set it — to the same value on every node — to join a JDBC cluster, which is the one case that
     * requires sharing the name and the one case where omitting it is an error rather than a default.
     *
     * @param instanceName
     *            the instance name
     * @return this builder
     */
    public QuartzTaskSchedulerBuilder instanceName(String instanceName) {
        this.instanceName = Objects.requireNonNull(instanceName, "Instance name cannot be null");
        return this;
    }

    /**
     * Sets the scheduler instance ID.
     *
     * @param instanceId
     *            the instance ID (use "AUTO" for auto-generated)
     * @return this builder
     */
    public QuartzTaskSchedulerBuilder instanceId(String instanceId) {
        this.instanceId = Objects.requireNonNull(instanceId, "Instance ID cannot be null");
        return this;
    }

    /**
     * Sets whether the scheduler's threads are daemon threads.
     *
     * <p>
     * Defaults to {@code true}, which is the choice every other long-lived thread in the framework already makes. It is
     * a backstop, not the shutdown mechanism: an owner that never calls {@link QuartzTaskScheduler#shutdown()} leaves
     * non-daemon Quartz threads holding the JVM open with no symptom other than a process that will not exit. Pass
     * {@code false} when the scheduler is the reason the process exists and outliving {@code main} is the point — and
     * then be sure something shuts it down.
     *
     * @param daemonThreads
     *            true to make the scheduler and worker threads daemons
     * @return this builder
     */
    public QuartzTaskSchedulerBuilder daemonThreads(boolean daemonThreads) {
        this.daemonThreads = daemonThreads;
        return this;
    }

    /**
     * Sets whether {@link QuartzTaskScheduler#shutdown()} blocks until running jobs finish.
     *
     * <p>
     * Defaults to {@code true}, which is what a standalone process wants: the scheduler is the reason it is running, so
     * letting a job finish costs nothing. It is the wrong default inside a server, where shutdown is ordered and this
     * wait happens before the things that drain traffic — one long job then holds the whole process open. Pass
     * {@code false} there; an unfinished job is abandoned and fires again at its next trigger.
     *
     * @param waitForJobsOnShutdown
     *            true to wait for running jobs at shutdown
     * @return this builder
     */
    public QuartzTaskSchedulerBuilder waitForJobsOnShutdown(boolean waitForJobsOnShutdown) {
        this.waitForJobsOnShutdown = waitForJobsOnShutdown;
        return this;
    }

    /**
     * Sets the thread pool size.
     *
     * @param threadCount
     *            the number of threads
     * @return this builder
     */
    public QuartzTaskSchedulerBuilder threadCount(int threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("Thread count must be at least 1");
        }
        this.threadCount = threadCount;
        return this;
    }

    /**
     * Sets the thread name prefix.
     *
     * @param threadNamePrefix
     *            the prefix for thread names
     * @return this builder
     */
    public QuartzTaskSchedulerBuilder threadNamePrefix(String threadNamePrefix) {
        this.threadNamePrefix = Objects.requireNonNull(threadNamePrefix, "Thread name prefix cannot be null");
        return this;
    }

    /**
     * Configures JDBC-based job store for persistent job storage.
     *
     * @param jdbcUrl
     *            the JDBC connection URL
     * @param jdbcDriver
     *            the JDBC driver class name
     * @return this builder
     */
    public QuartzTaskSchedulerBuilder jdbcJobStore(String jdbcUrl, String jdbcDriver) {
        this.useJdbcJobStore = true;
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "JDBC URL cannot be null");
        this.jdbcDriver = Objects.requireNonNull(jdbcDriver, "JDBC driver cannot be null");
        return this;
    }

    /**
     * Configures JDBC-based job store with a custom DataSource class.
     *
     * <p>
     * This option requires JDBC job store to be enabled via {@link #jdbcJobStore(String, String)}. When set, the custom
     * DataSource class is used instead of the JDBC URL and driver for connection management.
     *
     * @param dataSourceClass
     *            the DataSource class name
     * @return this builder
     */
    public QuartzTaskSchedulerBuilder dataSourceClass(String dataSourceClass) {
        this.useJdbcJobStore = true;
        this.dataSourceClass = Objects.requireNonNull(dataSourceClass, "DataSource class cannot be null");
        return this;
    }

    /**
     * Enables clustering for distributed environments.
     *
     * @param clustered
     *            true to enable clustering
     * @return this builder
     */
    public QuartzTaskSchedulerBuilder clustered(boolean clustered) {
        this.clustered = clustered;
        return this;
    }

    /**
     * Sets the cluster check-in interval in milliseconds.
     *
     * @param intervalMs
     *            the interval in milliseconds
     * @return this builder
     */
    public QuartzTaskSchedulerBuilder clusterCheckinInterval(long intervalMs) {
        if (intervalMs < 1000) {
            throw new IllegalArgumentException("Cluster check-in interval must be at least 1000ms");
        }
        this.clusterCheckinInterval = intervalMs;
        return this;
    }

    /**
     * Sets the task executor for executing scheduled tasks by ID.
     *
     * @param taskExecutor
     *            the task executor
     * @return this builder
     */
    public QuartzTaskSchedulerBuilder taskExecutor(ScheduledTaskExecutor taskExecutor) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "Task executor cannot be null");
        return this;
    }

    /**
     * Builds the QuartzTaskScheduler instance.
     *
     * @return a new QuartzTaskScheduler
     * @throws NullPointerException
     *             if taskExecutor is not set
     * @throws IllegalStateException
     *             if clustering is enabled without an explicit {@link #instanceName(String)} — the nodes of a Quartz
     *             cluster are the schedulers sharing a name, so a derived one would silently leave this node alone
     */
    public QuartzTaskScheduler build() {
        Objects.requireNonNull(taskExecutor, "Task executor is required");

        if (clustered && instanceName == null) {
            throw new IllegalStateException("Clustered schedulers must share an explicit instanceName across nodes; "
                    + "a derived one would make each node a cluster of one, and every trigger would fire on all of "
                    + "them");
        }

        Properties props = new Properties();

        // Scheduler identification
        props.setProperty("org.quartz.scheduler.instanceName",
                instanceName != null ? instanceName : QuartzTaskScheduler.deriveInstanceName());
        props.setProperty("org.quartz.scheduler.instanceId", instanceId);

        // Thread pool configuration
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", String.valueOf(threadCount));
        props.setProperty("org.quartz.threadPool.threadNamePrefix", threadNamePrefix);
        props.setProperty("org.quartz.threadPool.makeThreadsDaemons", String.valueOf(daemonThreads));
        props.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", String.valueOf(daemonThreads));

        if (useJdbcJobStore) {
            configureJdbcJobStore(props);
        } else {
            props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        }

        Scheduler scheduler = QuartzTaskScheduler.createScheduler(props);
        return QuartzTaskScheduler.owning(scheduler, taskExecutor, waitForJobsOnShutdown);
    }

    private void configureJdbcJobStore(Properties props) {
        props.setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX");
        props.setProperty("org.quartz.jobStore.driverDelegateClass", "org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
        props.setProperty("org.quartz.jobStore.tablePrefix", "QRTZ_");
        props.setProperty("org.quartz.jobStore.dataSource", "aimonDS");

        // DataSource configuration
        if (dataSourceClass != null) {
            props.setProperty("org.quartz.dataSource.aimonDS.connectionProvider.class", dataSourceClass);
        } else {
            // Name the pool. Left unset, Quartz falls through to c3p0, which it no longer brings with it
            // as of 2.5 -- the scheduler then fails to build with a ClassNotFoundException that names a
            // library this project never chose.
            props.setProperty("org.quartz.dataSource.aimonDS.provider", "hikaricp");
            props.setProperty("org.quartz.dataSource.aimonDS.driver", jdbcDriver);
            props.setProperty("org.quartz.dataSource.aimonDS.URL", jdbcUrl);
            props.setProperty("org.quartz.dataSource.aimonDS.maxConnections", "10");
        }

        // Clustering configuration
        if (clustered) {
            props.setProperty("org.quartz.jobStore.isClustered", "true");
            props.setProperty("org.quartz.jobStore.clusterCheckinInterval", String.valueOf(clusterCheckinInterval));
        }
    }
}
