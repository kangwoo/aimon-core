package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.exception.SchedulingException;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.testkit.AbstractScheduledTaskRepositoryContractTest;

/**
 * Runs the shared task repository contract against {@link MongoScheduledTaskRepository} on a real MongoDB, plus what
 * is specific to a store whose documents can be written by something other than this build.
 *
 * <p>
 * {@link #reopened()} hands out a repository on a <em>second client</em>, so "after a restart" in the contract means a
 * handle that shares nothing with the first but the database.
 */
@DisplayName("MongoScheduledTaskRepository integration")
@Tag("docker")
class MongoScheduledTaskRepositoryIntegrationTest extends AbstractScheduledTaskRepositoryContractTest {

    private MongoClient client;
    private MongoClient secondClient;
    private MongoScheduledTaskRepository repository;

    @BeforeEach
    void setUp() {
        MongoTestSupport.dropAndApplyDdl();
        client = MongoTestSupport.newClient();
        secondClient = MongoTestSupport.newClient();
        repository = new MongoScheduledTaskRepository(client.getDatabase(MongoTestSupport.DATABASE_NAME));
    }

    @AfterEach
    void tearDown() {
        client.close();
        secondClient.close();
    }

    @Override
    protected ScheduledTaskRepository repository() {
        return repository;
    }

    @Override
    protected ScheduledTaskRepository reopened() {
        return new MongoScheduledTaskRepository(secondClient.getDatabase(MongoTestSupport.DATABASE_NAME));
    }

    @Test
    @DisplayName("a document this build cannot read is left out of the listings and does not hide the others")
    void unreadableDocumentIsSkippedByListings() {
        final ScheduledTask readable = task("readable");
        repository.save(readable);
        plantUnreadable("task-unreadable");

        assertThat(repository.findAll()).extracting(ScheduledTask::getId).containsExactly(readable.getId());
        assertThat(repository.findByEnabledTrue()).extracting(ScheduledTask::getId).containsExactly(readable.getId());
        assertThat(repository.findByOwner(Principal.user("alice"))).extracting(ScheduledTask::getId)
                .containsExactly(readable.getId());
    }

    @Test
    @DisplayName("asking for that document by id fails rather than answering 'absent', and it still exists")
    void unreadableDocumentFailsByIdAndStillExists() {
        plantUnreadable("task-unreadable");

        assertThatThrownBy(() -> repository.findById(ScheduledTaskId.of("task-unreadable")))
                .isInstanceOf(SchedulingException.class).hasMessageContaining("task-unreadable");
        assertThat(repository.existsById(ScheduledTaskId.of("task-unreadable"))).isTrue();
    }

    @Test
    @DisplayName("updateIfPresent on a task that was never stored writes nothing")
    void updateIfPresentOnAnAbsentTaskWritesNothing() {
        assertThat(repository.updateIfPresent(task("never-stored"))).isFalse();

        assertThat(collection().countDocuments()).isZero();
    }

    @Test
    @DisplayName("an unreadable document still counts against its owner's quota")
    void unreadableDocumentStillCounts() {
        repository.save(task("readable"));
        plantUnreadable("task-unreadable");

        // The listing leaves it out; the count, which is what a quota is measured against, must not.
        assertThat(repository.findByOwner(Principal.user("alice"))).hasSize(1);
        assertThat(repository.countByOwner(Principal.user("alice"))).isEqualTo(2);
    }

    @Test
    @DisplayName("recording a run keeps fields a newer build wrote that this one does not know")
    void recordExecutionKeepsFieldsThisBuildDoesNotKnow() {
        // A rolling upgrade or a rollback: the document carries a field from the other release. Decoding ignores it,
        // so the task keeps running here — and a whole-document write-back after each run would delete it.
        final ScheduledTask task = task("from-a-newer-build");
        repository.save(task);
        collection().updateOne(new Document("_id", task.getId().value()),
                new Document("$set", new Document("pausedUntil", "2027-01-01T00:00:00Z")));

        assertThat(repository.recordExecution(task.getId(), java.time.Instant.parse("2026-10-06T03:00:00Z"))).isTrue();

        final Document stored = collection().find(new Document("_id", task.getId().value())).first();
        assertThat(stored.getString("pausedUntil")).isEqualTo("2027-01-01T00:00:00Z");
        assertThat(stored.getString("lastExecutedAt")).isEqualTo("2026-10-06T03:00:00.000000000Z");
    }

    /** Owned by alice and enabled, so every listing would return it if it could be read; it has no routine. */
    private void plantUnreadable(String id) {
        collection().insertOne(new Document("_id", id).append("name", "broken").append("cronExpression", "0 0 1 1 *")
                .append("owner", new Document("type", "USER").append("id", "alice").append("displayName", "alice"))
                .append("boundRuntimeId", "agent:ops").append("enabled", true)
                .append("createdAt", "2026-10-06T00:00:00Z").append("routine", List.of()));
    }

    private static MongoCollection<Document> collection() {
        return MongoTestSupport.sharedDatabase().getCollection(DocumentKeys.COLL_SCHEDULED_TASKS);
    }

    private static ScheduledTask task(String name) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name(name).cronExpression("0 0 1 1 *")
                .owner(Principal.user("alice")).boundRuntimeId(AgentRuntimeId.fromName("mongo-repository"))
                .routine(List.of(RoutineStep.of("noop", "{}"))).enabled(true).build();
    }
}
