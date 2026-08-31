package at.aimon.bootstrap.assemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.bootstrap.RuntimeDegradations;
import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.redaction.RedactionPolicy;

/**
 * Which of the three memory components a spec produces, and what it says about the ones it does not.
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
    @DisplayName("the missing write path is recorded for every memory deployment, fixed peer included")
    void writePathIsAlwaysDegraded() {
        // The stack has no deriver, no derivation queue and no dreamer. Fully wired on the read side is still
        // read-only, and the symptom of not knowing that is an empty memory part nobody can explain.
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).representationStore(mock(RepresentationStore.class))
                .observationStore(mock(ObservationStore.class)).redactionPolicy(mock(RedactionPolicy.class)).build();

        MemoryAssembly.from(spec, degradations);

        final RuntimeDegradations recorded = degradations.build();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_WRITE_PATH)).isTrue();
        assertThat(recorded.describe()).contains("deriver");
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
    @DisplayName("no redaction degradation when there is no observation store to redact")
    void noRedactionDegradationWithoutObservations() {
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
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_WRITE_PATH)).isTrue();
    }
}
