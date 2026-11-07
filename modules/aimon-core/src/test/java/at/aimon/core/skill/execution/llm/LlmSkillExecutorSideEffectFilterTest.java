package at.aimon.core.skill.execution.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.ExecutionId;
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
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.execution.SkillExecutionContext;
import at.aimon.core.skill.execution.SkillExecutionRequest;
import at.aimon.core.skill.execution.SkillExecutionResult;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.toolinvocation.ToolCallDispatchers;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tools.code.ScriptedEngine;

/**
 * Locks in the slash-command half of the {@link SideEffectLevel} pair: a user-invoked skill runs its own ReAct loop
 * against the agent's real tool registry, so the ceiling that governs the agent must govern the skill too.
 *
 * <p>
 * Until the {@code ToolExecutionManager} was threaded through {@code DefaultCommandExecutionManager}, it did not: the
 * skill executor was handed a freshly minted default manager, which permits everything. A {@code READ_ONLY} agent
 * could therefore write to disk by way of {@code /some-skill}. These tests pin both halves of the fix — the definition
 * filter that keeps blocked tools out of the prompt, and the manager refusal that still catches a tool the model names
 * without having been shown it.
 */
@DisplayName("LlmSkillExecutor side-effect ceiling")
class LlmSkillExecutorSideEffectFilterTest {

    /** Records the tool definitions offered on each {@code sendMessage} and replays a scripted response. */
    private static final class DefinitionCapturingLlmClient implements LlmClient {

        private final List<List<String>> offeredToolNames = new CopyOnWriteArrayList<>();
        private final List<LlmResponse> script;
        private int callCount;

        private DefinitionCapturingLlmClient(LlmResponse... script) {
            this.script = List.of(script);
        }

        private synchronized LlmResponse capture(List<ToolDefinition> tools) {
            offeredToolNames.add(tools.stream().map(ToolDefinition::getName).toList());
            final int index = Math.min(callCount++, script.size() - 1);
            return script.get(index);
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

    private static Skill skill() {
        return Skill.builder().name("cleanup")
                .metadata(SkillMetadata.builder().name("cleanup").description("Tidy things up").build())
                .content(SkillContent.of("Tidy things up")).build();
    }

    private static SkillExecutionResult run(DefinitionCapturingLlmClient client, DefaultToolExecutionManager manager,
            Tool... tools) {
        return run(skill(), client, manager, tools);
    }

    private static SkillExecutionResult run(Skill skill, DefinitionCapturingLlmClient client,
            DefaultToolExecutionManager manager, Tool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }

        final LlmSkillExecutor executor = new LlmSkillExecutor(client, new DefaultSkillContentRenderer(), manager);
        final SkillExecutionContext context = SkillExecutionContext.builder().skill(skill)
                .defaultModel(LlmModel.builder().build()).toolRegistry(registry)
                .executionId(ExecutionId.generate("skill:ceiling-test"))
                .toolContext(ToolContext.builder()
                        .put(ToolContextKeys.TOOL_CALL_DISPATCHER, ToolCallDispatchers.ungated(manager, registry))
                        .build())
                .build();

        return executor.execute(context, SkillExecutionRequest.builder().build());
    }

    @Test
    @DisplayName("a READ_ONLY manager ceiling withholds MUTATING tool definitions from the skill's LLM")
    void readOnlyCeilingWithholdsMutatingTools() {
        final DefinitionCapturingLlmClient client = new DefinitionCapturingLlmClient(LlmResponse.text("done"));

        final SkillExecutionResult result = run(client, new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY),
                tool("Reader", SideEffectLevel.READ_ONLY), tool("Writer", SideEffectLevel.MUTATING));

        assertThat(result.isSuccess()).isTrue();
        assertThat(client.firstOffer()).contains("Reader").doesNotContain("Writer");
    }

    @Test
    @DisplayName("the default ceiling withholds nothing, so undeclared tools stay visible to the skill")
    void defaultCeilingOffersEverything() {
        final Tool undeclared = new AbstractTool("Legacy", "declares nothing", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ran Legacy");
            }
        };

        final DefinitionCapturingLlmClient client = new DefinitionCapturingLlmClient(LlmResponse.text("done"));

        run(client, new DefaultToolExecutionManager(), tool("Reader", SideEffectLevel.READ_ONLY), undeclared);

        assertThat(client.firstOffer()).contains("Reader", "Legacy");
    }

    @Test
    @DisplayName("naming a withheld tool anyway is refused by the manager, not run")
    void ceilingStillRefusesToolTheModelNamesUnprompted() {
        final AtomicBoolean writerRan = new AtomicBoolean();
        final Tool writer = new AbstractTool("Writer", "Writer description", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                writerRan.set(true);
                return ToolResult.success("wrote");
            }

            @Override
            public SideEffectLevel getSideEffectLevel() {
                return SideEffectLevel.MUTATING;
            }
        };

        final DefinitionCapturingLlmClient client = new DefinitionCapturingLlmClient(
                LlmResponse.of("writing", List.of(ToolUse.of("call-1", "Writer", Map.of()))),
                LlmResponse.text("gave up"));

        final SkillExecutionResult result = run(client, new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY),
                tool("Reader", SideEffectLevel.READ_ONLY), writer);

        assertThat(result.isSuccess()).isTrue();
        assertThat(writerRan).as("the blocked tool never executed").isFalse();
        assertThat(client.offeredToolNames).as("the loop ran a second iteration after the refusal").hasSize(2);
    }

    @Test
    @DisplayName("the skill offers and runs a tool under its own offer context, replacing one the caller bound")
    void skillBindsItsOwnOfferContext() {
        final List<ToolOfferContext> ranUnder = new CopyOnWriteArrayList<>();
        final Tool contextual = new AbstractTool("Contextual", "d", Map.of("type", "object")) {
            @Override
            public ToolDescriptor describe(ToolOfferContext context) {
                return context.isOffering()
                        ? ToolDescriptor.from(this).sideEffectLevel(SideEffectLevel.READ_ONLY).build()
                        : ToolDescriptor.of(this);
            }

            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                ranUnder.add(ToolOfferContext.of(context));
                return ToolResult.success("ran");
            }
        };
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(contextual);
        final DefaultToolExecutionManager manager = new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY);
        final DefinitionCapturingLlmClient client = new DefinitionCapturingLlmClient(
                LlmResponse.of("run", List.of(ToolUse.of("call-1", "Contextual", Map.of()))), LlmResponse.text("done"));
        // The enclosing turn's iteration already bound an offer of its own; the skill must not trip the write-once key.
        final ToolOfferContext callers = ToolOfferContext.builder().build();
        final SkillExecutionContext context = SkillExecutionContext.builder().skill(skill())
                .defaultModel(LlmModel.builder().build()).toolRegistry(registry)
                .executionId(ExecutionId.generate("skill:offer-test"))
                .toolContext(ToolContext.builder()
                        .put(ToolContextKeys.TOOL_CALL_DISPATCHER, ToolCallDispatchers.ungated(manager, registry))
                        .put(ToolContextKeys.TOOL_OFFER_CONTEXT, callers).build())
                .build();

        final SkillExecutionResult result = new LlmSkillExecutor(client, new DefaultSkillContentRenderer(), manager)
                .execute(context, SkillExecutionRequest.builder().build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(client.firstOffer()).containsExactly("Contextual");
        assertThat(ranUnder).hasSize(1);
        assertThat(ranUnder.get(0)).isNotSameAs(callers);
        assertThat(ranUnder.get(0).getCeiling()).isEqualTo(SideEffectLevel.READ_ONLY);
    }

    @Test
    @DisplayName("a registered ExecuteCode that cannot be offered here is not offered, though the ceiling permits it")
    void nonOfferableExecuteCodeIsNotOffered() {
        // No engine, and a skill's offer carries no programmatic settings: ExecuteCode's descriptor is not offerable,
        // while its MUTATING grade passes the default ceiling — the ceiling alone would let it through.
        final ExecuteCodeTool executeCode = new ExecuteCodeTool(List.of(), CodeExecutionLimits.defaults());

        final DefinitionCapturingLlmClient unrestricted = new DefinitionCapturingLlmClient(LlmResponse.text("done"));
        run(unrestricted, new DefaultToolExecutionManager(), tool("Reader", SideEffectLevel.READ_ONLY), executeCode);
        assertThat(unrestricted.firstOffer()).containsExactly("Reader");

        final Skill naming = Skill.builder().name("coder").metadata(SkillMetadata.builder().name("coder")
                .description("Runs code").allowedTools("Reader ExecuteCode").build())
                .content(SkillContent.of("Runs code")).build();
        final DefinitionCapturingLlmClient named = new DefinitionCapturingLlmClient(LlmResponse.text("done"));
        run(naming, named, new DefaultToolExecutionManager(), tool("Reader", SideEffectLevel.READ_ONLY), executeCode);
        assertThat(named.firstOffer()).containsExactly("Reader");
    }

    /** Explicit engine, since the skill's context has no execution environment to declare host or sandbox. */
    private static ProgrammaticToolSettings jsSettings(String... tools) {
        return ProgrammaticToolSettings.builder().tools(List.of(tools)).engine(ProgrammaticEngineChoice.JAVASCRIPT)
                .build();
    }

    /** Runs a skill with the given programmatic settings under a caller publishing {@code caller} (null: absent). */
    private static List<String> firstOfferUnderCaller(ProgrammaticToolSettings skillProgrammatic,
            ProgrammaticToolSettings caller) {
        final DefaultToolExecutionManager manager = new DefaultToolExecutionManager();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool("Reader", SideEffectLevel.READ_ONLY));
        registry.register(new ExecuteCodeTool(List.of(ScriptedEngine.javascript()), CodeExecutionLimits.defaults()));
        final Skill skill = Skill.builder().name("probe").metadata(
                SkillMetadata.builder().name("probe").description("probe").programmatic(skillProgrammatic).build())
                .content(SkillContent.of("probe")).build();
        final ToolContext.Builder toolContext = ToolContext.builder().put(ToolContextKeys.TOOL_CALL_DISPATCHER,
                ToolCallDispatchers.ungated(manager, registry,
                        caller == null ? ProgrammaticToolSettings.none() : caller));
        if (caller != null) {
            toolContext.put(ToolContextKeys.CALLER_PROGRAMMATIC, caller);
        }
        final DefinitionCapturingLlmClient client = new DefinitionCapturingLlmClient(LlmResponse.text("done"));
        final SkillExecutionResult result = new LlmSkillExecutor(client, new DefaultSkillContentRenderer(), manager)
                .execute(SkillExecutionContext.builder().skill(skill).defaultModel(LlmModel.builder().build())
                        .toolRegistry(registry).executionId(ExecutionId.generate("skill:ptc-test"))
                        .toolContext(toolContext.build()).build(), SkillExecutionRequest.builder().build());

        assertThat(result.isSuccess()).isTrue();
        return client.firstOffer();
    }

    @Test
    @DisplayName("ExecuteCode is offered to a slash skill when both the skill and its caller open tools to code")
    void executeCodeOfferedWhenSkillAndCallerAllow() {
        assertThat(firstOfferUnderCaller(jsSettings("Reader"), jsSettings("Reader", "Grep"))).contains("Reader",
                ExecuteCodeTool.TOOL_NAME);
    }

    @Test
    @DisplayName("ExecuteCode is not offered to a skill without programmatic keys, though its caller has them")
    void notOfferedWithoutSkillKeys() {
        assertThat(firstOfferUnderCaller(ProgrammaticToolSettings.none(), jsSettings("Reader")))
                .containsExactly("Reader");
    }

    @Test
    @DisplayName("ExecuteCode is not offered to a skill whose caller has no programmatic settings")
    void notOfferedWithoutCallerSettings() {
        assertThat(firstOfferUnderCaller(jsSettings("Reader"), null)).containsExactly("Reader");
        assertThat(firstOfferUnderCaller(jsSettings("Reader"), ProgrammaticToolSettings.none()))
                .containsExactly("Reader");
    }

    @Test
    @DisplayName("ExecuteCode is not offered when the skill's and caller's tools do not meet")
    void notOfferedWhenDisjoint() {
        assertThat(firstOfferUnderCaller(jsSettings("Reader"), jsSettings("Grep"))).containsExactly("Reader");
    }
}
