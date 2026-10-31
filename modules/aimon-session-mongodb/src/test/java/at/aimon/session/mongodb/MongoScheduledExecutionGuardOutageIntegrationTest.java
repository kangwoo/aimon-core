package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineExecutor;
import at.aimon.core.scheduling.RoutineResult;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledExecutionGuard.ExecutionLease;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.exception.SchedulingException;
import at.aimon.core.scheduling.quota.DefaultTaskQuotaManager;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.ScheduledFire;
import at.aimon.core.scheduling.scheduler.TaskScheduler;
import at.aimon.session.mongodb.internal.DocumentKeys;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * {@link MongoScheduledExecutionGuard} through a real outage: the server is paused mid-lease and resumed. A paused
 * server keeps its sockets open and answers nothing, which is the failure a socket timeout exists for.
 *
 * <p>
 * On a container of its own, not {@link MongoTestSupport}'s, since pausing that one would stall every other test. The
 * guard's client has short timeouts so failures arrive quickly; {@code observer} has default ones and only reads, after
 * the server is back. A failover needs a three-member replica set whose members advertise addresses the host can reach,
 * which is more harness than this behaviour needs: to the guard an election looks like this outage followed by a new
 * primary.
 */
@DisplayName("MongoScheduledExecutionGuard through a server outage")
@Tag("docker")
class MongoScheduledExecutionGuardOutageIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(3);
    private static final long WAIT_TIMEOUT_MS = 60_000L;
    private static final Instant SLOT = Instant.parse("2026-10-27T10:00:00Z");

    private static MongoDBContainer mongo;
    private static MongoClient guardClient;
    private static MongoClient observer;

    private final ScheduledTaskId taskId = ScheduledTaskId.generate();
    private final List<ILoggingEvent> warnings = new CopyOnWriteArrayList<>();
    private ListAppender<ILoggingEvent> appender;
    private MongoScheduledExecutionGuard guard;

    @BeforeAll
    static void startServer() {
        mongo = new MongoDBContainer("mongo:6.0").withStartupTimeout(Duration.ofMinutes(2));
        mongo.start();
        final String url = mongo.getReplicaSetUrl(MongoTestSupport.DATABASE_NAME);
        guardClient = MongoClients.create(url + (url.contains("?") ? "&" : "?")
                + "socketTimeoutMS=500&serverSelectionTimeoutMS=500&connectTimeoutMS=500&heartbeatFrequencyMS=500");
        observer = MongoClients.create(url);
    }

    @AfterAll
    static void stopServer() {
        guardClient.close();
        observer.close();
        mongo.stop();
    }

    @AfterEach
    void cleanUp() {
        resume();
        if (guard != null) {
            guard.close();
        }
        if (appender != null) {
            ((Logger) LoggerFactory.getLogger(MongoScheduledExecutionGuard.class)).detachAppender(appender);
        }
    }

    @Test
    @DisplayName("a held lease's heartbeat fails during the outage, survives it, and renews again in server time after")
    void heartbeatRecoversFromAnOutage() throws Exception {
        guard = guard();
        captureWarnings();
        final ExecutionLease lease = guard.tryBegin(ScheduledFire.of(taskId, SLOT, SLOT)).orElseThrow();
        final Date stampBeforeOutage = document().getDate(DocumentKeys.F_LEASE_EXPIRES_AT);

        pause();
        awaitTrue(() -> warnings.stream().anyMatch(w -> w.getFormattedMessage().contains("Could not renew")),
                "renewals fail during the outage");
        assertThat(heartbeatThreads()).as("and the heartbeat keeps going").hasSize(1);
        resume();

        awaitTrue(() -> document().getDate(DocumentKeys.F_LEASE_EXPIRES_AT).after(stampBeforeOutage),
                "the heartbeat renews again once the server is back");
        final Date renewedBy = Date.from(serverNow().plus(LEASE));
        awaitTrue(() -> !document().getDate(DocumentKeys.F_LEASE_EXPIRES_AT).before(renewedBy),
                "and keeps renewing in server time");
        assertThat(document().getString(DocumentKeys.F_HOLDER_ID)).startsWith("node-A/");
        assertThat(warnings).noneMatch(w -> w.getFormattedMessage().contains("Lost the lease"));
        lease.close();
    }

    @Test
    @DisplayName("during the outage tryBegin throws and the manager skips the fire; after it, fires run again")
    void managerSkipsFiresDuringAnOutage() throws Exception {
        guard = guard();
        final RoutineExecutor routine = mock(RoutineExecutor.class);
        final Principal owner = Principal.user("alice");
        final InMemoryScheduledTaskRepository tasks = new InMemoryScheduledTaskRepository();
        final ScheduledTaskManager manager = ScheduledTaskManager.builder().taskRepository(tasks)
                .historyRepository(new InMemoryScheduledTaskExecutionHistoryRepository()).routineExecutor(routine)
                .taskScheduler(mock(TaskScheduler.class)).eventPublisher(new SimpleScheduledTaskEventPublisher())
                .quotaManager(new DefaultTaskQuotaManager(1)).executionGuard(guard).build();
        final ScheduledTask task = ScheduledTask.builder().id(taskId).name("t").cronExpression("* * * * *").owner(owner)
                .boundRuntimeId(AgentRuntimeId.fromName("outage")).routine(List.of(RoutineStep.of("Bash", "{}")))
                .enabled(true).build();
        manager.register(task);
        when(routine.execute(any())).thenReturn(RoutineResult.success(taskId, List.of(), SLOT, SLOT));

        pause();
        assertThatThrownBy(() -> guard.tryBegin(ScheduledFire.of(taskId, SLOT, SLOT)))
                .isInstanceOf(SchedulingException.class);
        assertThatCode(() -> manager.executeFire(ScheduledFire.of(taskId, SLOT, SLOT))).doesNotThrowAnyException();
        verify(routine, never()).execute(any());
        resume();

        awaitTrue(() -> {
            manager.executeFire(ScheduledFire.of(taskId, SLOT, SLOT));
            return !Mockito.mockingDetails(routine).getInvocations().isEmpty();
        }, "the fire runs once the server is back");
    }

    private MongoScheduledExecutionGuard guard() {
        return new MongoScheduledExecutionGuard(guardClient.getDatabase(MongoTestSupport.DATABASE_NAME),
                DocumentKeys.COLL_SCHEDULED_EXECUTION_GUARDS, "node-A", LEASE,
                MongoScheduledExecutionGuard.DEFAULT_MAX_SKEW);
    }

    private void captureWarnings() {
        appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getLevel() == Level.WARN) {
                    warnings.add(event);
                }
            }
        };
        appender.start();
        ((Logger) LoggerFactory.getLogger(MongoScheduledExecutionGuard.class)).addAppender(appender);
    }

    private static void pause() {
        mongo.getDockerClient().pauseContainerCmd(mongo.getContainerId()).exec();
    }

    private static void resume() {
        final Boolean paused = mongo.getDockerClient().inspectContainerCmd(mongo.getContainerId()).exec().getState()
                .getPaused();
        if (Boolean.TRUE.equals(paused)) {
            mongo.getDockerClient().unpauseContainerCmd(mongo.getContainerId()).exec();
        }
    }

    private Document document() {
        final MongoCollection<Document> collection = observer.getDatabase(MongoTestSupport.DATABASE_NAME)
                .getCollection(DocumentKeys.COLL_SCHEDULED_EXECUTION_GUARDS);
        return collection.find(new Document(DocumentKeys.F_ID, taskId.value())).first();
    }

    private static Instant serverNow() {
        return observer.getDatabase(MongoTestSupport.DATABASE_NAME).runCommand(new Document("hello", 1))
                .getDate("localTime").toInstant();
    }

    private List<Thread> heartbeatThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("mongo-execution-guard-heartbeat-" + taskId)).toList();
    }

    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as(description).isLessThan(deadline);
            Thread.sleep(100L);
        }
    }
}
