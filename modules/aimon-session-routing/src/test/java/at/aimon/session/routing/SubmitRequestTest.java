package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * {@link SubmitRequest} refuses an {@code agentRef} that {@link AgentRuntimeId#fromName(String)} would refuse, at
 * {@code build()} — before the router can claim, and so permanently bind, a session to it (#260).
 */
@DisplayName("SubmitRequest")
class SubmitRequestTest {

    private static SubmitRequest.Builder request(String agentRef) {
        return SubmitRequest.builder().sessionId(SessionId.of("c-req-1")).agentRef(agentRef).userInput("hi")
                .initiator(Principal.system());
    }

    @Test
    @DisplayName("a null agentRef is still a NullPointerException")
    void nullAgentRef() {
        assertThatNullPointerException().isThrownBy(() -> request(null).build()).withMessageContaining("agentRef");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    @DisplayName("a blank agentRef is refused")
    void blankAgentRef(String agentRef) {
        assertThatIllegalArgumentException().isThrownBy(() -> request(agentRef).build())
                .withMessageContaining("agentRef must not be blank");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ops:prod", ":", "agent:alpha", "alpha:"})
    @DisplayName("an agentRef containing ':' is refused")
    void colonInAgentRef(String agentRef) {
        assertThatIllegalArgumentException().isThrownBy(() -> request(agentRef).build())
                .withMessageContaining("must not contain ':'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"alpha", "ops-prod", "ops.prod", "a b"})
    @DisplayName("every agentRef the request accepts is one AgentRuntimeId.fromName accepts")
    void acceptedAgentRefsAreRuntimeNames(String agentRef) {
        final SubmitRequest built = request(agentRef).build();

        assertThat(built.getAgentRef()).isEqualTo(agentRef);
        assertThat(AgentRuntimeId.fromName(built.getAgentRef()).value()).isEqualTo("agent:" + agentRef);
    }
}
