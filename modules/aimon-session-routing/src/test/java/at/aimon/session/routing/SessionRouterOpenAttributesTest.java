package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.OpenAttributes;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.TestLiveSession;

/**
 * End-to-end check that {@link SubmitRequest#getOpenAttributes()} reaches a caller-supplied
 * {@link LiveSessionOpener} on cache miss, and that subsequent submits hitting the cached entry do <b>not</b>
 * re-invoke the opener (re-open semantics documented on {@link OpenAttributes}).
 */
@DisplayName("SessionRouter: OpenAttributes flow to caller-supplied opener")
class SessionRouterOpenAttributesTest {

    private SessionRouter manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @DisplayName("first submit forwards OpenAttributes from SubmitRequest into the opener")
    void firstSubmitForwardsOpenAttributes() throws Exception {
        final ConcurrentMap<SessionId, TestLiveSession> sessions = new ConcurrentHashMap<>();
        final AtomicReference<OpenAttributes> capturedAttrs = new AtomicReference<>();
        final AtomicReference<AgentRuntimeId> capturedRuntimeId = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger openerCalls = new java.util.concurrent.atomic.AtomicInteger();

        final LiveSessionOpener opener = (id, agentRuntimeId, options, attrs) -> {
            openerCalls.incrementAndGet();
            capturedAttrs.set(attrs);
            capturedRuntimeId.set(agentRuntimeId);
            return sessions.computeIfAbsent(id, TestLiveSession::new);
        };

        manager = SessionRouter.builder().sessionOpener(opener).sessionRecordStore(new InMemorySessionRecordStore())
                .idleTtl(Duration.ofMinutes(10)).nodeId("node-" + UUID.randomUUID()).build();

        final OpenAttributes attrs = OpenAttributes.builder().put("ops.agentId", "agt-42").put("ops.ouId", "ou-7")
                .build();

        final SessionId id = SessionId.of("c-attrs-1");
        final SubmitRequest request = SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput("hello")
                .initiator(Principal.user("tester")).openAttributes(attrs).build();

        final SubmitDisposition outcome = manager.submit(request);
        assertThat(outcome.getKind()).isEqualTo(SubmitDisposition.Kind.EXECUTED_LOCALLY);

        // Opener is invoked synchronously on the manager's turn-executor thread; wait for the turn to land.
        final TestLiveSession session = waitForSession(sessions, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        assertThat(openerCalls.get()).isEqualTo(1);
        assertThat(capturedRuntimeId.get()).isEqualTo(AgentRuntimeId.fromName("alpha"));
        assertThat(capturedAttrs.get()).isNotNull();
        assertThat(capturedAttrs.get().getString("ops.agentId")).hasValue("agt-42");
        assertThat(capturedAttrs.get().getString("ops.ouId")).hasValue("ou-7");

        session.completeCurrentTurn(TestLiveSession.ok("done"));
        outcome.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("submits with empty / no openAttributes flow OpenAttributes.empty() to the opener")
    void emptyOpenAttributesByDefault() throws Exception {
        final ConcurrentMap<SessionId, TestLiveSession> sessions = new ConcurrentHashMap<>();
        final AtomicReference<OpenAttributes> capturedAttrs = new AtomicReference<>();

        final LiveSessionOpener opener = (id, agentRuntimeId, options, attrs) -> {
            capturedAttrs.set(attrs);
            return sessions.computeIfAbsent(id, TestLiveSession::new);
        };

        manager = SessionRouter.builder().sessionOpener(opener).sessionRecordStore(new InMemorySessionRecordStore())
                .idleTtl(Duration.ofMinutes(10)).nodeId("node-" + UUID.randomUUID()).build();

        final SessionId id = SessionId.of("c-attrs-empty");
        final SubmitRequest request = SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput("hi")
                .initiator(Principal.user("tester")).build(); // no openAttributes

        final SubmitDisposition outcome = manager.submit(request);
        final TestLiveSession session = waitForSession(sessions, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        assertThat(capturedAttrs.get()).isNotNull();
        assertThat(capturedAttrs.get().isEmpty()).isTrue();

        session.completeCurrentTurn(TestLiveSession.ok("done"));
        outcome.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("re-open semantics: opener is invoked once per cached session; later attributes are ignored")
    void cachedSessionReuseSkipsOpener() throws Exception {
        final ConcurrentMap<SessionId, TestLiveSession> sessions = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger openerCalls = new java.util.concurrent.atomic.AtomicInteger();
        final AtomicReference<OpenAttributes> capturedAttrs = new AtomicReference<>();

        final LiveSessionOpener opener = (id, agentRuntimeId, options, attrs) -> {
            openerCalls.incrementAndGet();
            capturedAttrs.set(attrs);
            return sessions.computeIfAbsent(id, TestLiveSession::new);
        };

        manager = SessionRouter.builder().sessionOpener(opener).sessionRecordStore(new InMemorySessionRecordStore())
                .idleTtl(Duration.ofMinutes(10)).nodeId("node-" + UUID.randomUUID()).build();

        final SessionId id = SessionId.of("c-attrs-reuse");
        final OpenAttributes firstAttrs = OpenAttributes.builder().put("ops.agentId", "agt-1").build();
        final OpenAttributes secondAttrs = OpenAttributes.builder().put("ops.agentId", "agt-DIFFERENT").build();

        // First submit — opener invoked with firstAttrs.
        final SubmitDisposition firstOutcome = manager.submit(SubmitRequest.builder().sessionId(id).agentRef("alpha")
                .userInput("first").initiator(Principal.user("tester")).openAttributes(firstAttrs).build());
        final TestLiveSession session = waitForSession(sessions, id);
        assertThat(session.awaitTurnStarted()).isTrue();
        session.completeCurrentTurn(TestLiveSession.ok("done"));
        firstOutcome.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(openerCalls.get()).isEqualTo(1);
        assertThat(capturedAttrs.get().getString("ops.agentId")).hasValue("agt-1");

        // Second submit — same session, different attributes. Opener must NOT be invoked again.
        final SubmitDisposition secondOutcome = manager.submit(SubmitRequest.builder().sessionId(id).agentRef("alpha")
                .userInput("second").initiator(Principal.user("tester")).openAttributes(secondAttrs).build());

        assertThat(session.awaitTurnCount(2)).isTrue();
        assertThat(session.submittedInputs()).contains("second");

        assertThat(openerCalls.get()).as("cached entry must not re-invoke the opener").isEqualTo(1);
        assertThat(capturedAttrs.get().getString("ops.agentId")).as("captured attrs unchanged from first open")
                .hasValue("agt-1");

        session.completeCurrentTurn(TestLiveSession.ok("done2"));
        secondOutcome.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("contextDiscriminator on SubmitRequest reaches opener as composite AgentRuntimeId")
    void contextDiscriminatorRoutedToOpener() throws Exception {
        final ConcurrentMap<SessionId, TestLiveSession> sessions = new ConcurrentHashMap<>();
        final AtomicReference<AgentRuntimeId> capturedRuntimeId = new AtomicReference<>();

        final LiveSessionOpener opener = (id, agentRuntimeId, options, attrs) -> {
            capturedRuntimeId.set(agentRuntimeId);
            return sessions.computeIfAbsent(id, TestLiveSession::new);
        };

        manager = SessionRouter.builder().sessionOpener(opener).sessionRecordStore(new InMemorySessionRecordStore())
                .idleTtl(Duration.ofMinutes(10)).nodeId("node-" + UUID.randomUUID()).build();

        final SessionId id = SessionId.of("c-disc-1");
        final SubmitRequest request = SubmitRequest.builder().sessionId(id).agentRef("alpha")
                .contextDiscriminator("tenant-a").userInput("hi").initiator(Principal.user("tester")).build();

        final SubmitDisposition outcome = manager.submit(request);
        final TestLiveSession session = waitForSession(sessions, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        assertThat(capturedRuntimeId.get()).isEqualTo(AgentRuntimeId.fromName("alpha", "tenant-a"));
        assertThat(capturedRuntimeId.get().agentName()).isEqualTo("alpha");
        assertThat(capturedRuntimeId.get().discriminator()).hasValue("tenant-a");

        session.completeCurrentTurn(TestLiveSession.ok("done"));
        outcome.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("absent contextDiscriminator yields the bare 'agent:<ref>' context id")
    void absentDiscriminatorYieldsBareRuntimeId() throws Exception {
        final ConcurrentMap<SessionId, TestLiveSession> sessions = new ConcurrentHashMap<>();
        final AtomicReference<AgentRuntimeId> capturedRuntimeId = new AtomicReference<>();

        final LiveSessionOpener opener = (id, agentRuntimeId, options, attrs) -> {
            capturedRuntimeId.set(agentRuntimeId);
            return sessions.computeIfAbsent(id, TestLiveSession::new);
        };

        manager = SessionRouter.builder().sessionOpener(opener).sessionRecordStore(new InMemorySessionRecordStore())
                .idleTtl(Duration.ofMinutes(10)).nodeId("node-" + UUID.randomUUID()).build();

        final SessionId id = SessionId.of("c-disc-bare");
        final SubmitRequest request = SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput("hi")
                .initiator(Principal.user("tester")).build();

        final SubmitDisposition outcome = manager.submit(request);
        final TestLiveSession session = waitForSession(sessions, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        assertThat(capturedRuntimeId.get()).isEqualTo(AgentRuntimeId.fromName("alpha"));
        assertThat(capturedRuntimeId.get().discriminator()).isEmpty();

        session.completeCurrentTurn(TestLiveSession.ok("done"));
        outcome.getFuture().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static TestLiveSession waitForSession(ConcurrentMap<SessionId, TestLiveSession> sessions, SessionId id)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 1_000L;
        while (System.currentTimeMillis() < deadline) {
            final TestLiveSession s = sessions.get(id);
            if (s != null) {
                return s;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("session was not opened within the deadline");
    }

    /**
     * Compile-time sanity: LiveSessionOptions / LiveSession references are kept so the test does not become a stale
     * shell when the module is shaken.
     */
    @SuppressWarnings("unused")
    private static LiveSession typeRef(LiveSessionOptions ignored) {
        return null;
    }
}
