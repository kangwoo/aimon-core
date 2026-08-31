package at.aimon.core.agent.impl.orca;

import static at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.LlmResponse;

/**
 * Locks in that {@link OrcaAgentExecutorFactory#withMaxSideEffectLevel(SideEffectLevel)} configures
 * <b>both</b> halves of the enforcement pair, not only the definition filter.
 *
 * <p>
 * The scenario is exactly the one the second half exists for: the model asks for a tool it was never offered — as it
 * can, from memory of an earlier turn or another agent. The filter cannot help there because the definition was
 * already withheld, so if the factory failed to pass the ceiling down to the {@code DefaultToolExecutionManager} the
 * call would execute.
 */
@DisplayName("OrcaAgentExecutorFactory side-effect ceiling wiring")
class OrcaAgentExecutorFactorySideEffectCeilingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a ceiling set on the factory also refuses an unoffered tool the model names anyway")
    void ceilingReachesTheExecutionManager() {
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);
        final AtomicBoolean executed = new AtomicBoolean();

        final Tool writer = new AbstractTool("Writer", "mutates things", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                executed.set(true);
                return ToolResult.success("wrote");
            }

            @Override
            public SideEffectLevel getSideEffectLevel() {
                return SideEffectLevel.MUTATING;
            }
        };

        // The model calls Writer even though a READ_ONLY ceiling means its definition was never offered.
        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient();
        client.script("conv-ceiling", toolCall("tu-1", "Writer")).fallback("conv-ceiling", LlmResponse.text("done"));

        final OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
                .withMaxSideEffectLevel(SideEffectLevel.READ_ONLY)
                .create(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()));
        final OrcaAgentRuntime context = support.newContext("agent:factory-ceiling", writer);

        final OrcaAgentExecutionResult result = executor.execute(context,
                OrcaAgentExecutorTestSupport.request("conv-ceiling"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(executed).as("the execution gate must refuse the tool the filter had already hidden").isFalse();
    }
}
