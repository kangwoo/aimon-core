package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.Staticness;
import at.aimon.core.agent.prompt.SystemPromptPart;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

@DisplayName("SnapshotMemoryContextProvider")
class SnapshotMemoryContextProviderTest {

    private static final Instant T0 = Instant.parse("2024-01-15T10:00:00Z");
    private static final String SESSION_ID = "sess-1";

    private InMemoryRepresentationStore store;
    private Workspace ws;
    private Principal alicePrincipal;
    private Principal bobPrincipal;
    private PeerView alice;
    private PeerView bob;

    @BeforeEach
    void setUp() {
        store = new InMemoryRepresentationStore();
        ws = Workspace.builder().id("ws-1").build();
        alicePrincipal = Principal.user("alice", "Alice");
        bobPrincipal = Principal.user("bob", "Bob");
        alice = PeerView.of(ws, alicePrincipal);
        bob = PeerView.of(ws, bobPrincipal);
    }

    private Representation localRep(PeerView peer, String sessionId, String summary, List<Observation> observations,
            int tokenCount) {
        return Representation.builder().subject(peer).observer(peer).sessionId(sessionId).summary(summary)
                .observations(observations).tokenCount(tokenCount).generatedAt(T0).build();
    }

    private Representation localRep(String summary, List<Observation> observations, int tokenCount) {
        return localRep(alice, SESSION_ID, summary, observations, tokenCount);
    }

    private Representation globalRep(String summary) {
        return Representation.builder().subject(alice).summary(summary).generatedAt(T0).build();
    }

    private Observation observation(String localId, String content, double confidence) {
        return Observation.builder().id(ObservationId.of(ws, localId)).subject(alice).observer(alice).content(content)
                .type(ObservationType.EXPLICIT).confidence(confidence).createdAt(T0).build();
    }

    private MemorySnapshotReader reader() {
        return SnapshotMemoryContextProvider.readerOver(store);
    }

    /** A single-peer provider, as a CLI process wires it. */
    private SnapshotMemoryContextProvider provider(MemoryInjectionMode mode, int maxTokens) {
        return new SnapshotMemoryContextProvider(reader(), ws, MemoryPeerResolver.fixed(alicePrincipal), mode,
                maxTokens);
    }

    /** A multi-caller provider, as a server wires it. */
    private SnapshotMemoryContextProvider callerProvider() {
        return new SnapshotMemoryContextProvider(reader(), ws, MemoryPeerResolver.caller(),
                MemoryInjectionMode.SUMMARY_ONLY, 0);
    }

    private static MemoryContextRequest inSession(String sessionId) {
        return MemoryContextRequest.builder().sessionId(SessionId.of(sessionId)).build();
    }

    private static MemoryContextRequest from(Principal principal, String sessionId) {
        return MemoryContextRequest.builder().principal(principal).sessionId(SessionId.of(sessionId)).build();
    }

    @Nested
    @DisplayName("constructor")
    class Construction {

        @Test
        @DisplayName("rejects null reader, workspace, resolver, mode")
        void rejectsNullCoreArgs() {
            final MemoryPeerResolver resolver = MemoryPeerResolver.fixed(alicePrincipal);
            assertThatThrownBy(
                    () -> new SnapshotMemoryContextProvider(null, ws, resolver, MemoryInjectionMode.SUMMARY_ONLY, 0))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SnapshotMemoryContextProvider(reader(), null, resolver,
                    MemoryInjectionMode.SUMMARY_ONLY, 0)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SnapshotMemoryContextProvider.readerOver(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(
                    () -> new SnapshotMemoryContextProvider(reader(), ws, null, MemoryInjectionMode.SUMMARY_ONLY, 0))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SnapshotMemoryContextProvider(reader(), ws, resolver, null, 0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects negative maxTokens")
        void rejectsNegativeMaxTokens() {
            assertThatThrownBy(() -> new SnapshotMemoryContextProvider(reader(), ws,
                    MemoryPeerResolver.fixed(alicePrincipal), MemoryInjectionMode.SUMMARY_ONLY, -1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxTokens");
        }
    }

    @Nested
    @DisplayName("scope resolution")
    class ScopeResolution {

        @Test
        @DisplayName("one provider serves two sessions and gives each its own local representation")
        void resolvesPerSession() {
            // The regression this whole SPI change exists for. The provider is built once, agent-scoped, and the
            // session arrives with the call — so session B never reads what was derived in session A.
            store.save(localRep(alice, "sess-A", "what happened in A", List.of(), 5));
            store.save(localRep(alice, "sess-B", "what happened in B", List.of(), 5));

            final SnapshotMemoryContextProvider shared = provider(MemoryInjectionMode.SUMMARY_ONLY, 0);

            assertThat(shared.provide(inSession("sess-A"))).hasValueSatisfying(
                    part -> assertThat(part.getContent()).contains("what happened in A").doesNotContain("in B"));
            assertThat(shared.provide(inSession("sess-B"))).hasValueSatisfying(
                    part -> assertThat(part.getContent()).contains("what happened in B").doesNotContain("in A"));
        }

        @Test
        @DisplayName("caller mode gives each principal its own memory and never the other's")
        void resolvesPerCaller() {
            // Fixing only the session id would not have closed this: the GLOBAL fallback below is keyed on the
            // subject alone, so a peer baked into the provider leaks through it whatever the session says.
            store.save(Representation.builder().subject(alice).summary("alice is a DBA").generatedAt(T0).build());
            store.save(Representation.builder().subject(bob).summary("bob is on call").generatedAt(T0).build());

            final SnapshotMemoryContextProvider shared = callerProvider();

            assertThat(shared.provide(from(alicePrincipal, "sess-1"))).hasValueSatisfying(
                    part -> assertThat(part.getContent()).contains("alice is a DBA").doesNotContain("bob"));
            assertThat(shared.provide(from(bobPrincipal, "sess-2"))).hasValueSatisfying(
                    part -> assertThat(part.getContent()).contains("bob is on call").doesNotContain("DBA"));
        }

        @Test
        @DisplayName("caller mode contributes nothing for an unidentified execution")
        void anonymousUnderCallerModeGetsNothing() {
            // Not "fall back to some default peer" — that would be handing one user's representation to whoever
            // arrives without a name. A subagent fork lands here too: it carries no principal by design.
            store.save(globalRep("alice is a DBA"));

            assertThat(callerProvider().provide(MemoryContextRequest.empty())).isEmpty();
        }

        @Test
        @DisplayName("fixed mode ignores the caller, because one peer wrote everything in the store")
        void fixedModeIgnoresTheCaller() {
            store.save(globalRep("alice is a DBA"));

            assertThat(provider(MemoryInjectionMode.SUMMARY_ONLY, 0).provide(from(bobPrincipal, SESSION_ID)))
                    .hasValueSatisfying(part -> assertThat(part.getContent()).contains("alice is a DBA"));
        }

        @Test
        @DisplayName("a session-less execution misses LOCAL and still reaches GLOBAL")
        void sessionLessExecutionFallsBackToGlobal() {
            store.save(localRep("session-local detail", List.of(), 5));
            store.save(globalRep("durable summary"));

            final Optional<SystemPromptPart> part = provider(MemoryInjectionMode.SUMMARY_ONLY, 0)
                    .provide(MemoryContextRequest.empty());

            assertThat(part).isPresent();
            assertThat(part.get().getContent()).contains("scope: global").contains("durable summary")
                    .doesNotContain("session-local detail");
        }

        @Test
        @DisplayName("rejects a null request")
        void rejectsNullRequest() {
            assertThatThrownBy(() -> provider(MemoryInjectionMode.SUMMARY_ONLY, 0).provide(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("provide")
    class Provide {

        @Test
        @DisplayName("returns empty when store has no representation for the scope")
        void emptyWhenNoneStored() {
            assertThat(provider(MemoryInjectionMode.SUMMARY_ONLY, 0).provide(inSession(SESSION_ID))).isEmpty();
        }

        @Test
        @DisplayName("returns LOCAL representation when present")
        void returnsLocalHit() {
            store.save(localRep("local summary", List.of(), 12));

            Optional<SystemPromptPart> part = provider(MemoryInjectionMode.SUMMARY_ONLY, 0)
                    .provide(inSession(SESSION_ID));

            assertThat(part).isPresent();
            assertThat(part.get().getStaticness()).isEqualTo(Staticness.DYNAMIC);
            assertThat(part.get().getKind()).isEqualTo("memory");
            assertThat(part.get().getContent()).contains("Known about " + alice.key()).contains("scope: local")
                    .contains("Summary:").contains("local summary");
        }

        @Test
        @DisplayName("falls back to GLOBAL when LOCAL miss")
        void fallsBackToGlobal() {
            store.save(globalRep("global summary"));

            Optional<SystemPromptPart> part = provider(MemoryInjectionMode.SUMMARY_ONLY, 0)
                    .provide(inSession(SESSION_ID));

            assertThat(part).isPresent();
            assertThat(part.get().getContent()).contains("scope: global").contains("global summary");
        }

        @Test
        @DisplayName("prefers LOCAL over GLOBAL when both exist")
        void prefersLocalOverGlobal() {
            store.save(globalRep("global summary"));
            store.save(localRep("local summary", List.of(), 5));

            Optional<SystemPromptPart> part = provider(MemoryInjectionMode.SUMMARY_ONLY, 0)
                    .provide(inSession(SESSION_ID));

            assertThat(part).isPresent();
            assertThat(part.get().getContent()).contains("scope: local").contains("local summary")
                    .doesNotContain("global summary");
        }

        @Test
        @DisplayName("does not match LOCAL representations recorded by another observer")
        void localMissOnDifferentObserver() {
            // Stored LOCAL is for observer=alice; the execution resolves to bob, who has no LOCAL row and no GLOBAL
            // one either — so the provider returns empty rather than leaking another peer's view.
            store.save(localRep("alice's view", List.of(), 5));

            assertThat(callerProvider().provide(from(bobPrincipal, SESSION_ID))).isEmpty();
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {

        @Test
        @DisplayName("SUMMARY_ONLY omits observations even when present")
        void summaryOnlyOmitsObservations() {
            Observation obs = observation("obs-1", "alice prefers dark mode", 0.9d);
            store.save(localRep("alice has UI preferences", List.of(obs), 4));

            Optional<SystemPromptPart> part = provider(MemoryInjectionMode.SUMMARY_ONLY, 0)
                    .provide(inSession(SESSION_ID));

            assertThat(part).isPresent();
            assertThat(part.get().getContent()).contains("alice has UI preferences").doesNotContain("Observations")
                    .doesNotContain("alice prefers dark mode");
        }

        @Test
        @DisplayName("FULL with maxTokens=0 (unbounded) includes observations")
        void fullUnboundedIncludesObservations() {
            Observation obs = observation("obs-1", "alice prefers dark mode", 0.9d);
            store.save(localRep("alice has UI preferences", List.of(obs), 999));

            Optional<SystemPromptPart> part = provider(MemoryInjectionMode.FULL, 0).provide(inSession(SESSION_ID));

            assertThat(part).isPresent();
            assertThat(part.get().getContent()).contains("Observations (1)").contains("alice prefers dark mode")
                    .contains("confidence=0.90");
        }

        @Test
        @DisplayName("FULL within budget includes observations")
        void fullWithinBudgetIncludesObservations() {
            Observation obs = observation("obs-1", "alice prefers dark mode", 0.9d);
            store.save(localRep("alice has UI preferences", List.of(obs), 50));

            Optional<SystemPromptPart> part = provider(MemoryInjectionMode.FULL, 100).provide(inSession(SESSION_ID));

            assertThat(part).isPresent();
            assertThat(part.get().getContent()).contains("Observations (1)").contains("alice prefers dark mode")
                    .doesNotContain("over budget");
        }

        @Test
        @DisplayName("FULL over budget drops observations and notes the cap")
        void fullOverBudgetDropsObservations() {
            Observation obs = observation("obs-1", "alice prefers dark mode", 0.9d);
            store.save(localRep("alice has UI preferences", List.of(obs), 200));

            Optional<SystemPromptPart> part = provider(MemoryInjectionMode.FULL, 100).provide(inSession(SESSION_ID));

            assertThat(part).isPresent();
            assertThat(part.get().getContent()).contains("over budget=100, observations omitted")
                    .doesNotContain("Observations (").doesNotContain("alice prefers dark mode");
        }

        @Test
        @DisplayName("empty summary renders as (empty) placeholder")
        void emptySummaryPlaceholder() {
            store.save(localRep("", List.of(), 0));

            Optional<SystemPromptPart> part = provider(MemoryInjectionMode.SUMMARY_ONLY, 0)
                    .provide(inSession(SESSION_ID));

            assertThat(part).isPresent();
            assertThat(part.get().getContent()).contains("Summary:").contains("(empty)");
        }
    }
}
