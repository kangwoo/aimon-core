package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

class HookContextExecutionAttributesTest {

    private static final HookRegistry HOOK_REGISTRY = new DefaultHookRegistry();
    private static final Environment ENVIRONMENT = Environment.createDefault();
    private static final Instant TIMESTAMP = Instant.now();
    private static final Map<String, Object> TEST_ATTRS = Map.of("tenant", "acme", "priority", 1);

    @Nested
    class OnStartContextTest {

        @Test
        void executionAttributes_set_returnsSetAttributes() {
            OnStartContext context = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).userMessage("hello")
                    .timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS).build();

            assertThat(context.getExecutionAttributes()).isEqualTo(TEST_ATTRS);
        }

        @Test
        void executionAttributes_notSet_returnsEmptyMap() {
            OnStartContext context = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).userMessage("hello")
                    .timestamp(TIMESTAMP).build();

            assertThat(context.getExecutionAttributes()).isEmpty();
        }

        @Test
        void executionAttributes_defensiveCopy() {
            HashMap<String, Object> mutableMap = new HashMap<>();
            mutableMap.put("key", "value");

            OnStartContext context = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).userMessage("hello")
                    .timestamp(TIMESTAMP).executionAttributes(mutableMap).build();

            mutableMap.put("newKey", "newValue");

            assertThat(context.getExecutionAttributes()).hasSize(1).containsEntry("key", "value");
        }

        @Test
        void executionAttributes_returnedMapIsUnmodifiable() {
            OnStartContext context = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).userMessage("hello")
                    .timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS).build();

            assertThatThrownBy(() -> context.getExecutionAttributes().put("new", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void toString_containsExecutionAttributes() {
            OnStartContext context = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).userMessage("hello")
                    .timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS).build();

            assertThat(context.toString()).contains("executionAttributes");
        }
    }

    @Nested
    class OnStopContextTest {

        private ExecutionMetadata createMetadata() {
            Instant start = Instant.now();
            Instant end = start.plusMillis(100);
            return ExecutionMetadata.builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(start, end)
                    .build();
        }

        @Test
        void executionAttributes_set_returnsSetAttributes() {
            OnStopContext context = OnStopContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).success(true)
                    .finalAnswer("done").metadata(createMetadata()).timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS)
                    .build();

            assertThat(context.getExecutionAttributes()).isEqualTo(TEST_ATTRS);
        }

        @Test
        void executionAttributes_notSet_returnsEmptyMap() {
            OnStopContext context = OnStopContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).success(true)
                    .finalAnswer("done").metadata(createMetadata()).timestamp(TIMESTAMP).build();

            assertThat(context.getExecutionAttributes()).isEmpty();
        }

        @Test
        void executionAttributes_defensiveCopy() {
            HashMap<String, Object> mutableMap = new HashMap<>();
            mutableMap.put("key", "value");

            OnStopContext context = OnStopContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).success(true)
                    .finalAnswer("done").metadata(createMetadata()).timestamp(TIMESTAMP).executionAttributes(mutableMap)
                    .build();

            mutableMap.put("newKey", "newValue");

            assertThat(context.getExecutionAttributes()).hasSize(1).containsEntry("key", "value");
        }

        @Test
        void executionAttributes_returnedMapIsUnmodifiable() {
            OnStopContext context = OnStopContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).success(true)
                    .finalAnswer("done").metadata(createMetadata()).timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS)
                    .build();

            assertThatThrownBy(() -> context.getExecutionAttributes().put("new", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void toString_containsExecutionAttributes() {
            OnStopContext context = OnStopContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).success(true)
                    .finalAnswer("done").metadata(createMetadata()).timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS)
                    .build();

            assertThat(context.toString()).contains("executionAttributes");
        }
    }

    @Nested
    class PreToolContextTest {

        private static final ToolUse TOOL_USE = ToolUse.of("id-1", "TestTool", Map.of());

        @Test
        void executionAttributes_set_returnsSetAttributes() {
            PreToolContext context = PreToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).toolUse(TOOL_USE)
                    .iterationCount(1).timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS).build();

            assertThat(context.getExecutionAttributes()).isEqualTo(TEST_ATTRS);
        }

        @Test
        void executionAttributes_notSet_returnsEmptyMap() {
            PreToolContext context = PreToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).toolUse(TOOL_USE)
                    .iterationCount(1).timestamp(TIMESTAMP).build();

            assertThat(context.getExecutionAttributes()).isEmpty();
        }

        @Test
        void executionAttributes_defensiveCopy() {
            HashMap<String, Object> mutableMap = new HashMap<>();
            mutableMap.put("key", "value");

            PreToolContext context = PreToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).toolUse(TOOL_USE)
                    .iterationCount(1).timestamp(TIMESTAMP).executionAttributes(mutableMap).build();

            mutableMap.put("newKey", "newValue");

            assertThat(context.getExecutionAttributes()).hasSize(1).containsEntry("key", "value");
        }

        @Test
        void executionAttributes_returnedMapIsUnmodifiable() {
            PreToolContext context = PreToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).toolUse(TOOL_USE)
                    .iterationCount(1).timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS).build();

            assertThatThrownBy(() -> context.getExecutionAttributes().put("new", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void toString_containsExecutionAttributes() {
            PreToolContext context = PreToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).toolUse(TOOL_USE)
                    .iterationCount(1).timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS).build();

            assertThat(context.toString()).contains("executionAttributes");
        }
    }

    @Nested
    class PostToolContextTest {

        private static final ToolUse TOOL_USE = ToolUse.of("id-1", "TestTool", Map.of());
        private static final ToolUseResult TOOL_RESULT = ToolUseResult.success("id-1", "result");

        @Test
        void executionAttributes_set_returnsSetAttributes() {
            PostToolContext context = PostToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).toolUse(TOOL_USE)
                    .toolUseResult(TOOL_RESULT).iterationCount(1).timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS)
                    .build();

            assertThat(context.getExecutionAttributes()).isEqualTo(TEST_ATTRS);
        }

        @Test
        void executionAttributes_notSet_returnsEmptyMap() {
            PostToolContext context = PostToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).toolUse(TOOL_USE)
                    .toolUseResult(TOOL_RESULT).iterationCount(1).timestamp(TIMESTAMP).build();

            assertThat(context.getExecutionAttributes()).isEmpty();
        }

        @Test
        void executionAttributes_defensiveCopy() {
            HashMap<String, Object> mutableMap = new HashMap<>();
            mutableMap.put("key", "value");

            PostToolContext context = PostToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).toolUse(TOOL_USE)
                    .toolUseResult(TOOL_RESULT).iterationCount(1).timestamp(TIMESTAMP).executionAttributes(mutableMap)
                    .build();

            mutableMap.put("newKey", "newValue");

            assertThat(context.getExecutionAttributes()).hasSize(1).containsEntry("key", "value");
        }

        @Test
        void executionAttributes_returnedMapIsUnmodifiable() {
            PostToolContext context = PostToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).toolUse(TOOL_USE)
                    .toolUseResult(TOOL_RESULT).iterationCount(1).timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS)
                    .build();

            assertThatThrownBy(() -> context.getExecutionAttributes().put("new", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void toString_containsExecutionAttributes() {
            PostToolContext context = PostToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("test-agent").hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).toolUse(TOOL_USE)
                    .toolUseResult(TOOL_RESULT).iterationCount(1).timestamp(TIMESTAMP).executionAttributes(TEST_ATTRS)
                    .build();

            assertThat(context.toString()).contains("executionAttributes");
        }
    }
}
