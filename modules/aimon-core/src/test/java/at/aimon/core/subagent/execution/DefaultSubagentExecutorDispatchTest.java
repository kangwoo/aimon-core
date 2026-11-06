package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.toolinvocation.ToolDispatchResults;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("DefaultSubagentExecutor.invokeTool — a fork's one way to a tool")
class DefaultSubagentExecutorDispatchTest {

    /** Records the origin and context of the one call it receives and answers with a fixed text. */
    private static final class RecordingDispatcher implements ToolCallDispatcher {

        private final AtomicReference<ToolCallOrigin> origin = new AtomicReference<>();
        private final AtomicReference<ToolContext> context = new AtomicReference<>();

        @Override
        public ToolDispatchResult dispatch(ToolUse toolUse, ToolCallOrigin callOrigin, ToolContext callContext) {
            origin.set(callOrigin);
            context.set(callContext);
            return ToolDispatchResults.of(ToolUseResult.success(toolUse.getId(), "ran"), List.of("reset"), List.of());
        }

        @Override
        public ToolCallDispatcher narrow(ToolRegistry registry, List<AllowedTool> allowedTools) {
            throw new UnsupportedOperationException("not narrowed in this test");
        }
    }

    @Test
    @DisplayName("a context with no dispatcher bound is refused per call, with the main loop's wording")
    void refusesWithoutABoundDispatcher() {
        final ToolUseResult result = DefaultSubagentExecutor.invokeTool(ToolContext.empty(),
                ToolUse.of("t1", "Bash", Map.of("command", "rm -rf /")), 3);

        assertThat(result.isError()).isTrue();
        assertThat(result.getToolUseId()).isEqualTo("t1");
        assertThat(result.getContent()).isEqualTo(ToolDispatchResults.NO_DISPATCHER_MESSAGE);
    }

    @Test
    @DisplayName("a bound dispatcher receives a direct call carrying the fork's iteration, and its notices are rendered")
    void dispatchesThroughTheBoundDispatcher() {
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.TOOL_CALL_DISPATCHER, dispatcher).build();

        final ToolUseResult result = DefaultSubagentExecutor.invokeTool(context, ToolUse.of("t1", "Read", Map.of()), 3);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("[environment] reset\nran");
        assertThat(dispatcher.origin.get()).isEqualTo(ToolCallOrigin.direct());
        assertThat(dispatcher.context.get().get(ToolContextKeys.ITERATION_COUNT)).contains(3);
    }
}
