package at.aimon.bootstrap.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.MemoryInjectionMode;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.redaction.RedactionPolicy;

/**
 * What a {@link MemorySpec} refuses to be built as, and what it defaults to.
 *
 * <p>
 * Both rejections here are of specs that would <b>succeed</b> — no exception, no missing bean, memory reported as
 * configured — and then do nothing. That is the failure mode the whole spec is shaped around: memory has no
 * output the operator can check at startup, so a mis-wired memory is indistinguishable from a peer who happens to
 * have nothing stored yet.
 */
class MemorySpecTest {

    private static final Workspace WORKSPACE = Workspace.builder().id("ws-1").build();
    private static final Principal PEER = Principal.user("kangwoo");

    @Test
    @DisplayName("a fixed-peer spec carries the peer and reports it is not per-caller")
    void fixedPeerSpecCarriesThePeer() {
        final RepresentationStore representations = mock(RepresentationStore.class);
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).representationStore(representations).build();

        assertThat(spec.getWorkspace()).isEqualTo(WORKSPACE);
        assertThat(spec.getFixedPeer()).contains(PEER);
        assertThat(spec.isPerCaller()).isFalse();
        assertThat(spec.getRepresentationStore()).contains(representations);
        assertThat(spec.getObservationStore()).isEmpty();
    }

    @Test
    @DisplayName("a per-caller spec has no peer and says so both ways")
    void perCallerSpecHasNoPeer() {
        final MemorySpec spec = MemorySpec.perCaller(WORKSPACE).representationStore(mock(RepresentationStore.class))
                .build();

        assertThat(spec.getFixedPeer()).isEmpty();
        assertThat(spec.isPerCaller()).isTrue();
    }

    @Test
    @DisplayName("injection defaults to SUMMARY_ONLY, and the rest to absent")
    void defaultsAreSummaryOnlyAndNothingElse() {
        // The cheap mode is the default because the expensive one is per turn, forever, for every session.
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).representationStore(mock(RepresentationStore.class))
                .build();

        assertThat(spec.getInjectionMode()).isEqualTo(MemoryInjectionMode.SUMMARY_ONLY);
        assertThat(spec.getMaxTokens()).isZero();
        assertThat(spec.getRedactionPolicy()).isEmpty();
    }

    @Test
    @DisplayName("every set value is handed on unchanged")
    void setValuesAreHandedOn() {
        final ObservationStore observations = mock(ObservationStore.class);
        final RedactionPolicy redaction = mock(RedactionPolicy.class);
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).observationStore(observations)
                .injectionMode(MemoryInjectionMode.FULL).maxTokens(512).redactionPolicy(redaction).build();

        assertThat(spec.getObservationStore()).contains(observations);
        assertThat(spec.getInjectionMode()).isEqualTo(MemoryInjectionMode.FULL);
        assertThat(spec.getMaxTokens()).isEqualTo(512);
        assertThat(spec.getRedactionPolicy()).contains(redaction);
    }

    @Test
    @DisplayName("a spec with no store at all is refused")
    void noStoreIsRefused() {
        // Nothing downstream would fail: the assembly produces no provider, no enricher and no tools, the stack
        // builds, and memory is listed in configuration as on.
        assertThatThrownBy(() -> MemorySpec.forPeer(WORKSPACE, PEER).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one store");
    }

    @Test
    @DisplayName("per-caller with only an observation store is refused, and the message says what to use instead")
    void perCallerWithoutRepresentationsIsRefused() {
        // Per-caller mode cannot register the memory tools — they read their observer from the tool context and
        // the enricher that puts it there binds one fixed peer — so an observation store is the one thing this
        // mode has no use for. Left alone it wires nothing whatsoever.
        assertThatThrownBy(() -> MemorySpec.perCaller(WORKSPACE).observationStore(mock(ObservationStore.class)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("representation store")
                .hasMessageContaining("MemorySpec.forPeer");
    }

    @Test
    @DisplayName("a fixed peer with only an observation store is fine — that is the tools-only deployment")
    void fixedPeerWithOnlyObservationsIsAccepted() {
        final MemorySpec spec = MemorySpec.forPeer(WORKSPACE, PEER).observationStore(mock(ObservationStore.class))
                .build();

        assertThat(spec.getRepresentationStore()).isEmpty();
        assertThat(spec.getObservationStore()).isPresent();
    }

    @Test
    @DisplayName("a negative token cap is refused; zero means no cap")
    void negativeMaxTokensIsRefused() {
        assertThatThrownBy(() -> MemorySpec.forPeer(WORKSPACE, PEER)
                .representationStore(mock(RepresentationStore.class)).maxTokens(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxTokens");
    }

    @Test
    @DisplayName("forPeer rejects a null peer by naming the other entry point")
    void forPeerRejectsNullPeer() {
        // The alternative is a fixed-peer spec that silently behaves as per-caller.
        assertThatThrownBy(() -> MemorySpec.forPeer(WORKSPACE, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MemorySpec.perCaller");
    }
}
