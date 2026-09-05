package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.input.FileInput;
import at.aimon.core.agent.input.ImageInput;
import at.aimon.core.agent.input.MultimodalInput;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * What the router owes a turn that is not made of text.
 *
 * <p>
 * A {@code LiveSession} has taken a {@code UserInput} for some time, so an image or a document worked on whichever
 * host held the handle. A submission that went through the router did not: {@code SubmitRequest} carried a
 * {@code String}, so the same application lost multimodal the moment it scaled out — and lost it silently, because
 * every hop still had something plausible to forward.
 *
 * <p>
 * The two hops are tested separately because they fail differently. The local hop is pure object plumbing —
 * {@code runTurnLoop} rewraps the request as an {@code InboundMessage} for its own drain loop, so a flattening there
 * would lose the image on a <em>single-node</em> deployment, which is the one nobody would think to check. The
 * forwarded hop is the one the item was about. Neither can see what serialization does to the image, and that is
 * deliberate: the in-memory inbox does not serialize. Each backend's own codec test covers its wire, and
 * {@code AbstractMultiNodeSessionContractTest} runs the whole path over a real one.
 */
@DisplayName("SessionRouter multimodal submit")
class SessionRouterMultimodalSubmitTest {

    private SessionLeaseStore leaseStore;
    private SessionSignalBus bus;
    private SessionInbox inbox;
    private SessionRecordStore repository;
    private IdempotencyStore idempotency;

    private final List<TestManagerHarness> nodes = new ArrayList<>();

    @BeforeEach
    void wireSharedBackend() {
        leaseStore = new InMemorySessionLeaseStore();
        bus = new InMemorySignalBus();
        inbox = new InMemorySessionInbox();
        repository = new InMemorySessionRecordStore();
        idempotency = new InMemoryIdempotencyStore();
    }

    @AfterEach
    void closeNodes() {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            nodes.get(i).close();
        }
    }

    @Test
    @DisplayName("the holder runs the image it was handed, not a description of it")
    void locallyExecutedTurnKeepsItsInput() throws Exception {
        final TestManagerHarness holder = node("node-A");
        final SessionId id = SessionId.of("c-mm-local");
        final UserInput input = MultimodalInput.of(TextInput.of("what is in this?"),
                ImageInput.of(new byte[]{1, 2, 3, 4}, "image/png"));

        holder.manager().submit(request(id, input));

        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();
        assertThat(session.submittedUserInputs()).containsExactly(input);
    }

    @Test
    @DisplayName("a forwarded image is the image the holder runs")
    void forwardedTurnKeepsItsInput() throws Exception {
        final TestManagerHarness holder = node("node-A");
        final TestManagerHarness peer = node("node-B");
        final SessionId id = SessionId.of("c-mm-fwd");
        final UserInput input = MultimodalInput.of(TextInput.of("summarize the attachment"), FileInput
                .of("report body".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain", "report.txt"));

        holder.manager().submit(RequestFixtures.submit(id, "alpha", "first"));
        final TestLiveSession session = awaitSession(holder, id);
        assertThat(session.awaitTurnStarted()).isTrue();

        final SubmitDisposition forwarded = peer.manager().submit(request(id, input));
        assertThat(forwarded.getKind()).as("node-B cannot win a lock node-A is holding")
                .isEqualTo(SubmitDisposition.Kind.FORWARDED);

        session.completeCurrentTurn(TestLiveSession.ok("first-done"));
        assertThat(session.awaitTurnCount(2)).as("node-A must drain node-B's message as its next turn").isTrue();

        assertThat(session.submittedUserInputs().get(1)).isEqualTo(input);
    }

    @Test
    @DisplayName("a text turn's idempotency hash is still sha256 of the bare text")
    void textIdempotencyHashIsUnchanged() throws Exception {
        // Cross-version, not cosmetic. The hash is written to the shared IdempotencyStore and recomputed by whichever
        // node a retry lands on, which during a rolling upgrade is as likely to be one that predates SubmitRequest
        // carrying a UserInput. That node computes sha256(text). If this one hashed the encoded form instead, every
        // legitimate text retry across the version boundary would come back as IdempotencyConflictException — "key
        // reused with different input" — for as long as the two builds coexist.
        final TestManagerHarness holder = node("node-A");
        final SessionId id = SessionId.of("c-mm-idem");

        holder.manager().submit(SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput("hello")
                .idempotencyKey("k-1").initiator(Principal.user("tester")).build());
        assertThat(awaitSession(holder, id).awaitTurnStarted()).isTrue();

        assertThat(idempotency.find("k-1").orElseThrow().getInputHash()).isEqualTo(sha256("hello"));
    }

    @Test
    @DisplayName("a non-text turn hashes over the encoding, so two different images are two different inputs")
    void nonTextInputsHashDistinctly() throws Exception {
        // No compatibility constraint here — an older node could not have submitted one of these at all — but the
        // hash still has to be a function of the input, or a second image reusing a key would silently replay the
        // first one's answer.
        final TestManagerHarness holder = node("node-A");
        final SessionId first = SessionId.of("c-mm-h1");
        final SessionId second = SessionId.of("c-mm-h2");

        holder.manager()
                .submit(SubmitRequest.builder().sessionId(first).agentRef("alpha")
                        .userInput(ImageInput.of(new byte[]{1, 2, 3}, "image/png")).idempotencyKey("k-a")
                        .initiator(Principal.user("tester")).build());
        holder.manager()
                .submit(SubmitRequest.builder().sessionId(second).agentRef("alpha")
                        .userInput(ImageInput.of(new byte[]{9, 9, 9}, "image/png")).idempotencyKey("k-b")
                        .initiator(Principal.user("tester")).build());
        assertThat(awaitSession(holder, first).awaitTurnStarted()).isTrue();
        assertThat(awaitSession(holder, second).awaitTurnStarted()).isTrue();

        final String hashA = idempotency.find("k-a").orElseThrow().getInputHash();
        final String hashB = idempotency.find("k-b").orElseThrow().getInputHash();
        assertThat(hashA).isNotEqualTo(hashB);
        // And not the asText() rendering either — two images of the same length and MIME type render identically.
        assertThat(hashA).isNotEqualTo(sha256("[Image: image/png, 3 bytes]"));
    }

    private static SubmitRequest request(SessionId id, UserInput input) {
        return SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput(input)
                .initiator(Principal.user("tester")).build();
    }

    private TestManagerHarness node(String nodeId) {
        final TestManagerHarness harness = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency).build();
        nodes.add(harness);
        return harness;
    }

    private static TestLiveSession awaitSession(TestManagerHarness harness, SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        final TestLiveSession session = harness.session(id);
        assertThat(session).as("a session for %s should have been opened", id).isNotNull();
        return session;
    }

    /** Spelled out here rather than reused from the router, so the assertion cannot follow a change to it. */
    private static String sha256(String input) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
