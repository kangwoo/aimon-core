package at.aimon.core.tools.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentLaunchContext;
import at.aimon.core.subagent.exception.SubagentNotFoundException;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.tools.ToolContextKeys;

/**
 * EE-44: a subagent definition marked hidden is not offered to the model and cannot be launched through {@code Task},
 * while the registry still resolves it for the callers that look it up by name.
 */
@DisplayName("TaskTool: a hidden subagent is neither listed nor launched")
class TaskToolHiddenSubagentTest {

    private static final String HIDDEN = "workflow-judge";

    private final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
    private final SubagentExecutionManager executionManager = mock(SubagentExecutionManager.class);
    private final TaskTool tool = new TaskTool(mock(LlmModel.class), registry, mock(ToolRegistry.class),
            mock(HookRegistry.class), executionManager);

    TaskToolHiddenSubagentTest() {
        registry.register(
                Subagent.builder().name("Explore").description("Find files").systemPrompt("You explore").build());
        registry.register(Subagent.builder().name(HIDDEN).description("Placement only: gives judge steps their slot")
                .systemPrompt("(never run through Task)").attributes(Map.of("sandbox.slot", "isolated")).hidden(true)
                .build());
    }

    @Test
    @DisplayName("the tool description lists the visible subagent and says nothing of the hidden one")
    void theDescriptionOmitsAHiddenSubagent() {
        final String description = tool.getDefinition().getDescription();

        assertThat(description).contains("- Explore: Find files").doesNotContain(HIDDEN, "Placement only");
    }

    @Test
    @DisplayName("with only hidden definitions registered, the description says none are available")
    void onlyHiddenDefinitionsMeansNoneAvailable() {
        final InMemorySubagentRegistry onlyHidden = new InMemorySubagentRegistry();
        onlyHidden.register(Subagent.builder().name(HIDDEN).systemPrompt("x").hidden(true).build());
        final TaskTool onlyHiddenTool = new TaskTool(mock(LlmModel.class), onlyHidden, mock(ToolRegistry.class),
                mock(HookRegistry.class), executionManager);

        assertThat(onlyHiddenTool.getDefinition().getDescription()).contains("No subagents currently available")
                .doesNotContain(HIDDEN);
    }

    @Test
    @DisplayName("a call that names the hidden subagent is refused before anything is launched")
    void aCallNamingAHiddenSubagentIsRefused() {
        final ToolResult result = tool.execute(input(HIDDEN, false), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains(HIDDEN, "not available", "Available subagents: Explore");
        verifyNoInteractions(executionManager);
    }

    @Test
    @DisplayName("a background call that names the hidden subagent is refused too")
    void aBackgroundCallNamingAHiddenSubagentIsRefused() {
        final ToolResult result = tool.execute(input(HIDDEN, true), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).doesNotContain("Background task launched");
        verifyNoInteractions(executionManager);
    }

    @Test
    @DisplayName("the names offered after an unknown subagent leave the hidden one out")
    void theNotFoundListOmitsAHiddenSubagent() {
        when(executionManager.execute(any(SubagentLaunchContext.class), anyString(), eq("ghost"), anyString(),
                anyString())).thenThrow(new SubagentNotFoundException("ghost"));

        final ToolResult result = tool.execute(input("ghost", false), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Available subagents: Explore").doesNotContain(HIDDEN);
    }

    @Test
    @DisplayName("a visible subagent is launched as before")
    void aVisibleSubagentStillRuns() {
        final Instant now = Instant.now();
        when(executionManager.execute(any(SubagentLaunchContext.class), anyString(), eq("Explore"), anyString(),
                anyString()))
                .thenReturn(SubagentExecutionResult.success("done",
                        SessionSnapshot.of(SessionId.generate(), "sys", List.of()), ExecutionMetadata.builder()
                                .iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build()));

        final ToolResult result = tool.execute(input("Explore", false), context());

        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
    }

    @Test
    @DisplayName("the registry still resolves the hidden definition by name")
    void theRegistryStillResolvesIt() {
        assertThat(registry.getSubagent(HIDDEN)).hasValueSatisfying(subagent -> {
            assertThat(subagent.getMetadata().isHidden()).isTrue();
            assertThat(subagent.getMetadata().getAttributes()).containsEntry("sandbox.slot", "isolated");
        });
        assertThat(registry.getAllSubagents()).extracting(Subagent::getName).contains(HIDDEN);
    }

    private static ToolInput input(String subagentName, boolean background) {
        return ToolInput.of(Map.of("subagent_name", subagentName, "prompt", "go", "description", "d",
                "run_in_background", background));
    }

    private static ToolContext context() {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test")).build();
    }
}
