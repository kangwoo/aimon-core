package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

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
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.tools.search.ToolSearchTool;

/**
 * Locks in the allow-list half of the fork's definition filter, the sibling of
 * {@link DefaultSubagentExecutorSideEffectFilterTest}: a fork is not offered a tool whose name its own
 * {@code allowed-tools} never mentions, because the same allow-list goes on to refuse that call at dispatch.
 *
 * <p>
 * Three properties are worth holding still, and only the first is the headline:
 *
 * <ul>
 * <li>A name absent from the allow-list is withheld — the wasted iteration this removes.
 * <li>A <b>pattern</b> entry still offers its tool. {@code Bash(git:*)} keeps {@code Bash} visible because a tool list
 * cannot say "which arguments"; narrowing further would hide calls that are actually permitted.
 * <li>The <b>registry</b> is untouched, so a forbidden call still reports a permission denial rather than
 * {@code "Unknown tool: …"}. That distinction is deliberate in {@code DefaultToolExecutionManager}, and filtering the
 * definitions rather than the registry is what preserves it.
 * </ul>
 */
@DisplayName("DefaultSubagentExecutor allow-list definition filter")
class DefaultSubagentExecutorAllowedToolFilterTest {

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
        return new AbstractTool(name, name + " description", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ran " + name);
            }
        };
    }

    private static Subagent subagentAllowing(List<String> allowedTools) {
        return Subagent.of("explorer",
                SubagentMetadata.builder().description("d").maxIterations(5).tools(allowedTools).build(),
                SubagentContent.of("you are explorer"));
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

    private DefinitionCapturingLlmClient run(Subagent subagent, DefinitionCapturingLlmClient client, Tool... tools) {
        return run(subagent, client, new DefaultToolExecutionManager(), tools);
    }

    private DefinitionCapturingLlmClient run(Subagent subagent, DefinitionCapturingLlmClient client,
            DefaultToolExecutionManager manager, Tool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }

        final DefaultSubagentExecutor executor = new DefaultSubagentExecutor(client, manager,
                new DefaultHookExecutionManager());

        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:allow-list-fork")).subagent(subagent)
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
    @DisplayName("a tool whose name is absent from allowed-tools is withheld from the fork's LLM")
    void namesOutsideTheAllowListAreWithheld() {
        final DefinitionCapturingLlmClient client = run(subagentAllowing(List.of("Reader", "Grepper")),
                new DefinitionCapturingLlmClient(), tool("Reader"), tool("Grepper"), tool("Writer"));

        assertThat(client.firstOffer()).contains("Reader", "Grepper").doesNotContain("Writer");
    }

    @Test
    @DisplayName("a pattern entry still offers its tool — a tool list cannot express which arguments are allowed")
    void patternEntriesStillOfferTheirTool() {
        final DefinitionCapturingLlmClient client = run(subagentAllowing(List.of("Bash(git:*)")),
                new DefinitionCapturingLlmClient(), tool("Bash"), tool("Writer"));

        assertThat(client.firstOffer()).contains("Bash").doesNotContain("Writer");
    }

    @Test
    @DisplayName("a subagent declaring no restrictions is offered everything")
    void noRestrictionsOffersEverything() {
        final DefinitionCapturingLlmClient client = run(subagentAllowing(List.of()), new DefinitionCapturingLlmClient(),
                tool("Reader"), tool("Writer"));

        assertThat(client.firstOffer()).contains("Reader", "Writer");
    }

    @Test
    @DisplayName("an allow-list naming nothing registered leaves an empty offer, and the fork still reports success")
    void anAllowListNamingNothingRegisteredEmptiesTheOffer() {
        // Pinning the bad outcome rather than endorsing it. Every provider omits an empty `tools` field instead of
        // rejecting it, so the model answers from prose and the fork reports COMPLETED — a fabricated answer a parent
        // reads as clean. Before this filter the allow-list could not produce this state; now a typo can, which is why
        // availableToolDefinitions logs it. Changing the outcome to a failure is a behaviour change on its own.
        final DefinitionCapturingLlmClient client = run(subagentAllowing(List.of("Reeder", "Grepper")),
                new DefinitionCapturingLlmClient(), tool("Reader"), tool("Writer"));

        assertThat(client.firstOffer()).isEmpty();
    }

    @Test
    @DisplayName("the ceiling and the allow-list compose: each keeps something, together they keep nothing")
    void theTwoFiltersCompose() {
        // The case neither filter can reach alone, and the one this PR newly makes reachable. A writer subagent under
        // a read-only ceiling: the allow-list keeps only Write, the ceiling strips Write, and the intersection is
        // empty. Before this change the allow-list did not touch the offer, so the model was still shown Reader.
        final DefinitionCapturingLlmClient client = run(subagentAllowing(List.of("Writer")),
                new DefinitionCapturingLlmClient(), new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY),
                tool("Reader", SideEffectLevel.READ_ONLY), tool("Writer", SideEffectLevel.MUTATING));

        assertThat(client.firstOffer()).isEmpty();

        // Each filter alone still leaves something, which is what makes this a composition failure rather than either
        // filter being too strict on its own.
        assertThat(run(subagentAllowing(List.of("Writer")), new DefinitionCapturingLlmClient(),
                tool("Reader", SideEffectLevel.READ_ONLY), tool("Writer", SideEffectLevel.MUTATING)).firstOffer())
                .containsExactly("Writer");
        assertThat(run(subagentAllowing(List.of()), new DefinitionCapturingLlmClient(),
                new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY), tool("Reader", SideEffectLevel.READ_ONLY),
                tool("Writer", SideEffectLevel.MUTATING)).firstOffer()).containsExactly("Reader");
    }

    @Test
    @DisplayName("ToolSearch is withheld unless the allow-list names it, as the tool-search design intends")
    void toolSearchIsSubjectToTheAllowList() {
        // docs/design/tool/tool-search.md §7 makes ToolSearch subject to the allow-list on purpose, so this is the
        // documented control rather than an oversight — but in a deployment whose tools are all deferred it is also
        // the only route to them, so omitting it strands the fork. The guide's table now says so.
        final DefinitionCapturingLlmClient withoutIt = run(subagentAllowing(List.of("Reader")),
                new DefinitionCapturingLlmClient(), tool("Reader"), tool(ToolSearchTool.TOOL_NAME));
        assertThat(withoutIt.firstOffer()).contains("Reader").doesNotContain(ToolSearchTool.TOOL_NAME);

        final DefinitionCapturingLlmClient withIt = run(subagentAllowing(List.of("Reader", ToolSearchTool.TOOL_NAME)),
                new DefinitionCapturingLlmClient(), tool("Reader"), tool(ToolSearchTool.TOOL_NAME));
        assertThat(withIt.firstOffer()).contains("Reader", ToolSearchTool.TOOL_NAME);
    }

    @Test
    @DisplayName("a forbidden call is still denied, not reported as an unknown tool")
    void forbiddenCallsStillReadAsPermissionDenials() {
        final DefinitionCapturingLlmClient client = new DefinitionCapturingLlmClient();
        // The model is not offered Writer, but nothing stops it naming one anyway — that call must still be told it
        // is forbidden rather than that it does not exist. This holds because the execution manager resolves against
        // the context's full registry (SingleToolInvoker passes spec.getToolRegistry()), independently of what the
        // offer was narrowed to.
        client.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", "Writer", Map.of()))));

        run(subagentAllowing(List.of("Reader")), client, tool("Reader"), tool("Writer"));

        assertThat(client.offeredToolNames.get(0)).doesNotContain("Writer");
        assertThat(client.transcripts).as("the fork made a second call carrying the observation").hasSizeGreaterThan(1);

        assertThat(toolObservations(client.transcripts.get(1))).singleElement(as(STRING)).contains("Writer")
                .contains("not allowed").doesNotContain("Unknown tool");
    }

    /** Returns the text of every tool observation present in one captured transcript. */
    private static List<String> toolObservations(List<Message> transcript) {
        return transcript.stream().flatMap(message -> message.getToolUseResults().stream())
                .map(ToolUseResult::getContent).toList();
    }
}
