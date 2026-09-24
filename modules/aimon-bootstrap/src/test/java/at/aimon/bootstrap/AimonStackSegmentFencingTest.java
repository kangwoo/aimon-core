package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionNotHeldException;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.LeaseHolder;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SessionFence;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogSegmentSweeper;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.session.transcript.TranscriptManager;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.session.routing.DeploymentMode;
import at.aimon.session.routing.SubmitRequest;

/**
 * The assembled stack's record writes (turn-end saves, checkpoints) and segment deletes (turn-end garbage collection,
 * {@code /clear}) go through the lease this node holds (session-log §5.4, §5.6, §12.3).
 *
 * <ul>
 * <li>Distributed: a node holding the session saves and deletes; a node that lost it, or never held it, does neither.
 * <li>Single node with a supplied lease store: a session nobody holds — the CLI's shape, a live session opened outside
 * the router — saves and deletes as before; a session another node holds is refused.
 * <li>Single node with the default lease store: raw, as always.
 * </ul>
 */
class AimonStackSegmentFencingTest {

    private static final LlmClient TEXT_LLM = new LlmClient() {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    };

    private final AtomicReference<Instant> leaseNow = new AtomicReference<>(Instant.now());
    private final Clock leaseClock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return leaseNow.get();
        }
    };
    private final InMemorySessionLeaseStore leases = new InMemorySessionLeaseStore(leaseClock);
    private final InMemorySessionLogSegmentStore segments = new InMemorySessionLogSegmentStore();
    // Not an InMemorySessionRecordStore by type: distributed mode refuses one, rightly, for a real deployment.
    private final SessionRecordStore records = mock(SessionRecordStore.class,
            delegatesTo(new InMemorySessionRecordStore()));

    private AimonStackSpec spec(Path workspace, DeploymentMode mode) {
        return spec(workspace, mode, false);
    }

    private AimonStackSpec spec(Path workspace, DeploymentMode mode, boolean singleNodeLeaseStore) {
        final SessionSpec.Builder session = SessionSpec.builder().mode(mode).recordStore(records).segmentStore(segments)
                .logWriteFormat(SessionLogFormat.V2);
        if (mode == DeploymentMode.DISTRIBUTED) {
            session.nodeId("pod-a").leaseStore(leases).signalBus(new InMemorySignalBus())
                    .inbox(new InMemorySessionInbox()).idempotencyStore(new InMemoryIdempotencyStore());
        } else if (singleNodeLeaseStore) {
            session.nodeId("pod-a").leaseStore(leases);
        }
        return AimonStackSpec.builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(TEXT_LLM))
                .agent(AgentSpec.of(AgentBundle.builder()
                        .agent(DefaultAgent.builder().name("ops").systemPrompt("You are ops.").maxIterations(3).build())
                        .build()))
                .session(session.build()).build();
    }

    /** An orphan past the default one-hour grace: named by no manifest, old enough for GC to take. */
    private SegmentId plantOrphan(SessionId session) {
        final SegmentId id = SegmentId.generate();
        segments.put(SessionLogSegment.builder().sessionId(session).id(id).fromSeq(0).toSeq(1).entryCount(1)
                .payload("[]").createdAt(Instant.now().minus(Duration.ofHours(2))).build());
        return id;
    }

    private static void runTurn(AimonStack stack, SessionId session) throws Exception {
        stack.sessionRouter()
                .submit(SubmitRequest.builder().sessionId(session).agentRef("ops").userInput("hello")
                        .initiator(Principal.user("tester")).build())
                .getFuture().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /**
     * A save on this node's transcript manager, outside any turn — the path a stale node's late save takes, and the
     * path
     * a live session opened outside the router (the CLI's) takes every turn. Uses the turn-end path, which never
     * throws.
     */
    private static void saveOutsideTheRouter(AimonStack stack, SessionId session) {
        final TranscriptManager manager = stack.agentExecutor().getTranscriptManager();
        final TranscriptBuffer buffer = manager.initialize(session, "You are ops.");
        buffer.addUserMessage("late");
        manager.saveSilently(buffer);
    }

    /** Whether the record holds the message {@link #saveOutsideTheRouter} writes. */
    private boolean recordHasLateSave(SessionId session) {
        return records.load(session).map(record -> SessionSnapshot.from(record).getConversationHistory().stream()
                .anyMatch(message -> "late".equals(message.getContent()))).orElse(false);
    }

    @Test
    @DisplayName("distributed: the holder's turn-end GC deletes the orphan through the fenced view")
    void holderDeletes(@TempDir Path workspace) throws Exception {
        final SessionId session = SessionId.of("fence-held");
        final SegmentId orphan = plantOrphan(session);

        try (AimonStack stack = AimonStackBuilder.build(spec(workspace, DeploymentMode.DISTRIBUTED))) {
            assertThat(stack.sessionRouter().fencedSegmentStore()).isPresent();
            runTurn(stack, session);
        }

        assertThat(segments.get(session, orphan)).isEmpty();
        assertThat(records.load(session)).as("the holder's turn-end save landed through the fenced view").isPresent();
        assertThat(SessionSnapshot.from(records.load(session).orElseThrow()).getConversationHistory()).isNotEmpty();
    }

    @Test
    @DisplayName("distributed: a node whose lease another node took can neither save that session nor delete its segments")
    void staleHolderIsRejected(@TempDir Path workspace) throws Exception {
        final SessionId session = SessionId.of("fence-stale");

        try (AimonStack stack = AimonStackBuilder.build(spec(workspace, DeploymentMode.DISTRIBUTED))) {
            runTurn(stack, session);
            // pod-a still holds the session (its live session is cached). Let the lease lapse and give it to pod-b.
            leaseNow.set(leaseNow.get().plus(Duration.ofDays(1)));
            assertThat(leases.tryAcquire(session, "pod-b", Duration.ofDays(30))).as("pod-b takes the session")
                    .isPresent();
            final SegmentId orphan = plantOrphan(session);

            saveOutsideTheRouter(stack, session);

            assertThat(recordHasLateSave(session)).as("pod-a's late save is refused by the fence").isFalse();
            assertThat(segments.get(session, orphan)).as("pod-a's late GC is refused by the fence").isPresent();
            final TranscriptManager manager = stack.agentExecutor().getTranscriptManager();
            final TranscriptBuffer buffer = manager.initialize(session, "You are ops.");
            buffer.addUserMessage("late");
            assertThatThrownBy(() -> manager.save(buffer)).as("the throwing save path says why")
                    .isInstanceOf(SessionNotHeldException.class);
        }
    }

    @Test
    @DisplayName("distributed: a node that never held the session cannot delete its segments either")
    void nonHolderIsRejected(@TempDir Path workspace) {
        final SessionId session = SessionId.of("fence-never-held");
        assertThat(leases.tryAcquire(session, "pod-b", Duration.ofDays(30))).isPresent();
        final SegmentId orphan = plantOrphan(session);

        try (AimonStack stack = AimonStackBuilder.build(spec(workspace, DeploymentMode.DISTRIBUTED))) {
            saveOutsideTheRouter(stack, session);
        }

        assertThat(recordHasLateSave(session)).isFalse();
        assertThat(segments.get(session, orphan)).isPresent();
    }

    @Test
    @DisplayName("single node: GC deletes through the raw store, lease or not")
    void singleNodeDeletesRaw(@TempDir Path workspace) {
        final SessionId session = SessionId.of("fence-single");
        final SegmentId orphan = plantOrphan(session);

        try (AimonStack stack = AimonStackBuilder.build(spec(workspace, DeploymentMode.SINGLE_NODE))) {
            assertThat(stack.sessionRouter().fencedRecordStore(SessionFence.HOLDER_ONLY)).isPresent();
            saveOutsideTheRouter(stack, session);
        }

        assertThat(recordHasLateSave(session)).isTrue();
        assertThat(segments.get(session, orphan)).isEmpty();
    }

    @Test
    @DisplayName("single node + lease store: a session nobody holds (the CLI's shape) saves and collects as before")
    void singleNodeWithLeaseStoreUnheldSessionPasses(@TempDir Path workspace) {
        final SessionId session = SessionId.of("fence-single-unheld");
        final SegmentId orphan = plantOrphan(session);

        try (AimonStack stack = AimonStackBuilder.build(spec(workspace, DeploymentMode.SINGLE_NODE, true))) {
            saveOutsideTheRouter(stack, session);
        }

        assertThat(recordHasLateSave(session)).isTrue();
        assertThat(segments.get(session, orphan)).isEmpty();
    }

    @Test
    @DisplayName("single node + lease store: the router's own turn saves and collects through its lease")
    void singleNodeWithLeaseStoreHolderPasses(@TempDir Path workspace) throws Exception {
        final SessionId session = SessionId.of("fence-single-held");
        final SegmentId orphan = plantOrphan(session);

        try (AimonStack stack = AimonStackBuilder.build(spec(workspace, DeploymentMode.SINGLE_NODE, true))) {
            runTurn(stack, session);
            assertThat(leases.findHolder(session)).as("the router holds the session").isPresent();
        }

        assertThat(segments.get(session, orphan)).isEmpty();
        assertThat(SessionSnapshot.from(records.load(session).orElseThrow()).getConversationHistory()).isNotEmpty();
    }

    @Test
    @DisplayName("single node + lease store: a session another node holds can neither be saved nor collected")
    void singleNodeWithLeaseStoreHeldElsewhereIsRejected(@TempDir Path workspace) {
        final SessionId session = SessionId.of("fence-single-elsewhere");
        assertThat(leases.tryAcquire(session, "pod-b", Duration.ofDays(30))).isPresent();
        final SegmentId orphan = plantOrphan(session);

        try (AimonStack stack = AimonStackBuilder.build(spec(workspace, DeploymentMode.SINGLE_NODE, true))) {
            saveOutsideTheRouter(stack, session);
        }

        assertThat(recordHasLateSave(session)).isFalse();
        assertThat(segments.get(session, orphan)).isPresent();
    }

    @Test
    @DisplayName("a configured sweep is on the teardown plan and deletes a never-resumed session's orphan")
    void sweepRunsWhenConfigured(@TempDir Path workspace) throws Exception {
        final SessionId session = SessionId.of("sweep-never-resumed");
        final SegmentId orphan = plantOrphan(session);
        final AimonStackSpec base = spec(workspace, DeploymentMode.SINGLE_NODE);
        final AimonStackSpec withSweep = AimonStackSpec.builder().workspaceRoot(workspace.toString())
                .llm(LlmSpec.of(TEXT_LLM)).agent(base.getAgents().get(0))
                .session(SessionSpec.builder().recordStore(records).segmentStore(segments)
                        .segmentSweepInterval(Duration.ofMillis(20)).segmentSweepGrace(Duration.ofMinutes(1)).build())
                .build();

        try (AimonStack stack = AimonStackBuilder.build(withSweep)) {
            assertThat(stack.teardownPlan()).anyMatch(entry -> entry.contains("segmentSweep"));
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (segments.get(session, orphan).isPresent() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertThat(segments.get(session, orphan)).isEmpty();
        }
    }

    @Test
    @DisplayName("with a lease store the sweep is coordinated: a pass takes the sweep lease as this node, and a node"
            + " that finds it held skips its pass")
    void sweepIsCoordinatedThroughTheLeaseStore(@TempDir Path workspace) throws Exception {
        final SessionId session = SessionId.of("sweep-coordinated");
        final SegmentId orphan = plantOrphan(session);
        final AimonStackSpec base = spec(workspace, DeploymentMode.DISTRIBUTED);
        final AimonStackSpec withSweep = AimonStackSpec.builder().workspaceRoot(workspace.toString())
                .llm(LlmSpec.of(TEXT_LLM)).agent(base.getAgents().get(0))
                .session(SessionSpec.builder().mode(DeploymentMode.DISTRIBUTED).nodeId("pod-a").leaseStore(leases)
                        .signalBus(new InMemorySignalBus()).inbox(new InMemorySessionInbox())
                        .idempotencyStore(new InMemoryIdempotencyStore()).recordStore(records).segmentStore(segments)
                        .segmentSweepInterval(Duration.ofMillis(20)).segmentSweepGrace(Duration.ofMinutes(1)).build())
                .build();

        // pod-b is sweeping this interval: pod-a's passes skip and the orphan stays.
        assertThat(leases.tryAcquire(SessionLogSegmentSweeper.SWEEP_LEASE_ID, "pod-b", Duration.ofDays(1))).isPresent();
        try (AimonStack stack = AimonStackBuilder.build(withSweep)) {
            TimeUnit.MILLISECONDS.sleep(200);
            assertThat(segments.get(session, orphan)).as("pod-b holds the sweep lease").isPresent();

            // pod-b's lease lapses (it died); pod-a takes the next pass.
            leaseNow.set(leaseNow.get().plus(Duration.ofDays(2)));
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (segments.get(session, orphan).isPresent() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertThat(segments.get(session, orphan)).isEmpty();
            assertThat(leases.findHolder(SessionLogSegmentSweeper.SWEEP_LEASE_ID)).get()
                    .extracting(LeaseHolder::getHolderId).isEqualTo("pod-a");
        }
    }

    @Test
    @DisplayName("without an interval no sweep is assembled")
    void noSweepByDefault(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.build(spec(workspace, DeploymentMode.SINGLE_NODE))) {
            assertThat(stack.teardownPlan()).noneMatch(entry -> entry.contains("segmentSweep"));
        }
    }
}
