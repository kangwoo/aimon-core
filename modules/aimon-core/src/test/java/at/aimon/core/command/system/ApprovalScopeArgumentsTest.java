package at.aimon.core.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import at.aimon.core.skill.policy.approval.ApprovalScope;

/** Unit tests for {@link ApprovalScopeArguments}, the shared {@code --agent} parse behind /revoke, /approve, /deny. */
class ApprovalScopeArgumentsTest {

    @Test
    void defaultsToConversationScope() {
        assertThat(ApprovalScopeArguments.scopeOf(null)).isEqualTo(ApprovalScope.SESSION);
        assertThat(ApprovalScopeArguments.scopeOf("")).isEqualTo(ApprovalScope.SESSION);
        assertThat(ApprovalScopeArguments.scopeOf("   ")).isEqualTo(ApprovalScope.SESSION);
        assertThat(ApprovalScopeArguments.scopeOf("turn-1")).isEqualTo(ApprovalScope.SESSION);
    }

    @Test
    void widensOnlyWhenTheFlagIsSpelledOut() {
        assertThat(ApprovalScopeArguments.scopeOf("turn-1 --agent")).isEqualTo(ApprovalScope.AGENT);
        assertThat(ApprovalScopeArguments.scopeOf("--agent turn-1")).isEqualTo(ApprovalScope.AGENT);
        assertThat(ApprovalScopeArguments.scopeOf("--AGENT")).isEqualTo(ApprovalScope.AGENT);
    }

    @Test
    void nearMissesDoNotWiden() {
        // A partial or misspelled flag must fail closed to the narrow scope rather than silently going agent-wide.
        assertThat(ApprovalScopeArguments.scopeOf("--agents")).isEqualTo(ApprovalScope.SESSION);
        assertThat(ApprovalScopeArguments.scopeOf("-agent")).isEqualTo(ApprovalScope.SESSION);
        assertThat(ApprovalScopeArguments.scopeOf("agent")).isEqualTo(ApprovalScope.SESSION);
        assertThat(ApprovalScopeArguments.scopeOf("--agent=true")).isEqualTo(ApprovalScope.SESSION);
    }

    @Test
    void firstOperandSkipsFlagsAndReturnsTheId() {
        assertThat(ApprovalScopeArguments.firstOperand("turn-1")).isEqualTo("turn-1");
        assertThat(ApprovalScopeArguments.firstOperand("turn-1 --agent")).isEqualTo("turn-1");
        assertThat(ApprovalScopeArguments.firstOperand("--agent turn-1")).isEqualTo("turn-1");
        assertThat(ApprovalScopeArguments.firstOperand("  turn-1   extra  ")).isEqualTo("turn-1");
    }

    @Test
    void firstOperandIsNullWhenNoOperandIsPresent() {
        assertThat(ApprovalScopeArguments.firstOperand(null)).isNull();
        assertThat(ApprovalScopeArguments.firstOperand("")).isNull();
        assertThat(ApprovalScopeArguments.firstOperand("   ")).isNull();
        assertThat(ApprovalScopeArguments.firstOperand("--agent")).isNull();
    }
}
