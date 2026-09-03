package at.aimon.bootstrap.assemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.bootstrap.RuntimeDegradations;
import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.MemoryHit;
import at.aimon.core.memory.MemoryIngestReceipt;
import at.aimon.core.memory.MemoryIngestor;
import at.aimon.core.memory.MemorySearchQuery;
import at.aimon.core.memory.MemorySearcher;
import at.aimon.core.memory.MemorySnapshotReader;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationDraft;
import at.aimon.core.memory.ObservationRecorder;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerMemory;
import at.aimon.core.memory.RedactingPeerMemory;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.DialecticQuery;
import at.aimon.core.memory.dialectic.DialecticResponse;
import at.aimon.core.memory.redaction.RedactionPolicy;

/**
 * Which memory components a spec produces, and what it says about the ones it does not.
 *
 * <p>
 * The assertions on {@link RuntimeDegradations} are the substance of this test rather than a decoration on it.
 * Every gap here is silent by construction — an injected memory part that is empty forever looks exactly like a
 * peer nobody has observed yet — so the degradation is the only place the operator can find out before the
 * feature quietly fails to matter.
 */
class MemoryAssemblyTest {

    private static final Workspace WORKSPACE = Workspace.builder().id("ws-1").build();
    private static final Principal PEER = Principal.user("kangwoo");

    private final RuntimeDegradations.Collector degradations = RuntimeDegradations.collector();

    @Test
    @DisplayName("no spec produces nothing and records nothing")
    void noSpecProducesNothing() {
        // A stack without memory is not a degraded stack — it never claimed the capability.
        final MemoryAssembly assembly = MemoryAssembly.from(null, degradations);

        assertThat(assembly.getPeerMemory()).isEmpty();
        assertThat(assembly.getContextProvider()).isEmpty();
        assertThat(assembly.getContextEnricher()).isEmpty();
        assertThat(assembly.getToolProvider()).isEmpty();
        assertThat(degradations.build().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a fixed peer with both stores produces all three components")
    void fixedPeerWithBothStoresProducesEverything() {
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).representationStore(mock(RepresentationStore.class))
                .observationStore(mock(ObservationStore.class)).redactionPolicy(mock(RedactionPolicy.class)).build();

        final MemoryAssembly assembly = MemoryAssembly.from(spec, degradations);

        assertThat(assembly.getContextProvider()).isPresent();
        assertThat(assembly.getContextEnricher()).isPresent();
        assertThat(assembly.getToolProvider()).isPresent();
        assertThat(assembly.getPeerMemory()).get().extracting(PeerMemory::backendId).isEqualTo("default");
    }

    @Test
    @DisplayName("a fixed peer with only observations registers the tools but injects nothing")
    void fixedPeerWithOnlyObservationsHasToolsAndNoInjection() {
        // Nothing is derived, so there is no snapshot to put in the prompt — but Observe and MemorySearch still
        // have a store and an observer, which is a coherent deployment rather than a half-configured one.
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).observationStore(mock(ObservationStore.class))
                .redactionPolicy(mock(RedactionPolicy.class)).build();

        final MemoryAssembly assembly = MemoryAssembly.from(spec, degradations);

        assertThat(assembly.getContextProvider()).isEmpty();
        assertThat(assembly.getContextEnricher()).isPresent();
        assertThat(assembly.getToolProvider()).isPresent();
        assertThat(degradations.build().has(MemoryAssembly.CAPABILITY_SNAPSHOT)).isTrue();
    }

    @Test
    @DisplayName("per-caller injects a memory part and registers no tools, and says why")
    void perCallerHasNoTools() {
        // The seam: a ToolContextEnricher is handed a session and an execution but no principal, so there is
        // nothing for it to resolve a per-call observer from. Registering the tools anyway would give the model
        // three that answer "no workspace in context" to every call.
        final MemorySpec spec = MemorySpec.perCaller(WORKSPACE).representationStore(mock(RepresentationStore.class))
                .build();

        final MemoryAssembly assembly = MemoryAssembly.from(spec, degradations);

        assertThat(assembly.getContextProvider()).isPresent();
        assertThat(assembly.getContextEnricher()).isEmpty();
        assertThat(assembly.getToolProvider()).isEmpty();
        assertThat(degradations.build().has(MemoryAssembly.CAPABILITY_TOOLS)).isTrue();
    }

    @Test
    @DisplayName("the missing ingest path is recorded for every store-only deployment, fixed peer included")
    void ingestIsDegradedWithoutAQueue() {
        // The stack has no deriver, no derivation queue and no dreamer. Fully wired on the read side is still
        // read-only, and the symptom of not knowing that is an empty memory part nobody can explain.
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).representationStore(mock(RepresentationStore.class))
                .observationStore(mock(ObservationStore.class)).redactionPolicy(mock(RedactionPolicy.class)).build();

        MemoryAssembly.from(spec, degradations);

        final RuntimeDegradations recorded = degradations.build();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_INGEST)).isTrue();
        assertThat(recorded.describe()).contains("nothing takes conversation in");
    }

    @Test
    @DisplayName("every missing capability gets its own key, so a gap is named rather than inferred")
    void everyMissingCapabilityIsNamed() {
        // Observation store only: no snapshot, no dialectic, no queue.
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).observationStore(mock(ObservationStore.class))
                .redactionPolicy(mock(RedactionPolicy.class)).build();

        MemoryAssembly.from(spec, degradations);

        final RuntimeDegradations recorded = degradations.build();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_SNAPSHOT)).isTrue();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_CHAT)).isTrue();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_INGEST)).isTrue();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_SEARCH)).isFalse();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_OBSERVE)).isFalse();
        assertThat(recorded.describe()).contains("'default'");
    }

    @Test
    @DisplayName("an observation store with no redaction policy is recorded as a leak, not left silent")
    void unredactedObservationsAreDegraded() {
        // Both tools take the policy as a nullable argument and treat null as "no redaction", so whatever the
        // model is told to observe is persisted verbatim — including whatever secret reached the conversation.
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).observationStore(mock(ObservationStore.class))
                .build();

        MemoryAssembly.from(spec, degradations);

        assertThat(degradations.build().has(MemoryAssembly.CAPABILITY_REDACTION)).isTrue();
    }

    @Test
    @DisplayName("no redaction degradation when there is nothing to write")
    void noRedactionDegradationWithoutAWritePath() {
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).representationStore(mock(RepresentationStore.class))
                .build();

        MemoryAssembly.from(spec, degradations);

        assertThat(degradations.build().has(MemoryAssembly.CAPABILITY_REDACTION)).isFalse();
    }

    @Test
    @DisplayName("a redaction policy clears the redaction degradation and only that one")
    void redactionPolicyClearsItsDegradation() {
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).observationStore(mock(ObservationStore.class))
                .redactionPolicy(mock(RedactionPolicy.class)).build();

        MemoryAssembly.from(spec, degradations);

        final RuntimeDegradations recorded = degradations.build();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_REDACTION)).isFalse();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_INGEST)).isTrue();
    }

    @Test
    @DisplayName("a supplied backend is used as given, and its CHAT tier finally reaches the tool provider")
    void suppliedBackendRegistersChat() {
        // MemoryChatTool used to be registered only by the CLI's hand-written wiring, so no stack-assembled
        // deployment could reach it whatever its backend served. Registration by capability closes that.
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).peerMemory(new ChatOnlyBackend()).build();

        final MemoryAssembly assembly = MemoryAssembly.from(spec, degradations);

        assertThat(assembly.getToolProvider()).isPresent();
        assertThat(assembly.getContextProvider()).isEmpty();
        assertThat(degradations.build().has(MemoryAssembly.CAPABILITY_CHAT)).isFalse();
        assertThat(degradations.build().describe()).contains("'chat-only'");
    }

    @Test
    @DisplayName("a redaction policy wraps the backend, and the delegate stays reachable for teardown")
    void backendIsWrappedButTheDelegateIsReachable() {
        final PeerMemory supplied = new ChatOnlyBackend();
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).peerMemory(supplied)
                .redactionPolicy(mock(RedactionPolicy.class)).build();

        final MemoryAssembly assembly = MemoryAssembly.from(spec, degradations);

        assertThat(assembly.getPeerMemory()).get().isInstanceOf(RedactingPeerMemory.class);
        // Teardown must see through the wrapper: it owns nothing, so an instanceof check against it would leave a
        // backend's own resources open forever.
        assertThat(assembly.getPeerMemoryDelegate()).get().isSameAs(supplied);
    }

    @Test
    @DisplayName("without a redaction policy the backend is handed on unwrapped, and that is recorded")
    void noPolicyMeansNoWrapper() {
        final PeerMemory supplied = new FullBackend();
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).peerMemory(supplied).build();

        final MemoryAssembly assembly = MemoryAssembly.from(spec, degradations);

        assertThat(assembly.getPeerMemory()).get().isSameAs(supplied);
        assertThat(degradations.build().has(MemoryAssembly.CAPABILITY_REDACTION)).isTrue();
    }

    @Test
    @DisplayName("per-caller turns ingest off even when the backend can do it, and says why")
    void perCallerCannotIngest() {
        // The execution-end seam has no principal to resolve an observer from, exactly as the tool context
        // enricher has none. Without this line, "memory is configured and nothing accumulates" has no explanation.
        final MemorySpec spec = MemorySpec.perCaller(WORKSPACE).peerMemory(new FullBackend()).build();

        MemoryAssembly.from(spec, degradations);

        final RuntimeDegradations recorded = degradations.build();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_INGEST)).isTrue();
        assertThat(recorded.describe()).contains("no fixed observer to attribute it to");
    }

    @Test
    @DisplayName("the ingestor is reachable from the assembly, already wrapped for redaction")
    void ingestorIsExposed() {
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).peerMemory(new FullBackend())
                .redactionPolicy(mock(RedactionPolicy.class)).build();

        assertThat(MemoryAssembly.from(spec, degradations).getIngestor()).isPresent();
    }

    /** Serves CHAT and nothing else — the shape that used to have no way to register a tool. */
    private static final class ChatOnlyBackend implements PeerMemory {

        @Override
        public String backendId() {
            return "chat-only";
        }

        @Override
        public Optional<MemorySnapshotReader> snapshotReader() {
            return Optional.empty();
        }

        @Override
        public Optional<MemorySearcher> searcher() {
            return Optional.empty();
        }

        @Override
        public Optional<DialecticEngine> dialecticEngine() {
            return Optional.of(query -> DialecticResponse.builder().answer("stub").build());
        }

        @Override
        public Optional<ObservationRecorder> observationRecorder() {
            return Optional.empty();
        }

        @Override
        public Optional<MemoryIngestor> ingestor() {
            return Optional.empty();
        }
    }

    /** Serves every tier, so the assembly's decisions are the only thing under test. */
    private static final class FullBackend implements PeerMemory {

        @Override
        public String backendId() {
            return "full";
        }

        @Override
        public Optional<MemorySnapshotReader> snapshotReader() {
            return Optional.of(query -> Optional.empty());
        }

        @Override
        public Optional<MemorySearcher> searcher() {
            return Optional.of(new MemorySearcher() {
                @Override
                public List<MemoryHit> search(MemorySearchQuery query) {
                    return List.of();
                }

                @Override
                public boolean ranksByScore() {
                    return false;
                }
            });
        }

        @Override
        public Optional<DialecticEngine> dialecticEngine() {
            return Optional.of(new DialecticEngine() {
                @Override
                public DialecticResponse query(DialecticQuery query) {
                    return DialecticResponse.builder().answer("stub").build();
                }
            });
        }

        @Override
        public Optional<ObservationRecorder> observationRecorder() {
            return Optional.of(new ObservationRecorder() {
                @Override
                public Observation observe(ObservationDraft draft) {
                    throw new UnsupportedOperationException("not exercised here");
                }

                @Override
                public boolean storesConfidence() {
                    return true;
                }
            });
        }

        @Override
        public Optional<MemoryIngestor> ingestor() {
            return Optional.of(request -> MemoryIngestReceipt.builder().accepted(request.getMessages().size()).build());
        }
    }
}
