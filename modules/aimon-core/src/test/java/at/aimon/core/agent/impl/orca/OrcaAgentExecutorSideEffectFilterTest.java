package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;

/**
 * Locks in the executor half of the {@link SideEffectLevel} pair: the definitions the executor hands the LLM are
 * filtered by {@code maxSideEffectLevel}, so a restricted turn never even shows the model a tool the
 * {@code DefaultToolExecutionManager} would go on to refuse.
 *
 * <p>
 * This is the <em>first</em> of two lines of defence and the only one that shapes the model's choices; the execution
 * gate (covered by {@code DefaultToolExecutionManagerSideEffectGateTest}) still matters because a model can name a
 * tool from memory that it was never shown in this turn.
 */
@DisplayName("OrcaAgentExecutor side-effect definition filter")
class OrcaAgentExecutorSideEffectFilterTest {

    @TempDir
    Path tempDir;

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

    private DefinitionCapturingLlmClient runWithCeiling(SideEffectLevel ceiling, Tool... tools) {
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);
        final DefinitionCapturingLlmClient client = new DefinitionCapturingLlmClient();
        final OrcaAgentExecutor executor = support.newExecutor(client);
        if (ceiling != null) {
            executor.maxSideEffectLevel = ceiling;
        }
        final OrcaAgentRuntime context = support.newContext("agent:side-effect-filter", tools);

        final OrcaAgentExecutionResult result = executor.execute(context,
                OrcaAgentExecutorTestSupport.request("conv-filter"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(client.offeredToolNames).as("the LLM was called at least once").isNotEmpty();
        return client;
    }

    @Test
    @DisplayName("a READ_ONLY ceiling withholds MUTATING tool definitions from the LLM")
    void readOnlyCeilingWithholdsMutatingTools() {
        final DefinitionCapturingLlmClient client = runWithCeiling(SideEffectLevel.READ_ONLY,
                tool("Reader", SideEffectLevel.READ_ONLY), tool("Writer", SideEffectLevel.MUTATING));

        assertThat(client.firstOffer()).contains("Reader").doesNotContain("Writer");
    }

    @Test
    @DisplayName("the default ceiling withholds nothing, so undeclared tools stay visible")
    void defaultCeilingOffersEverything() {
        final Tool undeclared = new AbstractTool("Legacy", "declares nothing", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ran Legacy");
            }
        };

        final DefinitionCapturingLlmClient client = runWithCeiling(null, tool("Reader", SideEffectLevel.READ_ONLY),
                undeclared);

        assertThat(client.firstOffer()).contains("Reader", "Legacy");
    }

    /**
     * MUTATING by its getters, READ_ONLY with a dynamic description in an offering context. Records what it saw: the
     * contexts it was described in, and the offer context bound into the context it ran with.
     */
    private static final class ContextualTool extends AbstractTool {

        private final List<ToolOfferContext> describedIn = new CopyOnWriteArrayList<>();
        private final List<ToolOfferContext> ranUnder = new CopyOnWriteArrayList<>();

        ContextualTool() {
            super("Contextual", "static description", Map.of("type", "object"));
        }

        @Override
        public ToolDescriptor describe(ToolOfferContext context) {
            describedIn.add(context);
            if (!context.isOffering()) {
                return ToolDescriptor.of(this);
            }
            return ToolDescriptor.from(this).sideEffectLevel(SideEffectLevel.READ_ONLY)
                    .definition(ToolDefinition.of("Contextual", "dynamic description", Map.of("type", "object")))
                    .build();
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            ranUnder.add(ToolOfferContext.of(context));
            return ToolResult.success("ran");
        }
    }

    @Test
    @DisplayName("the offer, the ceiling's refusal and the tool all see the iteration's offer context (decision 9)")
    void offerAndRefusalReadTheSameDescriptor() {
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);
        final List<List<ToolDefinition>> offers = new CopyOnWriteArrayList<>();
        final LlmClient client = new LlmClient() {
            private LlmResponse answer(List<ToolDefinition> tools) {
                offers.add(tools);
                return offers.size() == 1
                        ? LlmResponse.tools(List.of(ToolUse.of("call-1", "Contextual", Map.of())))
                        : LlmResponse.text("done");
            }

            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                    LlmModel modelConfig) {
                return answer(tools);
            }

            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                    LlmModel modelConfig, LlmCallMetadata metadata) {
                return answer(tools);
            }

            @Override
            public String getProviderName() {
                return "Scripted";
            }
        };
        final OrcaAgentExecutor executor = support.newExecutor(client);
        executor.maxSideEffectLevel = SideEffectLevel.READ_ONLY;
        final ContextualTool tool = new ContextualTool();

        final OrcaAgentExecutionResult result = executor.execute(support.newContext("agent:offer-context", tool),
                OrcaAgentExecutorTestSupport.request("conv-offer"));

        assertThat(result.isSuccess()).isTrue();
        // Offered under READ_ONLY with the description describe() gave, not the static one...
        assertThat(offers.get(0)).extracting(ToolDefinition::getDescription).containsExactly("dynamic description");
        // ...and run under the same ceiling, because the refusal read the bound offer context too.
        assertThat(tool.ranUnder).hasSize(1);
        assertThat(tool.ranUnder.get(0).isOffering()).isTrue();
        assertThat(tool.ranUnder.get(0).getCeiling()).isEqualTo(SideEffectLevel.READ_ONLY);
        assertThat(tool.describedIn).contains(tool.ranUnder.get(0));
    }
}
