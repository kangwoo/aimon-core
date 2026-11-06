package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.programmatic.CodeExecutionLimits;

@DisplayName("DefaultToolCallBridge — limits, collection and closing")
class DefaultToolCallBridgeTest {

    private static final ToolDescriptor READ = ToolDescriptor.of(TestTool.reader("Read"));

    private static ToolCallDispatcher answering(String text, List<String> notices, List<String> feedback) {
        return new ToolCallDispatcher() {
            @Override
            public ToolDispatchResult dispatch(at.aimon.core.llm.ToolUse toolUse,
                    at.aimon.core.agent.tool.ToolCallOrigin origin, ToolContext context) {
                return ToolDispatchResult.builder().toolUseId(toolUse.getId()).text(text).notices(notices)
                        .hookFeedback(feedback).build();
            }

            @Override
            public ToolCallDispatcher narrow(ToolRegistry registry, List<AllowedTool> allowedTools) {
                return this;
            }
        };
    }

    private static DefaultToolCallBridge bridge(ToolCallDispatcher dispatcher, CodeExecutionLimits limits) {
        return new DefaultToolCallBridge(dispatcher, ToolContext.empty(), "tu", "javascript", List.of(READ), limits,
                null);
    }

    @Test
    @DisplayName("nested ids are <parent>#n; notices and feedback are deduplicated")
    void collects() {
        final DefaultToolCallBridge bridge = bridge(answering("ok", List.of("n1"), List.of("f1")),
                CodeExecutionLimits.defaults());
        final ToolDispatchResult first = bridge.callAsync("Read", Map.of()).join();
        bridge.callAsync("Read", Map.of()).join();
        assertThat(first.getToolUseId()).isEqualTo("tu#1");
        assertThat(bridge.notices()).containsExactly("n1");
        assertThat(bridge.hookFeedback()).containsExactly("f1");
        assertThat(bridge.close(Duration.ofSeconds(1))).isEmpty();
        assertThat(bridge.callAsync("Read", Map.of()).join().getText()).contains("has ended");
    }

    @Test
    @DisplayName("one call past the nested-call limit stops the program and trips the child signal")
    void nestedCallLimit() {
        final DefaultToolCallBridge bridge = bridge(answering("ok", List.of(), List.of()),
                CodeExecutionLimits.builder().maxNestedCalls(2).build());
        bridge.callAsync("Read", Map.of()).join();
        bridge.callAsync("Read", Map.of()).join();
        final ToolDispatchResult third = bridge.callAsync("Read", Map.of()).join();
        assertThat(third.isError()).isTrue();
        assertThat(bridge.limitExceeded()).hasValueSatisfying(reason -> assertThat(reason).contains("2 tool calls"));
        assertThat(bridge.childSignal().isCancelled()).isTrue();
        bridge.close(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("arguments larger than the limit are refused before dispatch")
    void argumentSize() {
        final DefaultToolCallBridge bridge = bridge(answering("ok", List.of(), List.of()),
                CodeExecutionLimits.builder().maxArgumentBytes(16).build());
        final ToolDispatchResult result = bridge.callAsync("Read", Map.of("path", "x".repeat(100))).join();
        assertThat(result.isError()).isTrue();
        assertThat(result.getText()).contains("exceed the limit of 16 bytes");
        assertThat(bridge.records()).singleElement()
                .satisfies(call -> assertThat(call.refusal()).isEqualTo("argument size"));
        bridge.close(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("closing waits for running calls, at most the given time, and names those still running")
    void closeWaits() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ToolCallDispatcher slow = new ToolCallDispatcher() {
            @Override
            public ToolDispatchResult dispatch(at.aimon.core.llm.ToolUse toolUse,
                    at.aimon.core.agent.tool.ToolCallOrigin origin, ToolContext context) {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolDispatchResult.builder().toolUseId(toolUse.getId()).text("late").build();
            }

            @Override
            public ToolCallDispatcher narrow(ToolRegistry registry, List<AllowedTool> allowedTools) {
                return this;
            }
        };
        final DefaultToolCallBridge bridge = bridge(slow, CodeExecutionLimits.defaults());
        bridge.callAsync("Read", Map.of());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(bridge.close(Duration.ofMillis(100))).containsExactly("Read");
        release.countDown();
    }
}
