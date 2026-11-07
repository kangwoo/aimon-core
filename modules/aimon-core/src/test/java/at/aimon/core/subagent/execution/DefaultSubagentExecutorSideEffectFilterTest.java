package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ProgrammaticEngineChoice;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolOfferContext;
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
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentExecutionPolicy;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tools.code.ScriptedEngine;

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
                .hookRegistry(new DefaultHookRegistry()).parentCancellationSignal(NoopCancellationSignal.INSTANCE)
                .build();

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
    @DisplayName("a registered ExecuteCode that cannot be offered is not offered to the fork, though the ceiling "
            + "permits it")
    void nonOfferableExecuteCodeIsNotOffered() {
        // A fork's offer carries no programmatic settings, and this ExecuteCode has no engine either: its MUTATING
        // grade passes the default ceiling, so only the offerability rule keeps it out.
        final DefinitionCapturingLlmClient client = runWithCeiling(new DefaultToolExecutionManager(),
                tool("Reader", SideEffectLevel.READ_ONLY),
                new ExecuteCodeTool(List.of(), CodeExecutionLimits.defaults()));

        assertThat(client.firstOffer()).containsExactly("Reader");
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

    /** MUTATING by its getters, READ_ONLY in an offering context; records what it ran with. */
    private static final class ContextualTool extends AbstractTool {

        private final List<ToolContext> ranWith = new CopyOnWriteArrayList<>();

        ContextualTool() {
            super("Contextual", "static description", Map.of("type", "object"));
        }

        @Override
        public ToolDescriptor describe(ToolOfferContext context) {
            return context.isOffering()
                    ? ToolDescriptor.from(this).sideEffectLevel(SideEffectLevel.READ_ONLY).build()
                    : ToolDescriptor.of(this);
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            ranWith.add(context);
            return ToolResult.success("ran");
        }
    }

    @Test
    @DisplayName("a fork offers and runs a tool under the grade its offer context describes, and binds its quota")
    void offerContextAndQuotaAreBound() {
        final ContextualTool tool = new ContextualTool();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool);
        final List<List<String>> offers = new CopyOnWriteArrayList<>();
        final LlmClient client = new LlmClient() {
            private LlmResponse answer(List<ToolDefinition> tools) {
                offers.add(tools.stream().map(ToolDefinition::getName).toList());
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
        final DefaultSubagentExecutor executor = new DefaultSubagentExecutor(client,
                new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY), new DefaultHookExecutionManager());
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:offer-fork"))
                .subagent(Subagent.of("explorer", SubagentMetadata.builder().description("d").maxIterations(5).build(),
                        SubagentContent.of("you are explorer")))
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(new DefaultHookRegistry()).parentCancellationSignal(NoopCancellationSignal.INSTANCE)
                .build();

        final SubagentExecutionResult result = executor.execute(context,
                SubagentExecutionRequest.builder().taskId("task-1").goal("go")
                        .executionPolicy(SubagentExecutionPolicy.builder("explorer").maxToolCalls(7).build()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(offers.get(0)).containsExactly("Contextual");
        assertThat(tool.ranWith).hasSize(1);
        final ToolContext ran = tool.ranWith.get(0);
        assertThat(ToolOfferContext.of(ran).isOffering()).isTrue();
        assertThat(ToolOfferContext.of(ran).getCeiling()).isEqualTo(SideEffectLevel.READ_ONLY);
        // The fork's tool-call budget is the one a tool that dispatches further calls admits against.
        assertThat(ran.get(ToolContextKeys.TOOL_CALL_QUOTA)).hasValueSatisfying(quota -> {
            assertThat(quota.getLimit()).contains(7);
            assertThat(quota.used()).isEqualTo(1);
        });
    }

    /** Records the full definitions offered on the first {@code sendMessage} and answers with plain text. */
    private static List<ToolDefinition> firstOfferToFork(ProgrammaticToolSettings forkProgrammatic, Tool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        final List<List<ToolDefinition>> offers = new CopyOnWriteArrayList<>();
        final LlmClient client = new LlmClient() {
            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> defs,
                    LlmModel modelConfig) {
                offers.add(List.copyOf(defs));
                return LlmResponse.text("done");
            }

            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> defs,
                    LlmModel modelConfig, LlmCallMetadata metadata) {
                return sendMessage(systemPrompt, messages, defs, modelConfig);
            }

            @Override
            public String getProviderName() {
                return "OfferCapturing";
            }
        };
        final DefaultSubagentExecutor executor = new DefaultSubagentExecutor(client, new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager());
        // The fork carries its effective settings: the execution manager narrows them by the caller's before this.
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:ptc-fork"))
                .subagent(Subagent.of("reader",
                        SubagentMetadata.builder().description("d").maxIterations(5).programmatic(forkProgrammatic)
                                .build(),
                        SubagentContent.of("you are reader")))
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(new DefaultHookRegistry()).parentCancellationSignal(NoopCancellationSignal.INSTANCE)
                .build();

        final SubagentExecutionResult result = executor.execute(context,
                SubagentExecutionRequest.builder().taskId("task-1").goal("go").build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(offers).isNotEmpty();
        return offers.get(0);
    }

    private static List<String> names(List<ToolDefinition> definitions) {
        return definitions.stream().map(ToolDefinition::getName).toList();
    }

    /** Explicit engine, since this fork has no execution environment to declare host or sandbox. */
    private static ProgrammaticToolSettings jsSettings(String... tools) {
        return ProgrammaticToolSettings.builder().tools(List.of(tools)).engine(ProgrammaticEngineChoice.JAVASCRIPT)
                .build();
    }

    private static ExecuteCodeTool executeCode() {
        return new ExecuteCodeTool(List.of(ScriptedEngine.javascript()), CodeExecutionLimits.defaults());
    }

    @Test
    @DisplayName("ExecuteCode is offered to a fork whose effective programmatic settings open tools to code")
    void executeCodeOfferedWhenOwnAndCallerAllow() {
        final List<ToolDefinition> offer = firstOfferToFork(jsSettings("Reader"),
                tool("Reader", SideEffectLevel.READ_ONLY), executeCode());

        assertThat(names(offer)).contains("Reader", ExecuteCodeTool.TOOL_NAME);
    }

    @Test
    @DisplayName("ExecuteCode is not offered to a fork whose definition does not opt in")
    void notOfferedWithoutOwnOptIn() {
        final List<ToolDefinition> offer = firstOfferToFork(ProgrammaticToolSettings.none(),
                tool("Reader", SideEffectLevel.READ_ONLY), executeCode());

        assertThat(names(offer)).containsExactly("Reader");
    }

    @Test
    @DisplayName("the fork's ExecuteCode can call exactly the tools its effective settings name")
    void callableSetIsWhatTheSettingsSay() {
        final List<ToolDefinition> offer = firstOfferToFork(jsSettings("Reader"),
                tool("Reader", SideEffectLevel.READ_ONLY), tool("Grepper", SideEffectLevel.READ_ONLY), executeCode());

        final ToolDefinition executeCode = offer.stream()
                .filter(definition -> definition.getName().equals(ExecuteCodeTool.TOOL_NAME)).findFirst().orElseThrow();
        assertThat(executeCode.getDescription()).contains("tools.Reader(").doesNotContain("tools.Grepper(");
    }
}
