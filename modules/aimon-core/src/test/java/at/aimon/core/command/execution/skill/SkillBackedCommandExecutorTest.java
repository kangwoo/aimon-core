package at.aimon.core.command.execution.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.Command;
import at.aimon.core.command.CommandMetadata;
import at.aimon.core.command.CommandType;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionRequest;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.skill.SkillBackedCommand;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.execution.SkillExecutionContext;
import at.aimon.core.skill.execution.SkillExecutionMetadata;
import at.aimon.core.skill.execution.SkillExecutionRequest;
import at.aimon.core.skill.execution.SkillExecutionResult;
import at.aimon.core.skill.execution.SkillExecutor;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("SkillBackedCommandExecutor")
class SkillBackedCommandExecutorTest {

    @Test
    @DisplayName("constructor rejects null skill executor")
    void shouldRejectNullSkillExecutor() {
        assertThatThrownBy(() -> new SkillBackedCommandExecutor(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("delegates SkillBackedCommand to SkillExecutor and propagates raw arguments")
    void shouldDelegateToSkillExecutor() {
        AtomicReference<SkillExecutionContext> seenContext = new AtomicReference<>();
        AtomicReference<SkillExecutionRequest> seenRequest = new AtomicReference<>();
        SkillExecutor stub = (context, request) -> {
            seenContext.set(context);
            seenRequest.set(request);
            return SkillExecutionResult.success("skill ran");
        };

        SkillBackedCommandExecutor executor = new SkillBackedCommandExecutor(stub);
        SkillBackedCommand command = new SkillBackedCommand(simpleSkill("commit"));

        CommandExecutionResult result = executor.execute(buildContext(command), CommandExecutionRequest.builder()
                .rawArguments("--verbose hello").arguments(List.of("--verbose", "hello")).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("skill ran");
        assertThat(seenContext.get().getSkill().getName()).isEqualTo("commit");
        assertThat(seenRequest.get().getRawArguments()).isEqualTo("--verbose hello");
        assertThat(seenRequest.get().getArguments()).containsExactly("--verbose", "hello");
    }

    /**
     * The skill executor no longer invents an identity for its run, so this executor has to supply one — and a fresh
     * one
     * per invocation. Running {@code /commit} twice must not hand both runs the same id: an execution id partitions
     * per-run state, so a shared one would quietly merge two runs' buckets. Same reason
     * {@code RoutineExecutor} generates per fire rather than reusing the task id.
     */
    @Test
    @DisplayName("gives each invocation a fresh, skill-named ExecutionId")
    void shouldGiveEachInvocationAFreshExecutionId() {
        List<ExecutionId> seen = new ArrayList<>();
        SkillExecutor stub = (context, request) -> {
            seen.add(context.getExecutionId());
            return SkillExecutionResult.success("ok");
        };

        SkillBackedCommandExecutor executor = new SkillBackedCommandExecutor(stub);
        SkillBackedCommand command = new SkillBackedCommand(simpleSkill("commit"));

        executor.execute(buildContext(command), CommandExecutionRequest.builder().build());
        executor.execute(buildContext(command), CommandExecutionRequest.builder().build());

        assertThat(seen).hasSize(2).doesNotHaveDuplicates();
        assertThat(seen).allSatisfy(id -> assertThat(id.value()).startsWith("skill:commit:"));
    }

    @Test
    @DisplayName("rejects non-SkillBackedCommand input")
    void shouldRejectNonSkillBackedCommand() {
        SkillBackedCommandExecutor executor = new SkillBackedCommandExecutor(
                (c, r) -> SkillExecutionResult.success(""));

        Command notSkillBacked = new StubCommand("legacy");

        CommandExecutionContext context = buildContext(notSkillBacked);
        CommandExecutionRequest request = CommandExecutionRequest.builder().build();

        assertThatThrownBy(() -> executor.execute(context, request)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SkillBackedCommand");
    }

    @Test
    @DisplayName("maps successful SkillExecutionResult metadata to ExecutionMetadata")
    void shouldMapSuccessMetadata() {
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        Instant end = start.plusSeconds(3);
        SkillExecutionMetadata skillMeta = SkillExecutionMetadata.builder().iterationCount(2)
                .tokenUsage(TokenUsage.of(11, 7, 18)).timestamps(start, end).build();
        SkillExecutor stub = (c, r) -> SkillExecutionResult.success("ok", skillMeta);

        CommandExecutionResult result = new SkillBackedCommandExecutor(stub).execute(
                buildContext(new SkillBackedCommand(simpleSkill("commit"))), CommandExecutionRequest.builder().build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("ok");
        assertThat(result.getMetadata()).isPresent();
        assertThat(result.getMetadata().get().getIterationCount()).isEqualTo(2);
        assertThat(result.getMetadata().get().getTokenUsage()).isEqualTo(TokenUsage.of(11, 7, 18));
        assertThat(result.getMetadata().get().getStartTime()).isEqualTo(start);
        assertThat(result.getMetadata().get().getEndTime()).isEqualTo(end);
    }

    @Test
    @DisplayName("maps failed SkillExecutionResult to failure with error and metadata")
    void shouldMapFailureMetadata() {
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        Instant end = start.plusSeconds(1);
        SkillExecutionMetadata skillMeta = SkillExecutionMetadata.builder().iterationCount(0)
                .tokenUsage(TokenUsage.empty()).timestamps(start, end).build();
        IllegalStateException cause = new IllegalStateException("boom");
        SkillExecutor stub = (c, r) -> SkillExecutionResult.failure("permission denied", cause, skillMeta);

        CommandExecutionResult result = new SkillBackedCommandExecutor(stub).execute(
                buildContext(new SkillBackedCommand(simpleSkill("commit"))), CommandExecutionRequest.builder().build());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getResponse()).isEqualTo("permission denied");
        assertThat(result.getError()).containsSame(cause);
        assertThat(result.getMetadata()).isPresent();
        assertThat(result.getMetadata().get().getStartTime()).isEqualTo(start);
    }

    @Test
    @DisplayName("success result without metadata maps to success without metadata")
    void shouldOmitMetadataWhenSkillResultHasNone() {
        SkillExecutor stub = (c, r) -> SkillExecutionResult.success("ok");

        CommandExecutionResult result = new SkillBackedCommandExecutor(stub).execute(
                buildContext(new SkillBackedCommand(simpleSkill("commit"))), CommandExecutionRequest.builder().build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMetadata()).isEmpty();
    }

    @Test
    @DisplayName("forwards CommandExecutionContext.getToolContext() into SkillExecutionContext")
    void shouldForwardToolContextToSkillExecutionContext() {
        AtomicReference<SkillExecutionContext> seenContext = new AtomicReference<>();
        SkillExecutor stub = (context, request) -> {
            seenContext.set(context);
            return SkillExecutionResult.success("ok");
        };

        ToolContext toolContext = ToolContext.builder()
                .put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.fromName("ctx-fwd")).build();

        CommandExecutionContext context = CommandExecutionContext.builder()
                .command(new SkillBackedCommand(simpleSkill("commit"))).defaultModel(LlmModel.builder().build())
                .toolRegistry(new DefaultToolRegistry()).toolContext(toolContext).build();

        new SkillBackedCommandExecutor(stub).execute(context, CommandExecutionRequest.builder().build());

        assertThat(seenContext.get().getToolContext()).isSameAs(toolContext);
    }

    @Test
    @DisplayName("default CommandExecutionContext.getToolContext() returns ToolContext.empty()")
    void shouldDefaultToEmptyToolContext() {
        AtomicReference<SkillExecutionContext> seenContext = new AtomicReference<>();
        SkillExecutor stub = (context, request) -> {
            seenContext.set(context);
            return SkillExecutionResult.success("ok");
        };

        new SkillBackedCommandExecutor(stub).execute(buildContext(new SkillBackedCommand(simpleSkill("commit"))),
                CommandExecutionRequest.builder().build());

        assertThat(seenContext.get().getToolContext()).isNotNull();
        assertThat(seenContext.get().getToolContext().getContext()).isEmpty();
    }

    @Test
    @DisplayName("execute() rejects null context and request")
    void shouldRejectNullContextOrRequest() {
        SkillBackedCommandExecutor executor = new SkillBackedCommandExecutor(
                (c, r) -> SkillExecutionResult.success(""));
        CommandExecutionRequest request = CommandExecutionRequest.builder().build();
        CommandExecutionContext context = buildContext(new SkillBackedCommand(simpleSkill("commit")));

        assertThatThrownBy(() -> executor.execute(null, request)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> executor.execute(context, null)).isInstanceOf(NullPointerException.class);
    }

    private static CommandExecutionContext buildContext(Command command) {
        ToolRegistry toolRegistry = new DefaultToolRegistry();
        return CommandExecutionContext.builder().command(command).defaultModel(LlmModel.builder().build())
                .toolRegistry(toolRegistry).build();
    }

    private static Skill simpleSkill(String name) {
        SkillMetadata m = SkillMetadata.builder().name(name).description("desc").build();
        return Skill.builder().name(name).metadata(m).content(SkillContent.of("body")).build();
    }

    private static final class StubCommand implements Command {
        private final String name;

        StubCommand(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public CommandMetadata getMetadata() {
            return CommandMetadata.builder().description("desc").build();
        }

        @Override
        public CommandType getType() {
            return CommandType.CUSTOM;
        }
    }
}
