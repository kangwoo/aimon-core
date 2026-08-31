package at.aimon.core.hook.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.exception.AgentException;

class HookExceptionsTest {

    @Test
    void hookExceptionWithMessage() {
        HookException ex = new HookException("boom");
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isNull();
        assertThat(ex).isInstanceOf(AgentException.class);
    }

    @Test
    void hookExceptionWithMessageAndCause() {
        Throwable cause = new IllegalArgumentException("inner");
        HookException ex = new HookException("boom", cause);
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void executionBlockedByHookException_buildsMessageFromComponents() {
        ExecutionBlockedByHookException ex = new ExecutionBlockedByHookException(InvokerType.MAIN_AGENT, "agent-name",
                "OnStart", List.of("reason-1", "reason-2"));

        assertThat(ex).isInstanceOf(HookException.class);
        assertThat(ex.getInvokerType()).isEqualTo(InvokerType.MAIN_AGENT);
        assertThat(ex.getInvokerName()).isEqualTo("agent-name");
        assertThat(ex.getHookType()).isEqualTo("OnStart");
        assertThat(ex.getBlockReasons()).containsExactly("reason-1", "reason-2");
        assertThat(ex.getMessage()).contains("OnStart").contains("MAIN_AGENT").contains("agent-name")
                .contains("reason-1; reason-2");
    }

    @Test
    void executionBlockedByHookException_blockReasonsAreImmutable() {
        java.util.List<String> mutable = new java.util.ArrayList<>();
        mutable.add("first");
        ExecutionBlockedByHookException ex = new ExecutionBlockedByHookException(InvokerType.SUBAGENT, "sub", "PreTool",
                mutable);

        // Mutating the source list does not affect the exception's snapshot
        mutable.add("second");
        assertThat(ex.getBlockReasons()).containsExactly("first");

        assertThatThrownBy(() -> ex.getBlockReasons().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void executionBlockedByHookException_customMessageVariant() {
        ExecutionBlockedByHookException ex = new ExecutionBlockedByHookException("custom message",
                InvokerType.MAIN_AGENT, "agent", "OnStart", List.of("r"));

        assertThat(ex.getMessage()).isEqualTo("custom message");
        assertThat(ex.getInvokerType()).isEqualTo(InvokerType.MAIN_AGENT);
        assertThat(ex.getBlockReasons()).containsExactly("r");
    }

    @Test
    void executionBlockedByHookException_rejectsNullArguments() {
        assertThatThrownBy(() -> new ExecutionBlockedByHookException(null, "n", "h", List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExecutionBlockedByHookException(InvokerType.MAIN_AGENT, null, "h", List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExecutionBlockedByHookException(InvokerType.MAIN_AGENT, "n", null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExecutionBlockedByHookException(InvokerType.MAIN_AGENT, "n", "h", null))
                .isInstanceOf(NullPointerException.class);
    }
}
