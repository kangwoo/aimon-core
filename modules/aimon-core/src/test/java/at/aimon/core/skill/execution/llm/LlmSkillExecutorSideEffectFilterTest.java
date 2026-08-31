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
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.execution.SkillExecutionContext;
import at.aimon.core.skill.execution.SkillExecutionRequest;
import at.aimon.core.skill.execution.SkillExecutionResult;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;

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
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }

        final LlmSkillExecutor executor = new LlmSkillExecutor(client, new DefaultSkillContentRenderer(), manager);
        final SkillExecutionContext context = SkillExecutionContext.builder().skill(skill())
                .defaultModel(LlmModel.builder().build()).toolRegistry(registry)
                .executionId(ExecutionId.generate("skill:ceiling-test")).build();

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
}
