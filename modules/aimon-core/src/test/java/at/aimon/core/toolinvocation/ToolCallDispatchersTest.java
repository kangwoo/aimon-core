package at.aimon.core.toolinvocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

@DisplayName("ToolCallDispatchers.ungated — the explicit, named way past the gates")
class ToolCallDispatchersTest {

    private static Tool counting(String name, AtomicInteger runs) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.of(name, name, Map.of("type", "object"));
            }

            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                runs.incrementAndGet();
                return ToolResult.success("ran " + name);
            }
        };
    }

    @Test
    @DisplayName("runs the tool and hands the environment's notices on beside the text")
    void runsTheToolAndDrainsNotices() {
        final AtomicInteger runs = new AtomicInteger();
        final ToolRegistry registry = new DefaultToolRegistry();
        registry.register(counting("Read", runs));
        final Queue<String> notices = new ConcurrentLinkedQueue<>(List.of("re-created"));
        final ToolContext context = TestExecutionEnvironments.withoutStamps(
                TestExecutionEnvironments.builder().workingDirectory("/workspace").notices(notices).build());

        final ToolDispatchResult result = ToolCallDispatchers.ungated(new DefaultToolExecutionManager(), registry)
                .dispatch(ToolUse.of("t", "Read", Map.of()), ToolCallOrigin.direct(), context);

        assertThat(runs).hasValue(1);
        assertThat(result.isError()).isFalse();
        assertThat(result.getText()).isEqualTo("ran Read");
        assertThat(result.getNotices()).containsExactly("re-created");
        final ToolUseResult converted = ToolDispatchResults.toToolUseResult(result);
        assertThat(converted.getContent()).isEqualTo("[environment] re-created\nran Read");
    }

    @Test
    @DisplayName("an unknown tool is an error result, not a throw")
    void unknownToolIsAnErrorResult() {
        final ToolDispatchResult result = ToolCallDispatchers
                .ungated(new DefaultToolExecutionManager(), new DefaultToolRegistry())
                .dispatch(ToolUse.of("t", "Nope", Map.of()), ToolCallOrigin.direct(), ToolContext.empty());

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("narrows like the gated dispatcher: subset registry, intersected allow-list")
    void narrowsLikeTheGatedDispatcher() {
        final AtomicInteger runs = new AtomicInteger();
        final Tool read = counting("Read", runs);
        final Tool write = counting("Write", runs);
        final ToolRegistry registry = new DefaultToolRegistry();
        registry.register(read);
        registry.register(write);
        final ToolCallDispatcher ungated = ToolCallDispatchers.ungated(new DefaultToolExecutionManager(), registry);

        final ToolRegistry wider = new DefaultToolRegistry();
        wider.register(read);
        wider.register(counting("Bash", runs));
        assertThatIllegalArgumentException().isThrownBy(() -> ungated.narrow(wider, List.of()))
                .withMessageContaining("Bash");

        final ToolDispatchResult refused = ungated.narrow(registry, List.of(AllowedTool.parse("Read")))
                .dispatch(ToolUse.of("t", "Write", Map.of()), ToolCallOrigin.skill("s"), ToolContext.empty());
        assertThat(refused.isError()).isTrue();
        assertThat(runs).hasValue(0);
    }
}
