package at.aimon.core.subagent.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

@DisplayName("SubagentBehaviorRunner Tests")
class SubagentBehaviorRunnerTest {

    private final SubagentBehaviorRunner runner = new SubagentBehaviorRunner();

    /** Start each test from a guaranteed-clean interrupt flag, regardless of what ran earlier in this fork. */
    @BeforeEach
    void clearInterruptBefore() {
        Thread.interrupted();
    }

    /**
     * Defensively clear any interrupt a test left on the (pooled) JUnit worker so it cannot leak into the next test.
     */
    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("a behavior that leaves the thread interrupted does not leak the flag past run()")
    void clearsLeakedInterruptAfterRun() {
        SubagentExecutionResult result = runner.run((ctx, req, support) -> {
            // Simulate a behavior that caught InterruptedException without restoring/clearing the flag.
            Thread.currentThread().interrupt();
            return support.success("done");
        }, context(NoopCancellationSignal.INSTANCE), request());

        assertThat(result.isSuccess()).isTrue();
        // The runner's finally backstop must have cleared the flag so the shared pool worker is returned clean.
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("behavior success result has zero-cost metadata shape (iterationCount=0, no tokens)")
    void successShape() {
        SubagentExecutionResult result = runner.run((ctx, req, support) -> support.success("tick"),
                context(NoopCancellationSignal.INSTANCE), request());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("tick");
        assertThat(result.getIterationCount()).isZero();
        assertThat(result.getMetadata().getTokenUsage().getTotalTokens()).isZero();
        assertThat(result.getSnapshot()).isNotNull();
    }

    @Test
    @DisplayName("behavior failure result is shaped correctly")
    void failureShape() {
        SubagentExecutionResult result = runner.run((ctx, req, support) -> support.failure("nope"),
                context(NoopCancellationSignal.INSTANCE), request());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("nope");
        assertThat(result.getIterationCount()).isZero();
    }

    @Test
    @DisplayName("a thrown exception is mapped to a failure result, not propagated")
    void thrownExceptionBecomesFailure() {
        SubagentExecutionResult result = runner.run((ctx, req, support) -> {
            throw new IllegalStateException("boom");
        }, context(NoopCancellationSignal.INSTANCE), request());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("boom");
    }

    @Test
    @DisplayName("a null return is mapped to a failure result")
    void nullReturnBecomesFailure() {
        SubagentExecutionResult result = runner.run((ctx, req, support) -> null,
                context(NoopCancellationSignal.INSTANCE), request());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("null result");
    }

    @Test
    @DisplayName("a pre-cancelled parent signal short-circuits to a failure without invoking the behavior")
    void preCancelledShortCircuits() {
        DefaultInterruptCoordinator parent = new DefaultInterruptCoordinator();
        parent.requestInterrupt(InterruptReason.USER_SIGINT);
        AtomicBoolean invoked = new AtomicBoolean(false);

        SubagentExecutionResult result = runner.run((ctx, req, support) -> {
            invoked.set(true);
            return support.success("should-not-run");
        }, context(parent.getSignal()), request());

        assertThat(invoked).isFalse();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("interrupted");
    }

    @Test
    @DisplayName("the behavior observes the parent cancellation signal via the support facade")
    void behaviorObservesSignal() {
        DefaultInterruptCoordinator parent = new DefaultInterruptCoordinator();

        SubagentExecutionResult result = runner.run((ctx, req, support) -> {
            boolean before = support.isCancelledOrInterrupted();
            parent.requestInterrupt(InterruptReason.USER_SIGINT);
            boolean after = support.isCancelledOrInterrupted();
            return support.success(before + "/" + after);
        }, context(parent.getSignal()), request());

        assertThat(result.getFinalAnswer()).isEqualTo("false/true");
    }

    @Test
    @DisplayName("without an LlmClient, the behavior sees an empty llmGateway()")
    void llmGatewayAbsentWithoutClient() {
        SubagentExecutionResult result = runner.run(
                (ctx, req, support) -> support.success(String.valueOf(support.llmGateway().isPresent())),
                context(NoopCancellationSignal.INSTANCE), request());

        assertThat(result.getFinalAnswer()).isEqualTo("false");
    }

    @Test
    @DisplayName("with an LlmClient, the behavior calls the model through the gateway")
    void llmGatewayRoutesToClient() {
        LlmClient client = mock(LlmClient.class);
        when(client.sendMessage(any(), any(), any(), any())).thenReturn(LlmResponse.text("hi from llm"));
        SubagentBehaviorRunner runnerWithLlm = new SubagentBehaviorRunner(client);

        SubagentExecutionResult result = runnerWithLlm.run((ctx, req, support) -> {
            LlmResponse resp = support.llmGateway().orElseThrow().sendMessage("You are a clock.", List.of(), List.of(),
                    ctx.getDefaultModel());
            return support.success(resp.getTextContent());
        }, context(NoopCancellationSignal.INSTANCE), request());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("hi from llm");
        verify(client).sendMessage(any(), any(), any(), any());
    }

    @Test
    @DisplayName("effectiveLlmCallMetadata attributes usage to the subagent (component = name)")
    void effectiveMetadataAttributedToSubagent() {
        SubagentExecutionResult result = runner.run(
                (ctx, req, support) -> support
                        .success(support.effectiveLlmCallMetadata().getComponent().orElse("none")),
                context(NoopCancellationSignal.INSTANCE), request());

        assertThat(result.getFinalAnswer()).isEqualTo("clock");
    }

    @Test
    @DisplayName("resolvedModel honors the subagent's model alias, not the raw default")
    void resolvedModelHonorsSubagentAlias() {
        Subagent withModel = Subagent.builder().name("clock").model("sonnet").systemPrompt("(code)").build();

        SubagentExecutionResult result = runner.run(
                (ctx, req, support) -> support.success(support.resolvedModel().getName().orElse("?")),
                contextWith(withModel, new DefaultToolRegistry()), request());

        assertThat(result.getFinalAnswer()).isEqualTo("sonnet");
    }

    @Test
    @DisplayName("resolvedModel falls back to the default model name when the subagent has no alias")
    void resolvedModelFallsBackToDefault() {
        SubagentExecutionResult result = runner.run(
                (ctx, req, support) -> support.success(support.resolvedModel().getName().orElse("?")),
                context(NoopCancellationSignal.INSTANCE), request());

        assertThat(result.getFinalAnswer()).isEqualTo("gpt-4");
    }

    @Test
    @DisplayName("scopedToolRegistry filters to the subagent's allow-list")
    void scopedToolRegistryFiltersAllowList() {
        DefaultToolRegistry full = new DefaultToolRegistry();
        full.register(toolNamed("Read"));
        full.register(toolNamed("Write"));
        Subagent restricted = Subagent.builder().name("reader").tools(List.of("Read")).systemPrompt("(code)").build();

        SubagentExecutionResult result = runner.run((ctx, req, support) -> {
            var names = support.scopedToolRegistry().findAll().stream().map(t -> t.getDefinition().getName()).toList();
            return support.success(String.join(",", names));
        }, contextWith(restricted, full), request());

        assertThat(result.getFinalAnswer()).isEqualTo("Read");
    }

    @Test
    @DisplayName("scopedToolRegistry returns the full registry when the subagent has no restrictions")
    void scopedToolRegistryUnrestrictedReturnsFull() {
        DefaultToolRegistry full = new DefaultToolRegistry();
        full.register(toolNamed("Read"));
        full.register(toolNamed("Write"));
        Subagent unrestricted = Subagent.builder().name("any").systemPrompt("(code)").build();

        SubagentExecutionResult result = runner.run(
                (ctx, req, support) -> support.success(String.valueOf(support.scopedToolRegistry().size())),
                contextWith(unrestricted, full), request());

        assertThat(result.getFinalAnswer()).isEqualTo("2");
    }

    private static Tool toolNamed(String name) {
        Tool tool = mock(Tool.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.getName()).thenReturn(name);
        when(tool.getDefinition()).thenReturn(def);
        return tool;
    }

    private static SubagentExecutionContext contextWith(Subagent subagent, ToolRegistry toolRegistry) {
        return SubagentExecutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test")).subagent(subagent)
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(toolRegistry)
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .parentCancellationSignal(NoopCancellationSignal.INSTANCE).build();
    }

    private static SubagentExecutionContext context(CancellationSignal parentSignal) {
        return SubagentExecutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagent(Subagent.builder().name("clock").systemPrompt("(code behavior)").build())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .parentCancellationSignal(parentSignal).build();
    }

    private static SubagentExecutionRequest request() {
        return SubagentExecutionRequest.builder().taskId("task-1").goal("what time is it?").build();
    }
}
