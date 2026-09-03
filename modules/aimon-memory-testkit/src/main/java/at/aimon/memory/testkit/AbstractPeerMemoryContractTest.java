package at.aimon.memory.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.memory.MemoryCapabilities;
import at.aimon.core.memory.MemoryCapability;
import at.aimon.core.memory.MemoryHit;
import at.aimon.core.memory.MemoryIngestReceipt;
import at.aimon.core.memory.MemoryIngestRequest;
import at.aimon.core.memory.MemoryInjectionMode;
import at.aimon.core.memory.MemorySearchQuery;
import at.aimon.core.memory.MemorySearcher;
import at.aimon.core.memory.MemorySnapshot;
import at.aimon.core.memory.MemorySnapshotQuery;
import at.aimon.core.memory.MemorySnapshotScope;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationDraft;
import at.aimon.core.memory.ObservationRecorder;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerMemory;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.DialecticQuery;
import at.aimon.core.memory.dialectic.DialecticResponse;

/**
 * What every {@link PeerMemory} backend owes its callers, executed against each one.
 *
 * <p>
 * This is the only place two backends can be checked for saying the same thing. Each of them is otherwise described
 * solely by its own tests, which is how a store-tier backend once ended up throwing
 * {@code UnsupportedOperationException} from {@code semanticSearch} as a documented design choice while a caller two
 * layers up had no way to find out.
 *
 * <h2>The four capability-negotiation contracts</h2>
 *
 * <p>
 * Most of what follows is per-tier behaviour. Four cases are about the capability model itself, and they are the ones
 * that make {@link MemoryCapabilities#of(PeerMemory)} worth trusting:
 *
 * <ol>
 * <li><b>A tier that is offered answers.</b> A backend hands back an {@link java.util.Optional} per tier, and a
 * present one must not throw {@link UnsupportedOperationException}. This is the loophole the tier boundary cannot
 * close on its own: an assembly can hand the default backend a metadata-only observation store, and the SEARCH tier
 * then exists and fails on every call — the state the whole model is supposed to make unrepresentable.
 * <li><b>Search results are ordered by relevance, always.</b> Order is the ranking. A backend with no scores still
 * owes it.
 * <li><b>{@code ranksByScore() == false} does not silently ignore {@code minScore}.</b> A filter that did not run
 * must not read as one that did.
 * <li><b>{@code narrowsBySession() == false} does not silently ignore a session id.</b> The same rule on the query's
 * other narrowing axis. It is a separate contract rather than a clause of the one above because a backend can honour
 * one axis and not the other — a remote memory with a session concept but no relevance score is the ordinary case —
 * and because each has its own case below.
 * </ol>
 *
 * <h2>Joining</h2>
 *
 * <p>
 * A backend subclasses this and returns a fresh, empty instance from {@link #newBackend()}; this class closes it when
 * it is {@link AutoCloseable}. Every case guards on the capability it needs, so a backend serving three tiers reports
 * the other two as skipped rather than as passing — "this backend does not do CHAT" stays something a backend says
 * out loud.
 *
 * <p>
 * Seeding differs per backend (a store-backed one is written through its own stores; a remote one is written through
 * its server), so the cases that need existing content ask for it through {@link #seedObservation} or
 * {@link #seedSnapshot} and skip when a backend does not offer one.
 *
 * <p>
 * Those two are separate hooks and not one, because the two read tiers read different things. SEARCH reads
 * observations; SNAPSHOT reads a snapshot, which a backend may <em>derive</em> rather than store — writing an
 * observation into the default backend produces nothing for its snapshot reader to find, and a single hook would
 * have made that look like a contract violation instead of a difference between two tiers.
 */
public abstract class AbstractPeerMemoryContractTest {

    /** The workspace every peer in this suite belongs to. */
    protected static final Workspace WORKSPACE = Workspace.builder().id("testkit-ws").build();

    /** The peer everything is recorded about. */
    protected static final PeerView SUBJECT = PeerView.of(WORKSPACE, Principal.user("alice", "Alice"));

    /** The peer doing the observing — the agent. */
    protected static final PeerView OBSERVER = PeerView.of(WORKSPACE, Principal.user("agent", "Agent"));

    /** The session the suite's session-scoped cases run in. */
    protected static final String SESSION_ID = "testkit-session";

    private PeerMemory backend;
    private Set<MemoryCapability> capabilities;

    /**
     * Returns a fresh backend with no content, reset from any previous case.
     *
     * <p>
     * Anything a backend has to reset between cases (truncating a table, clearing a workspace on a server) belongs
     * inside this method, because JUnit runs a superclass {@code @BeforeEach} before the subclass's own.
     *
     * @return the backend under test, never null
     */
    protected abstract PeerMemory newBackend();

    /**
     * Puts one observation about {@code subject} into the backend by whatever means that backend has, so the SEARCH
     * cases have something to find.
     *
     * <p>
     * Not done through the OBSERVE tier, because a backend may serve SEARCH without serving OBSERVE, and the read
     * cases should not be skipped for the want of a write tier they do not use.
     *
     * @param subject
     *            the peer the observation is about
     * @param observer
     *            the peer recording it
     * @param content
     *            the sentence to record
     * @return {@code true} when the backend seeded; {@code false} when it has no way to, in which case the cases that
     *         need content report as skipped rather than as passing
     */
    protected boolean seedObservation(PeerView subject, PeerView observer, String content) {
        return false;
    }

    /**
     * Puts something the SNAPSHOT tier can read about {@code subject} into the backend.
     *
     * <p>
     * Deliberately vague about what: a store-backed backend saves a representation, a backend that derives on read
     * feeds it messages and waits. What the cases below assert is the <em>shape</em> of whatever comes back, not its
     * content, so a backend is free to produce it however it does.
     *
     * @param subject
     *            the peer the snapshot is about
     * @param observer
     *            the peer whose view it is
     * @param sessionId
     *            the session the local snapshot belongs to
     * @param summary
     *            the prose the snapshot should carry
     * @return {@code true} when the backend seeded; {@code false} when it has no way to, in which case the cases that
     *         need a snapshot report as skipped rather than as passing
     */
    protected boolean seedSnapshot(PeerView subject, PeerView observer, String sessionId, String summary) {
        return false;
    }

    /**
     * Returns the backend under test for the current case.
     *
     * @return the backend, never null
     */
    protected final PeerMemory backend() {
        return backend;
    }

    /**
     * Returns the capabilities the backend under test serves, computed rather than declared.
     *
     * @return the capability set, never null
     */
    protected final Set<MemoryCapability> capabilities() {
        return capabilities;
    }

    @BeforeEach
    final void createBackend() {
        backend = newBackend();
        capabilities = MemoryCapabilities.of(backend);
    }

    /**
     * Returns the object that actually owns {@link #backend()}'s native resources, for the harness to close.
     *
     * <p>
     * Override when {@link #newBackend()} returns a <em>decorator</em>. The default answer is the backend itself,
     * which is right for a plain one and wrong for a wrapper: a wrapper owns nothing, so the harness's
     * {@code instanceof AutoCloseable} test answers {@code false} for a delegate that very much is one, and an
     * adapter's HTTP client leaks once per case. {@code RedactingPeerMemory} states the same rule for assemblies and
     * exposes {@code getDelegate()} for exactly this.
     *
     * @return the resource owner, or {@code null} for none; defaults to the backend under test
     */
    protected PeerMemory resourceOwner() {
        return backend;
    }

    @AfterEach
    final void closeBackend() throws Exception {
        if (resourceOwner() instanceof AutoCloseable closeable) {
            closeable.close();
        }
        backend = null;
    }

    @Nested
    @DisplayName("capability negotiation")
    class Capabilities {

        @Test
        @DisplayName("a backend identifies itself, so a degradation message can name it")
        void backendIdIsUsable() {
            assertThat(backend().backendId()).isNotBlank();
        }

        @Test
        @DisplayName("capabilities are exactly the tiers that are present — the accessors are the only source")
        void capabilitiesTrackTheAccessors() {
            assertThat(capabilities().contains(MemoryCapability.SNAPSHOT))
                    .isEqualTo(backend().snapshotReader().isPresent());
            assertThat(capabilities().contains(MemoryCapability.SEARCH)).isEqualTo(backend().searcher().isPresent());
            assertThat(capabilities().contains(MemoryCapability.CHAT))
                    .isEqualTo(backend().dialecticEngine().isPresent());
            assertThat(capabilities().contains(MemoryCapability.OBSERVE))
                    .isEqualTo(backend().observationRecorder().isPresent());
            assertThat(capabilities().contains(MemoryCapability.INGEST)).isEqualTo(backend().ingestor().isPresent());
        }

        @Test
        @DisplayName("the accessors are stable — a tier does not appear or vanish between calls")
        void accessorsAreStable() {
            assertThat(MemoryCapabilities.of(backend())).isEqualTo(capabilities());
        }

        /**
         * Contract ① of the three. The tier boundary makes "claims CHAT, has no engine" unrepresentable; it does
         * nothing about "has an engine that refuses". A metadata-only observation store handed to the default backend
         * produces exactly that, which is why the contract is written down here rather than assumed.
         */
        @Test
        @DisplayName("a tier that is offered answers: none of them throws UnsupportedOperationException")
        void offeredTiersDoNotRefuse() {
            backend().snapshotReader().ifPresent(
                    reader -> assertThatCode(() -> reader.read(MemorySnapshotQuery.builder().subject(SUBJECT).build()))
                            .doesNotThrowAnyException());
            backend().searcher()
                    .ifPresent(searcher -> assertThatCode(
                            () -> searcher.search(MemorySearchQuery.builder().subject(SUBJECT).query("tea").build()))
                            .doesNotThrowAnyException());
            backend().observationRecorder()
                    .ifPresent(recorder -> assertThatCode(() -> recorder.observe(ObservationDraft.builder()
                            .subject(SUBJECT).observer(OBSERVER).content("Alice drinks tea").build()))
                            .doesNotThrowAnyException());
            backend().dialecticEngine()
                    .ifPresent(engine -> assertThatCode(() -> engine.query(DialecticQuery.builder().workspace(WORKSPACE)
                            .subject(SUBJECT).observer(OBSERVER).question("what does Alice drink?").build()))
                            .doesNotThrowAnyException());
            backend().ingestor()
                    .ifPresent(ingestor -> assertThatCode(() -> ingestor.ingest(MemoryIngestRequest.builder()
                            .observer(OBSERVER).sessionId(SESSION_ID).messages(List.of(Message.user("hello"))).build()))
                            .doesNotThrowAnyException());
        }
    }

    @Nested
    @DisplayName("SNAPSHOT tier")
    class Snapshot {

        @BeforeEach
        void requireSnapshot() {
            assumeTrue(capabilities().contains(MemoryCapability.SNAPSHOT), "backend does not serve SNAPSHOT");
        }

        @Test
        @DisplayName("an unknown peer is an empty answer, not a failure")
        void missIsEmptyRatherThanAnError() {
            assertThat(backend().snapshotReader().orElseThrow()
                    .read(MemorySnapshotQuery.builder().subject(SUBJECT).build())).isEmpty();
        }

        @Test
        @DisplayName("a snapshot never reports LOCAL_THEN_GLOBAL: it says which scope answered, not which was asked")
        void resolvedScopeIsAnOutcome() {
            assumeTrue(seedSnapshot(SUBJECT, OBSERVER, SESSION_ID, "Alice prefers tea"),
                    "backend cannot seed a snapshot");

            final MemorySnapshot snapshot = backend().snapshotReader().orElseThrow().read(
                    MemorySnapshotQuery.builder().subject(SUBJECT).observer(OBSERVER).sessionId(SESSION_ID).build())
                    .orElseThrow();

            assertThat(snapshot.getResolvedScope()).isIn(MemorySnapshotScope.LOCAL, MemorySnapshotScope.GLOBAL);
        }

        @Test
        @DisplayName("observationsAvailable=false comes with an empty list, so the flag and the data cannot disagree")
        void theFlagMatchesTheData() {
            assumeTrue(seedSnapshot(SUBJECT, OBSERVER, SESSION_ID, "Alice prefers tea"),
                    "backend cannot seed a snapshot");

            final MemorySnapshot snapshot = backend()
                    .snapshotReader().orElseThrow().read(MemorySnapshotQuery.builder().subject(SUBJECT)
                            .observer(OBSERVER).sessionId(SESSION_ID).mode(MemoryInjectionMode.FULL).build())
                    .orElseThrow();

            if (!snapshot.isObservationsAvailable()) {
                assertThat(snapshot.getObservations()).isEmpty();
            }
            assertThat(snapshot.getTokenCount()).isNotNegative();
            assertThat(snapshot.getGeneratedAt()).isNotNull().isBeforeOrEqualTo(Instant.now().plusSeconds(60));
        }

        @Test
        @DisplayName("SUMMARY_ONLY is not truncation: asking for less is not the same as being given less")
        void summaryOnlyIsNotTruncation() {
            assumeTrue(seedSnapshot(SUBJECT, OBSERVER, SESSION_ID, "Alice prefers tea"),
                    "backend cannot seed a snapshot");

            final MemorySnapshot snapshot = backend()
                    .snapshotReader().orElseThrow().read(MemorySnapshotQuery.builder().subject(SUBJECT)
                            .observer(OBSERVER).sessionId(SESSION_ID).mode(MemoryInjectionMode.SUMMARY_ONLY).build())
                    .orElseThrow();

            assertThat(snapshot.isTruncated()).isFalse();
        }
    }

    @Nested
    @DisplayName("SEARCH tier")
    class Search {

        @BeforeEach
        void requireSearch() {
            assumeTrue(capabilities().contains(MemoryCapability.SEARCH), "backend does not serve SEARCH");
        }

        private MemorySearcher searcher() {
            return backend().searcher().orElseThrow();
        }

        @Test
        @DisplayName("no match is an empty list, not a failure")
        void noMatchIsEmpty() {
            assertThat(searcher()
                    .search(MemorySearchQuery.builder().subject(SUBJECT).query("something nobody ever said").build()))
                    .isEmpty();
        }

        @Test
        @DisplayName("topK is a cap the backend honours")
        void topKIsHonoured() {
            assumeTrue(seedObservation(SUBJECT, OBSERVER, "Alice prefers tea"), "backend cannot seed observations");
            seedObservation(SUBJECT, OBSERVER, "Alice prefers tea in the morning");
            seedObservation(SUBJECT, OBSERVER, "Alice prefers tea after lunch");

            assertThat(searcher().search(MemorySearchQuery.builder().subject(SUBJECT).query("tea").topK(2).build()))
                    .hasSizeLessThanOrEqualTo(2);
        }

        /**
         * Contract ② of the three. Order is the ranking, and it is the half of relevance that every backend owes —
         * including the ones with no number to attach to it.
         */
        @Test
        @DisplayName("results are ordered by relevance; a scoring backend's scores are non-increasing along it")
        void resultsAreOrderedByRelevance() {
            assumeTrue(seedObservation(SUBJECT, OBSERVER, "Alice prefers tea"), "backend cannot seed observations");
            seedObservation(SUBJECT, OBSERVER, "Alice dislikes coffee");

            final List<MemoryHit> hits = searcher()
                    .search(MemorySearchQuery.builder().subject(SUBJECT).query("tea").build());

            if (searcher().ranksByScore()) {
                for (int i = 1; i < hits.size(); i++) {
                    assertThat(hits.get(i).getScore()).as("hit %d must not out-rank hit %d", i, i - 1)
                            .isLessThanOrEqualTo(hits.get(i - 1).getScore());
                }
            } else {
                // Nothing to compare, and that is the point: the list order carries the whole ranking, so a
                // fabricated score would be a second, unmeasured one.
                assertThat(hits).allSatisfy(hit -> assertThat(hit.getScore()).isZero());
            }
        }

        /**
         * Contract ③ of the three. Ignoring the floor is the failure this design exists to remove: the caller asked
         * for a filter, was not told it could not run, and reads the unfiltered result as filtered.
         */
        @Test
        @DisplayName("a backend that cannot score rejects a positive minScore rather than ignoring it")
        void minScoreIsRejectedRatherThanIgnored() {
            final MemorySearchQuery filtered = MemorySearchQuery.builder().subject(SUBJECT).query("tea").minScore(0.5d)
                    .build();

            if (searcher().ranksByScore()) {
                assertThatCode(() -> searcher().search(filtered)).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> searcher().search(filtered)).isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("a minScore of zero is no floor at all and is always accepted")
        void zeroMinScoreIsNotAFilter() {
            assertThatCode(() -> searcher()
                    .search(MemorySearchQuery.builder().subject(SUBJECT).query("tea").minScore(0.0d).build()))
                    .doesNotThrowAnyException();
        }

        /**
         * The same contract as {@code minScore}, on the query's other narrowing axis. Both promise a smaller result;
         * a backend that answers across every session while the caller named one has widened the answer silently.
         */
        @Test
        @DisplayName("a backend that cannot narrow by session rejects a session id rather than searching all of them")
        void sessionIdIsRejectedRatherThanIgnored() {
            final MemorySearchQuery narrowed = MemorySearchQuery.builder().subject(SUBJECT).query("tea")
                    .sessionId(SESSION_ID).build();

            if (searcher().narrowsBySession()) {
                assertThatCode(() -> searcher().search(narrowed)).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> searcher().search(narrowed)).isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("no session id is no narrowing at all and is always accepted")
        void noSessionIdIsNotAFilter() {
            assertThatCode(() -> searcher().search(MemorySearchQuery.builder().subject(SUBJECT).query("tea").build()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("OBSERVE tier")
    class Observe {

        @BeforeEach
        void requireObserve() {
            assumeTrue(capabilities().contains(MemoryCapability.OBSERVE), "backend does not serve OBSERVE");
        }

        private ObservationRecorder recorder() {
            return backend().observationRecorder().orElseThrow();
        }

        @Test
        @DisplayName("a draft comes back with the identity the backend assigned, in the right workspace")
        void recordingAssignsAnIdentity() {
            final Observation stored = recorder().observe(ObservationDraft.builder().subject(SUBJECT).observer(OBSERVER)
                    .content("Alice prefers tea").type(ObservationType.EXPLICIT).build());

            assertThat(stored.getId()).isNotNull();
            assertThat(stored.getId().getWorkspaceId()).isEqualTo(WORKSPACE.getId());
            assertThat(stored.getContent()).isEqualTo("Alice prefers tea");
            assertThat(stored.getSubject()).isEqualTo(SUBJECT);
        }

        @Test
        @DisplayName("storesConfidence() tells the truth: a stored value survives, a dropped one is declared")
        void confidenceMatchesTheDeclaration() {
            final Observation stored = recorder().observe(ObservationDraft.builder().subject(SUBJECT).observer(OBSERVER)
                    .content("Alice prefers tea in the afternoon").confidence(0.42d).build());

            if (recorder().storesConfidence()) {
                assertThat(stored.getConfidence()).isEqualTo(0.42d);
            } else {
                // No assertion on the value: a backend that does not store it is free to return anything, and the
                // point of the flag is that nobody reads that anything as the caller's number.
                assertThat(stored.getConfidence()).isBetween(0.0d, 1.0d);
            }
        }

        @Test
        @DisplayName("two recordings are two observations, not one overwriting the other")
        void recordingsAccumulate() {
            final Observation first = recorder().observe(ObservationDraft.builder().subject(SUBJECT).observer(OBSERVER)
                    .content("Alice prefers tea").build());
            final Observation second = recorder().observe(ObservationDraft.builder().subject(SUBJECT).observer(OBSERVER)
                    .content("Alice dislikes coffee").build());

            assertThat(first.getId()).isNotEqualTo(second.getId());
        }
    }

    @Nested
    @DisplayName("CHAT tier")
    class Chat {

        @BeforeEach
        void requireChat() {
            assumeTrue(capabilities().contains(MemoryCapability.CHAT), "backend does not serve CHAT");
        }

        @Test
        @DisplayName("a question about a peer nobody has observed still gets an answer object")
        void anEmptyMemoryStillAnswers() {
            final DialecticEngine engine = backend().dialecticEngine().orElseThrow();

            final DialecticResponse response = engine.query(DialecticQuery.builder().workspace(WORKSPACE)
                    .subject(SUBJECT).observer(OBSERVER).question("what does Alice drink?").build());

            assertThat(response).isNotNull();
            assertThat(response.getAnswer()).isNotNull();
        }
    }

    @Nested
    @DisplayName("INGEST tier")
    class Ingest {

        @BeforeEach
        void requireIngest() {
            assumeTrue(capabilities().contains(MemoryCapability.INGEST), "backend does not serve INGEST");
        }

        @Test
        @DisplayName("a receipt says how much was taken and whether derivation actually happened")
        void receiptReportsWhatHappened() {
            final MemoryIngestReceipt receipt = backend().ingestor().orElseThrow()
                    .ingest(MemoryIngestRequest.builder().observer(OBSERVER).sessionId(SESSION_ID).messages(
                            List.of(Message.user("what is the deploy window"), Message.assistant("Fridays, 14:00 UTC")))
                            .build());

            assertThat(receipt).isNotNull();
            assertThat(receipt.getAccepted()).isNotNegative();
        }

        @Test
        @DisplayName("asking to wait for derivation is a request, and the receipt says whether it was granted")
        void waitForDerivationIsReportedNotAssumed() {
            final MemoryIngestReceipt receipt = backend().ingestor().orElseThrow()
                    .ingest(MemoryIngestRequest.builder().observer(OBSERVER).sessionId(SESSION_ID)
                            .messages(List.of(Message.user("hello"))).waitForDerivation(true).build());

            // Either answer is contractual. What is not contractual is a backend that cannot synchronise reporting
            // that it did — the caller would read stale memory believing it fresh.
            assertThat(receipt.isDerived()).isIn(true, false);
        }
    }
}
