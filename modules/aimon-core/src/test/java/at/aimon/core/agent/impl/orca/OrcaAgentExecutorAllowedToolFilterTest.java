package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

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
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.tools.search.ToolSearchTool;

/**
 * Locks in the main agent's allow-list, the surface a subagent, a skill and a command each already had and the agent
 * itself did not: the agent's {@code allowed-tools} narrows the definitions its turn offers the LLM, and bounds the
 * dispatch that follows.
 *
 * <p>
 * The sibling of {@link OrcaAgentExecutorSideEffectFilterTest} on the other axis, and the main-agent counterpart of
 * {@code DefaultSubagentExecutorAllowedToolFilterTest}. The properties worth holding still are the same three:
 *
 * <ul>
 * <li>A name absent from the allow-list is withheld from the offer — the iteration this saves the model.
 * <li>A <b>pattern</b> entry still offers its tool: a tool list cannot say which arguments are allowed.
 * <li>The <b>registry</b> is untouched, so naming a withheld tool anyway reads as a permission denial rather than
 * {@code "Unknown tool: …"}. That distinction is what tells the model it asked for something forbidden rather than
 * something misspelled.
 * </ul>
 *
 * <p>
 * The fourth is this class's own: an agent that declares nothing is offered everything, which is the whole of the
 * backward compatibility claim.
 */
@DisplayName("OrcaAgentExecutor agent allow-list filter")
class OrcaAgentExecutorAllowedToolFilterTest {

    @TempDir
    Path tempDir;

    /** Replays queued responses (falling back to plain text) and records what was offered on each call. */
    private static final class DefinitionCapturingLlmClient implements LlmClient {

        private final List<List<String>> offeredToolNames = new CopyOnWriteArrayList<>();
        private final List<List<Message>> transcripts = new CopyOnWriteArrayList<>();
        private final List<LlmResponse> responses = new CopyOnWriteArrayList<>();
        private int index;

        private synchronized LlmResponse capture(List<ToolDefinition> tools, List<Message> messages) {
            offeredToolNames.add(tools.stream().map(ToolDefinition::getName).toList());
            transcripts.add(List.copyOf(messages));
            if (index < responses.size()) {
                return responses.get(index++);
            }
            return LlmResponse.text("done");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return capture(tools, messages);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return capture(tools, messages);
        }

        @Override
        public String getProviderName() {
            return "DefinitionCapturing";
        }

        List<String> firstOffer() {
            return offeredToolNames.get(0);
        }
    }

    private static Tool tool(String name) {
        return tool(name, SideEffectLevel.READ_ONLY);
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

    private DefinitionCapturingLlmClient run(List<String> allowedTools, Tool... tools) {
        return run(allowedTools, new DefinitionCapturingLlmClient(), null, tools);
    }

    private DefinitionCapturingLlmClient run(List<String> allowedTools, DefinitionCapturingLlmClient client,
            SideEffectLevel ceiling, Tool... tools) {
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);
        final OrcaAgentExecutor executor = support.newExecutor(client);
        if (ceiling != null) {
            executor.maxSideEffectLevel = ceiling;
        }
        final OrcaAgentRuntime runtime = support.newContextAllowing("agent:allow-list-main", allowedTools, tools);

        final OrcaAgentExecutionResult result = executor.execute(runtime,
                OrcaAgentExecutorTestSupport.request("conv-allow-list"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(client.offeredToolNames).as("the LLM was called at least once").isNotEmpty();
        return client;
    }

    @Test
    @DisplayName("a tool whose name is absent from the agent's allowed-tools is withheld from the LLM")
    void namesOutsideTheAllowListAreWithheld() {
        final DefinitionCapturingLlmClient client = run(List.of("Reader", "Grepper"), tool("Reader"), tool("Grepper"),
                tool("Writer"));

        assertThat(client.firstOffer()).contains("Reader", "Grepper").doesNotContain("Writer");
    }

    @Test
    @DisplayName("a pattern entry still offers its tool — a tool list cannot express which arguments are allowed")
    void patternEntriesStillOfferTheirTool() {
        final DefinitionCapturingLlmClient client = run(List.of("Bash(git:*)"), tool("Bash"), tool("Writer"));

        assertThat(client.firstOffer()).contains("Bash").doesNotContain("Writer");
    }

    @Test
    @DisplayName("an agent declaring no allow-list is offered everything, exactly as before the field existed")
    void noRestrictionsOffersEverything() {
        final DefinitionCapturingLlmClient client = run(List.of(), tool("Reader"), tool("Writer"));

        assertThat(client.firstOffer()).contains("Reader", "Writer");
    }

    @Test
    @DisplayName("the ceiling and the allow-list compose: each keeps something, together they keep nothing")
    void theTwoFiltersCompose() {
        assertThat(run(List.of("Writer"), new DefinitionCapturingLlmClient(), SideEffectLevel.READ_ONLY,
                tool("Reader", SideEffectLevel.READ_ONLY), tool("Writer", SideEffectLevel.MUTATING)).firstOffer())
                .isEmpty();

        // Each filter alone still leaves something, which is what makes the line above a composition failure rather
        // than either filter being too strict by itself.
        assertThat(run(List.of("Writer"), tool("Reader", SideEffectLevel.READ_ONLY),
                tool("Writer", SideEffectLevel.MUTATING)).firstOffer()).containsExactly("Writer");
        assertThat(run(List.of(), new DefinitionCapturingLlmClient(), SideEffectLevel.READ_ONLY,
                tool("Reader", SideEffectLevel.READ_ONLY), tool("Writer", SideEffectLevel.MUTATING)).firstOffer())
                .containsExactly("Reader");
    }

    @Test
    @DisplayName("ToolSearch is subject to the agent's allow-list, as it is to a subagent's")
    void toolSearchIsSubjectToTheAllowList() {
        assertThat(run(List.of("Reader"), tool("Reader"), tool(ToolSearchTool.TOOL_NAME)).firstOffer())
                .contains("Reader").doesNotContain(ToolSearchTool.TOOL_NAME);

        assertThat(run(List.of("Reader", ToolSearchTool.TOOL_NAME), tool("Reader"), tool(ToolSearchTool.TOOL_NAME))
                .firstOffer()).contains("Reader", ToolSearchTool.TOOL_NAME);
    }

    @Test
    @DisplayName("a forbidden call is still denied, not reported as an unknown tool")
    void forbiddenCallsStillReadAsPermissionDenials() {
        final DefinitionCapturingLlmClient client = new DefinitionCapturingLlmClient();
        // The model is not offered Writer, but nothing stops it naming one from memory — that call must be told it is
        // forbidden rather than that it does not exist. This is the half of the pair that the offer filter cannot do,
        // and it holds because only the definitions were narrowed: the execution manager still resolves against the
        // runtime's full registry.
        client.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", "Writer", Map.of()))));

        run(List.of("Reader"), client, null, tool("Reader"), tool("Writer"));

        assertThat(client.firstOffer()).doesNotContain("Writer");
        assertThat(client.transcripts).as("the turn made a second call carrying the observation").hasSizeGreaterThan(1);
        assertThat(toolObservations(client.transcripts.get(1))).singleElement(as(STRING)).contains("Writer")
                .contains("not allowed").doesNotContain("Unknown tool");
    }

    /** Returns the text of every tool observation present in one captured transcript. */
    private static List<String> toolObservations(List<Message> transcript) {
        return transcript.stream().flatMap(message -> message.getToolUseResults().stream())
                .map(ToolUseResult::getContent).toList();
    }
}
