package at.aimon.core.agent.interrupt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.InterruptAccess;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;

@DisplayName("InterruptAccess Tests")
class InterruptAccessTest {

    @Test
    @DisplayName("signalOf returns the noop singleton when the context carries no signal")
    void signalOfFallsBackToNoop() {
        CancellationSignal resolved = InterruptAccess.signalOf(ToolContext.empty());

        assertThat(resolved).isSameAs(NoopCancellationSignal.INSTANCE);
    }

    @Test
    @DisplayName("signalOf returns the signal stored in the context")
    void signalOfReturnsStoredSignal() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();
        ToolContext context = ToolContext.builder().put(InterruptToolKeys.CANCELLATION_SIGNAL, signal).build();

        CancellationSignal resolved = InterruptAccess.signalOf(context);

        assertThat(resolved).isSameAs(signal);
    }

    @Test
    @DisplayName("signalOf rejects null context")
    void signalOfRejectsNullContext() {
        assertThatThrownBy(() -> InterruptAccess.signalOf(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("registrarOf returns empty when no registrar is present")
    void registrarOfIsEmptyWhenAbsent() {
        assertThat(InterruptAccess.registrarOf(ToolContext.empty())).isEmpty();
    }

    @Test
    @DisplayName("registrarOf returns the registrar stored in the context")
    void registrarOfReturnsStoredRegistrar() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();
        ToolContext context = ToolContext.builder().put(InterruptToolKeys.TERMINATOR_REGISTRAR, registrar).build();

        assertThat(InterruptAccess.registrarOf(context)).containsSame(registrar);
    }

    @Test
    @DisplayName("registrarOf rejects null context")
    void registrarOfRejectsNullContext() {
        assertThatThrownBy(() -> InterruptAccess.registrarOf(null)).isInstanceOf(NullPointerException.class);
    }
}
