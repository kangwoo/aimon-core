package at.aimon.cli.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;

@DisplayName("ToolCallDisplayHook Tests")
@ExtendWith(MockitoExtension.class)
class ToolCallDisplayHookTest {

    @Mock
    private OutputFormatter outputFormatter;

    private ToolCallDisplayHook hook;

    @BeforeEach
    void setUp() {
        hook = new ToolCallDisplayHook(outputFormatter);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw NullPointerException for null OutputFormatter")
        void shouldThrowNullPointerExceptionForNullOutputFormatter() {
            assertThatThrownBy(() -> new ToolCallDisplayHook(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("OutputFormatter cannot be null");
        }
    }

    @Nested
    @DisplayName("Execute")
    class Execute {

        @Test
        @DisplayName("Should return success HookResult")
        void shouldReturnSuccessHookResult() {
            ToolUse toolUse = ToolUse.of("id-1", "Read", Map.of("file_path", "/tmp/test.txt"));
            PreToolContext context = mockPreToolContext(toolUse, InvokerType.MAIN_AGENT);

            HookResult result = hook.execute(context);

            assertThat(result).isNotNull();
            assertThat(result.isBlocked()).isFalse();
            assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        }

        @Test
        @DisplayName("Should call displayToolCall with correct arguments")
        void shouldCallDisplayToolCallWithCorrectArguments() {
            ToolUse toolUse = ToolUse.of("id-1", "Read", Map.of("file_path", "/tmp/test.txt"));
            PreToolContext context = mockPreToolContext(toolUse, InvokerType.MAIN_AGENT);

            hook.execute(context);

            ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<InvokerType> typeCaptor = ArgumentCaptor.forClass(InvokerType.class);

            verify(outputFormatter).displayToolCall(nameCaptor.capture(), inputCaptor.capture(), typeCaptor.capture());

            assertThat(nameCaptor.getValue()).isEqualTo("Read");
            assertThat(inputCaptor.getValue()).contains("file_path");
            assertThat(typeCaptor.getValue()).isEqualTo(InvokerType.MAIN_AGENT);
        }

        @Test
        @DisplayName("Should pass SUBAGENT invoker type to displayToolCall")
        void shouldPassSubagentInvokerTypeToDisplayToolCall() {
            ToolUse toolUse = ToolUse.of("id-2", "Bash", Map.of("command", "ls"));
            PreToolContext context = mockPreToolContext(toolUse, InvokerType.SUBAGENT);

            hook.execute(context);

            verify(outputFormatter).displayToolCall(eq("Bash"), org.mockito.ArgumentMatchers.anyString(),
                    eq(InvokerType.SUBAGENT));
        }
    }

    @Nested
    @DisplayName("Format Input")
    class FormatInput {

        @Test
        @DisplayName("Should format empty input as empty string")
        void shouldFormatEmptyInputAsEmptyString() {
            ToolUse toolUse = ToolUse.of("id-1", "Read", Collections.emptyMap());
            PreToolContext context = mockPreToolContext(toolUse, InvokerType.MAIN_AGENT);

            hook.execute(context);

            verify(outputFormatter).displayToolCall(eq("Read"), eq(""), eq(InvokerType.MAIN_AGENT));
        }

        @Test
        @DisplayName("Should format null input as empty string")
        void shouldFormatNullInputAsEmptyString() {
            ToolUse toolUse = mock(ToolUse.class);
            when(toolUse.getName()).thenReturn("Read");
            when(toolUse.getInput()).thenReturn(null);
            PreToolContext context = mockPreToolContext(toolUse, InvokerType.MAIN_AGENT);

            hook.execute(context);

            verify(outputFormatter).displayToolCall(eq("Read"), eq(""), eq(InvokerType.MAIN_AGENT));
        }

        @Test
        @DisplayName("Should format single string parameter with quotes")
        void shouldFormatSingleStringParameterWithQuotes() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("file_path", "/tmp/test.txt");
            ToolUse toolUse = ToolUse.of("id-1", "Read", input);
            PreToolContext context = mockPreToolContext(toolUse, InvokerType.MAIN_AGENT);

            hook.execute(context);

            ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
            verify(outputFormatter).displayToolCall(eq("Read"), inputCaptor.capture(), eq(InvokerType.MAIN_AGENT));

            assertThat(inputCaptor.getValue()).isEqualTo("{file_path: \"/tmp/test.txt\"}");
        }

        @Test
        @DisplayName("Should format multiple parameters")
        void shouldFormatMultipleParameters() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("file_path", "/tmp/test.txt");
            input.put("offset", 10);
            ToolUse toolUse = mockToolUse("Read", input);
            PreToolContext context = mockPreToolContext(toolUse, InvokerType.MAIN_AGENT);

            hook.execute(context);

            ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
            verify(outputFormatter).displayToolCall(eq("Read"), inputCaptor.capture(), eq(InvokerType.MAIN_AGENT));

            assertThat(inputCaptor.getValue()).isEqualTo("{file_path: \"/tmp/test.txt\", offset: 10}");
        }

        @Test
        @DisplayName("Should truncate strings longer than 100 characters")
        void shouldTruncateStringsLongerThan100Characters() {
            String longString = "a".repeat(150);
            String expectedTruncated = "\"" + "a".repeat(97) + "...\"";

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("content", longString);
            ToolUse toolUse = ToolUse.of("id-1", "Write", input);
            PreToolContext context = mockPreToolContext(toolUse, InvokerType.MAIN_AGENT);

            hook.execute(context);

            ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
            verify(outputFormatter).displayToolCall(eq("Write"), inputCaptor.capture(), eq(InvokerType.MAIN_AGENT));

            assertThat(inputCaptor.getValue()).isEqualTo("{content: " + expectedTruncated + "}");
        }

        @Test
        @DisplayName("Should not truncate strings with exactly 100 characters")
        void shouldNotTruncateStringsWithExactly100Characters() {
            String exactString = "b".repeat(100);

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("content", exactString);
            ToolUse toolUse = ToolUse.of("id-1", "Write", input);
            PreToolContext context = mockPreToolContext(toolUse, InvokerType.MAIN_AGENT);

            hook.execute(context);

            ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
            verify(outputFormatter).displayToolCall(eq("Write"), inputCaptor.capture(), eq(InvokerType.MAIN_AGENT));

            assertThat(inputCaptor.getValue()).isEqualTo("{content: \"" + exactString + "\"}");
        }

        @Test
        @DisplayName("Should format non-string values without quotes")
        void shouldFormatNonStringValuesWithoutQuotes() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("count", 42);
            input.put("enabled", true);
            input.put("ratio", 3.14);
            ToolUse toolUse = mockToolUse("Example", input);
            PreToolContext context = mockPreToolContext(toolUse, InvokerType.MAIN_AGENT);

            hook.execute(context);

            ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
            verify(outputFormatter).displayToolCall(eq("Example"), inputCaptor.capture(), eq(InvokerType.MAIN_AGENT));

            assertThat(inputCaptor.getValue()).isEqualTo("{count: 42, enabled: true, ratio: 3.14}");
        }
    }

    private ToolUse mockToolUse(String name, Map<String, Object> input) {
        ToolUse toolUse = mock(ToolUse.class);
        when(toolUse.getName()).thenReturn(name);
        when(toolUse.getInput()).thenReturn(input);
        return toolUse;
    }

    private PreToolContext mockPreToolContext(ToolUse toolUse, InvokerType invokerType) {
        PreToolContext context = mock(PreToolContext.class);
        when(context.getCurrentToolUse()).thenReturn(toolUse);
        when(context.getInvokerType()).thenReturn(invokerType);
        return context;
    }
}
