package at.aimon.core.toolinvocation.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.IdempotencyBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("SideEffectApprovalGate — the prompt names the path and language under another tool's key")
class SideEffectApprovalGateViaTest {

    @Test
    @DisplayName("'Bash' — via ExecuteCode (Python), then the call (decision 11)")
    void namesPathAndLanguage() {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final SideEffectApprovalGate gate = new SideEffectApprovalGate(prompt -> {
            prompts.add(prompt);
            return Decision.ALLOW;
        });
        final ToolDescriptor descriptor = ToolDescriptor.builder()
                .definition(ToolDefinition.of("ExecuteCode", "run", Map.of("type", "object")))
                .sideEffectLevel(SideEffectLevel.MUTATING).destructiveBehavior(DestructiveBehavior.DESTRUCTIVE)
                .idempotencyBehavior(IdempotencyBehavior.NON_IDEMPOTENT)
                .concurrencyBehavior(ConcurrencyBehavior.SEQUENTIAL).interruptBehavior(InterruptBehavior.COOPERATIVE)
                .dataReach(DataReach.ENVIRONMENT).approvalKey("Bash").approvalVia("ExecuteCode (Python)").build();
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.SESSION_ID, SessionId.of("s")).build();

        assertThat(gate.denialReason(descriptor, ToolUse.of("t", "ExecuteCode", Map.of("code", "print(1)")),
                ToolCallOrigin.direct(), context)).isEmpty();

        assertThat(prompts).singleElement().satisfies(prompt -> assertThat(prompt)
                .contains("'Bash' — via ExecuteCode (Python), 'ExecuteCode(code=print(1))'"));
    }
}
