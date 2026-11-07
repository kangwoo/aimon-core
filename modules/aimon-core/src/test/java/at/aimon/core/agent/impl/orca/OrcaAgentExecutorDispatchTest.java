package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.internal.ToolDispatchRefusals;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.toolinvocation.ToolDispatchResults;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("OrcaAgentExecutor.dispatchSingleTool — the main loop's one way to a tool")
class OrcaAgentExecutorDispatchTest {

    /** Records the origin of the one call it receives and answers with a fixed text. */
    private static final class RecordingDispatcher implements ToolCallDispatcher {

        private final AtomicReference<ToolCallOrigin> origin = new AtomicReference<>();

        @Override
        public ToolDispatchResult dispatch(ToolUse toolUse, ToolCallOrigin callOrigin, ToolContext context) {
            origin.set(callOrigin);
            return ToolDispatchResults.of(ToolUseResult.success(toolUse.getId(), "ran"), List.of("reset"), List.of());
        }

        @Override
        public ToolCallDispatcher narrow(ToolRegistry registry, List<AllowedTool> allowedTools,
                ProgrammaticToolSettings programmatic) {
            throw new UnsupportedOperationException("not narrowed in this test");
        }
    }

    @Test
    @DisplayName("a context with no dispatcher bound is refused per call, not run around the gates")
    void refusesWithoutABoundDispatcher() {
        final ToolUseResult result = OrcaAgentExecutor.dispatchSingleTool(ToolContext.empty(),
                ToolUse.of("t1", "Bash", Map.of("command", "rm -rf /")));

        assertThat(result.isError()).isTrue();
        assertThat(result.getToolUseId()).isEqualTo("t1");
        assertThat(result.getContent()).isEqualTo(ToolDispatchRefusals.NO_DISPATCHER);
    }

    @Test
    @DisplayName("a bound dispatcher receives the call as a direct one, and its notices are rendered in front")
    void dispatchesThroughTheBoundDispatcher() {
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.TOOL_CALL_DISPATCHER, dispatcher).build();

        final ToolUseResult result = OrcaAgentExecutor.dispatchSingleTool(context, ToolUse.of("t1", "Read", Map.of()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("[environment] reset\nran");
        assertThat(dispatcher.origin.get()).isEqualTo(ToolCallOrigin.direct());
    }
}
