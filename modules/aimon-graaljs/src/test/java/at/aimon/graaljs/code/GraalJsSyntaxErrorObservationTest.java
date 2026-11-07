package at.aimon.graaljs.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.CodeOfferKeys;
import at.aimon.core.tools.code.ExecuteCodeTool;

/**
 * What the model reads for a JavaScript syntax error, end to end through {@link ExecuteCodeTool} and the real GraalJS
 * engine (backlog TC-28): the error line names the error — {@code SyntaxError} and what was expected — instead of the
 * caret alone, and the source line and caret follow as an excerpt.
 */
@DisplayName("ExecuteCode over GraalJS — a syntax error's observation names the error")
class GraalJsSyntaxErrorObservationTest {

    private static GraalJsCodeExecutionEngine engine;

    @BeforeAll
    static void createEngine() {
        engine = GraalJsCodeExecutionEngine.create();
    }

    @AfterAll
    static void closeEngine() {
        engine.close();
    }

    @Test
    @DisplayName("the observation carries SyntaxError and Expected, not just the caret")
    void syntaxErrorObservation() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        final ExecuteCodeTool executeCode = new ExecuteCodeTool(List.of(engine), CodeExecutionLimits.defaults());
        registry.register(executeCode);
        registry.register(new EchoTool());
        final ToolOfferContext offer = ToolOfferContext.builder().registry(registry)
                .programmatic(ProgrammaticToolSettings.builder().tools(List.of("Echo")).build())
                .put(CodeOfferKeys.ENVIRONMENT_DESCRIPTOR, EnvironmentDescriptor.builder().workingDirectory("/w")
                        .isolation(EnvironmentIsolation.HOST).build())
                .build();
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.TOOL_CALL_DISPATCHER, new NoCalls())
                .put(ToolContextKeys.TOOL_OFFER_CONTEXT, offer).build();

        final ToolResult result = executeCode.execute(ToolInput.of(Map.of("code", "const x = 1 +;\n")), context);

        assertThat(result.isError()).isTrue();
        final String text = result.getContent();
        final String firstLine = text.lines().findFirst().orElse("");
        assertThat(firstLine).startsWith("ExecuteCode script error: ").contains("SyntaxError").contains("Expected");
        assertThat(firstLine.strip()).isNotEqualTo("ExecuteCode script error: ^");
        assertThat(text).contains("\n  const x = 1 +;\n").contains("[ExecuteCode (JavaScript): 0 tool calls");
    }

    /** A dispatcher the program never reaches: a syntax error runs nothing. */
    private static final class NoCalls implements ToolCallDispatcher {
        @Override
        public ToolDispatchResult dispatch(ToolUse toolUse, ToolCallOrigin origin, ToolContext context) {
            throw new AssertionError("a program with a syntax error made a call");
        }

        @Override
        public ToolCallDispatcher narrow(ToolRegistry registry, List<AllowedTool> allowedTools,
                ProgrammaticToolSettings programmatic) {
            return this;
        }
    }

    /** A read-only, code-callable tool, so the callable set is not empty. */
    private static final class EchoTool implements Tool {
        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of("Echo", "Echoes.", Map.of("type", "object"));
        }

        @Override
        public SideEffectLevel getSideEffectLevel() {
            return SideEffectLevel.READ_ONLY;
        }

        @Override
        public DestructiveBehavior getDestructiveBehavior() {
            return DestructiveBehavior.NON_DESTRUCTIVE;
        }

        @Override
        public DataReach dataReach() {
            return DataReach.ENVIRONMENT;
        }

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.CONCURRENT_SAFE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("echo");
        }
    }
}
