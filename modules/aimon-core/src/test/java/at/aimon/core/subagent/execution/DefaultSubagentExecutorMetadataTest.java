package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;

@DisplayName("DefaultSubagentExecutor LlmCallMetadata propagation")
class DefaultSubagentExecutorMetadataTest {

    private CapturingLlmClient llmClient;
    private DefaultSubagentExecutor executor;

    @BeforeEach
    void setUp() {
        llmClient = new CapturingLlmClient();
        executor = new DefaultSubagentExecutor(llmClient, new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager());
    }

    @Test
    @DisplayName("parent metadata 의 traceId/principal/tags 는 상속, component/feature 는 subagent 값으로 override")
    void inheritsParentTraceAndOverridesComponent() {
        final SubagentExecutionContext context = createContext("code-reviewer");
        final LlmCallMetadata parent = LlmCallMetadata.builder().component("orca-agent").feature("react-loop")
                .traceId("trace-xyz").tag("tenant", "acme").build();
        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("review")
                .llmCallMetadata(parent).build();

        executor.execute(context, request);

        assertThat(llmClient.captured).isNotEmpty();
        final LlmCallMetadata md = llmClient.captured.get(0);
        // subagent overrides
        assertThat(md.getComponent()).contains("code-reviewer");
        assertThat(md.getFeature()).contains("subagent");
        // parent component preserved as parentComponent for hierarchical attribution
        assertThat(md.getParentComponent()).contains("orca-agent");
        // inherited from parent
        assertThat(md.getTraceId()).contains("trace-xyz");
        assertThat(md.getTags()).containsEntry("tenant", "acme");
    }

    @Test
    @DisplayName("parent 가 비어 있어도 subagent name + feature=subagent 가 자동 채워진다")
    void autoDerivesWhenParentEmpty() {
        final SubagentExecutionContext context = createContext("explorer");

        executor.execute(context, SubagentExecutionRequest.builder().taskId("task-2").goal("explore").build());

        assertThat(llmClient.captured).isNotEmpty();
        final LlmCallMetadata md = llmClient.captured.get(0);
        assertThat(md.getComponent()).contains("explorer");
        assertThat(md.getFeature()).contains("subagent");
        assertThat(md.getParentComponent()).isEmpty();
    }

    @Test
    @DisplayName("parent 의 component 가 parentComponent 로 덮어써진다 — 조부모(grand-parent) chain 은 보존되지 않는다")
    void parentComponentOverridesAncestorChain() {
        final SubagentExecutionContext context = createContext("inner-subagent");
        // Simulate a nested call: outer subagent invoking inner subagent.
        // Outer subagent's metadata already has parentComponent="orca-agent" (set by its own spawner).
        final LlmCallMetadata parent = LlmCallMetadata.builder().component("outer-subagent")
                .parentComponent("orca-agent").feature("subagent").build();
        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-3").goal("nest")
                .llmCallMetadata(parent).build();

        executor.execute(context, request);

        assertThat(llmClient.captured).isNotEmpty();
        final LlmCallMetadata md = llmClient.captured.get(0);
        assertThat(md.getComponent()).contains("inner-subagent");
        // Immediate parent only — grand-parent "orca-agent" is intentionally not preserved.
        assertThat(md.getParentComponent()).contains("outer-subagent");
    }

    private SubagentExecutionContext createContext(String subagentName) {
        final Subagent subagent = Subagent.of(subagentName,
                SubagentMetadata.builder().description("d").maxIterations(2).build(),
                SubagentContent.of("you are " + subagentName));
        return SubagentExecutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent)
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault()).build();
    }

    private static final class CapturingLlmClient implements LlmClient {
        private final List<LlmCallMetadata> captured = new ArrayList<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            captured.add(metadata);
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "Capturing";
        }

    }
}
