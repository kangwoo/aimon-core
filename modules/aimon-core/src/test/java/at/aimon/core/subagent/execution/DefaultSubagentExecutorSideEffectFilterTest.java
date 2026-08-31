package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
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

/**
 * Locks in the fork half of the {@link SideEffectLevel} pair: a fork's definition list is filtered by the ceiling of
 * the {@code ToolExecutionManager} it shares with the parent, so it is never offered a tool that same manager would go
 * on to refuse.
 *
 * <p>
 * The regression this guards is not a safety hole — the manager refused these calls all along — but a wasted
 * iteration: a fork shown a blocked tool picks it, reads the refusal, and tries again. It also guards the reason the
 * ceiling is read from the manager rather than configured on the executor: a second setting could be forgotten, which
 * is exactly how the filter came to be missing here in the first place.
 */
@DisplayName("DefaultSubagentExecutor side-effect definition filter")
class DefaultSubagentExecutorSideEffectFilterTest {

    /** Records the tool definitions offered on each {@code sendMessage} and always answers with plain text. */
    private static final class DefinitionCapturingLlmClient implements LlmClient {

        private final List<List<String>> offeredToolNames = new CopyOnWriteArrayList<>();

        private LlmResponse capture(List<ToolDefinition> tools) {
            offeredToolNames.add(tools.stream().map(ToolDefinition::getName).toList());
            return LlmResponse.text("done");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return capture(tools);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return capture(tools);
        }

        @Override
        public String getProviderName() {
            return "DefinitionCapturing";
        }

        List<String> firstOffer() {
            return offeredToolNames.get(0);
        }
    }

    private static Tool tool(String name, SideEffectLevel level) {
        return new AbstractTool(name, name + " description", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ran " + name);
            }

            @Override
            public SideEffectLevel getSideEffectLevel() {
                return level;
            }
        };
    }

    private DefinitionCapturingLlmClient runWithCeiling(DefaultToolExecutionManager manager, Tool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }

        final DefinitionCapturingLlmClient client = new DefinitionCapturingLlmClient();
        final DefaultSubagentExecutor executor = new DefaultSubagentExecutor(client, manager,
                new DefaultHookExecutionManager());

        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:side-effect-fork"))
                .subagent(Subagent.of("explorer", SubagentMetadata.builder().description("d").maxIterations(5).build(),
                        SubagentContent.of("you are explorer")))
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .parentCancellationSignal(NoopCancellationSignal.INSTANCE).build();

        final SubagentExecutionResult result = executor.execute(context,
                SubagentExecutionRequest.builder().taskId("task-1").goal("go").build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(client.offeredToolNames).as("the LLM was called at least once").isNotEmpty();
        return client;
    }

    @Test
    @DisplayName("a READ_ONLY manager ceiling withholds MUTATING tool definitions from the fork's LLM")
    void readOnlyCeilingWithholdsMutatingTools() {
        final DefinitionCapturingLlmClient client = runWithCeiling(
                new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY), tool("Reader", SideEffectLevel.READ_ONLY),
                tool("Writer", SideEffectLevel.MUTATING));

        assertThat(client.firstOffer()).contains("Reader").doesNotContain("Writer");
    }

    @Test
    @DisplayName("the default ceiling withholds nothing, so undeclared tools stay visible to the fork")
    void defaultCeilingOffersEverything() {
        final Tool undeclared = new AbstractTool("Legacy", "declares nothing", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ran Legacy");
            }
        };

        final DefinitionCapturingLlmClient client = runWithCeiling(new DefaultToolExecutionManager(),
                tool("Reader", SideEffectLevel.READ_ONLY), undeclared);

        assertThat(client.firstOffer()).contains("Reader", "Legacy");
    }
}
