package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ProgrammaticEngineChoice;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.ToolGrade;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("ExecuteCodeTool — offer-time description, grade and availability")
class ExecuteCodeToolTest {

    private final DefaultToolRegistry registry = new DefaultToolRegistry();
    private final ScriptedEngine engine = ScriptedEngine.javascript();
    private final ExecuteCodeTool tool = new ExecuteCodeTool(List.of(engine), CodeExecutionLimits.defaults());

    ExecuteCodeToolTest() {
        registry.register(tool);
        registry.register(TestTool.reader("Read"));
        registry.register(TestTool.reader("Grep"));
        registry.register(TestTool.writer("Write"));
        registry.register(new TestTool("Rm", SideEffectLevel.MUTATING, DestructiveBehavior.DESTRUCTIVE,
                DataReach.ENVIRONMENT, true));
        registry.register(new TestTool("Task", SideEffectLevel.READ_ONLY, DestructiveBehavior.NON_DESTRUCTIVE,
                DataReach.EXTERNAL, false));
    }

    private ToolOfferContext offer(ProgrammaticToolSettings settings, EnvironmentIsolation isolation) {
        final ToolOfferContext.Builder builder = ToolOfferContext.builder().registry(registry).programmatic(settings);
        if (isolation != null) {
            builder.put(CodeOfferKeys.ENVIRONMENT_DESCRIPTOR,
                    EnvironmentDescriptor.builder().workingDirectory("/w").isolation(isolation).build());
        }
        return builder.build();
    }

    private static ProgrammaticToolSettings tools(String... names) {
        return ProgrammaticToolSettings.builder().tools(List.of(names)).build();
    }

    @Test
    @DisplayName("without programmatic-tools, or without an offer, ExecuteCode is not offerable and answers with its"
            + " most conservative grade")
    void notOfferableWithoutSettings() {
        final ToolDescriptor none = tool.describe(ToolOfferContext.none());
        assertThat(none.isOfferable()).isFalse();
        assertThat(none.getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        assertThat(none.getDataReach()).isEqualTo(DataReach.EXTERNAL);
        assertThat(tool.describe(offer(ProgrammaticToolSettings.none(), EnvironmentIsolation.HOST)).isOfferable())
                .isFalse();
        assertThat(offer(ProgrammaticToolSettings.none(), EnvironmentIsolation.HOST).offer())
                .extracting(ToolDescriptor::getName).doesNotContain(ExecuteCodeTool.TOOL_NAME);
    }

    @Test
    @DisplayName("auto picks JavaScript on a host environment and nothing on an undeclared one")
    void autoFollowsIsolation() {
        assertThat(tool.describe(offer(tools("Read"), EnvironmentIsolation.HOST)).isOfferable()).isTrue();
        assertThat(tool.describe(offer(tools("Read"), null)).isOfferable()).isFalse();
        // A sandbox means Python under auto, and no Python engine is assembled: no fallback to JavaScript.
        assertThat(tool.describe(offer(tools("Read"), EnvironmentIsolation.SANDBOXED)).isOfferable()).isFalse();
    }

    @Test
    @DisplayName("an explicit javascript choice works on a sandboxed or undeclared environment")
    void explicitJavascript() {
        final ProgrammaticToolSettings js = ProgrammaticToolSettings.builder().tools(List.of("Read"))
                .engine(ProgrammaticEngineChoice.JAVASCRIPT).build();
        assertThat(tool.describe(offer(js, EnvironmentIsolation.SANDBOXED)).isOfferable()).isTrue();
        assertThat(tool.describe(offer(js, null)).isOfferable()).isTrue();
    }

    @Test
    @DisplayName("an engine that is statically unavailable keeps ExecuteCode out of the offer")
    void staticallyUnavailableEngine() {
        engine.availability(EngineAvailability.unavailable("closed"));
        assertThat(tool.describe(offer(tools("Read"), EnvironmentIsolation.HOST)).isOfferable()).isFalse();
    }

    @Test
    @DisplayName("the callable set drops non-callable, mutating-destructive, unknown and self entries")
    void callableSetRules() {
        final ToolDescriptor descriptor = tool.describe(
                offer(tools("Read", "Rm", "Task", "Nope", ExecuteCodeTool.TOOL_NAME), EnvironmentIsolation.HOST));
        assertThat(descriptor.isOfferable()).isTrue();
        final String description = descriptor.getDefinition().getDescription();
        assertThat(description).contains("tools.Read(").doesNotContain("tools.Rm(").doesNotContain("tools.Task(")
                .doesNotContain("tools.Nope(").doesNotContain("tools.ExecuteCode(");
        assertThat(tool.describe(offer(tools("Rm", "Task"), EnvironmentIsolation.HOST)).isOfferable()).isFalse();
    }

    @Test
    @DisplayName("the grade is max(engine, callable set), approval delegated for the GraalJS-like engine")
    void gradeFolds() {
        final ToolDescriptor readOnly = tool.describe(offer(tools("Read", "Grep"), EnvironmentIsolation.HOST));
        assertThat(readOnly.getSideEffectLevel()).isEqualTo(SideEffectLevel.READ_ONLY);
        assertThat(readOnly.getDataReach()).isEqualTo(DataReach.ENVIRONMENT);
        assertThat(readOnly.isApprovalDelegated()).isTrue();
        assertThat(readOnly.isCodeCallable()).isFalse();

        final ToolDescriptor withWrite = tool.describe(offer(tools("Read", "Write"), EnvironmentIsolation.HOST));
        assertThat(withWrite.getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        assertThat(withWrite.getEffectiveDestructiveBehavior()).isEqualTo(DestructiveBehavior.NON_DESTRUCTIVE);
        assertThat(withWrite.getDataReach()).isEqualTo(DataReach.HOST);
    }

    @Test
    @DisplayName("an engine with another tool's approval key names its path and language (decision 11)")
    void approvalKeyAndVia() {
        final ScriptedEngine shellLike = new ScriptedEngine(CodeExecutionEngine.PYTHON,
                ToolGrade.builder().sideEffectLevel(SideEffectLevel.MUTATING)
                        .destructiveBehavior(DestructiveBehavior.DESTRUCTIVE).dataReach(DataReach.ENVIRONMENT)
                        .approvalKey("Bash").build());
        final ExecuteCodeTool python = new ExecuteCodeTool(List.of(shellLike), CodeExecutionLimits.defaults());
        final ToolDescriptor descriptor = python.describe(offer(tools("Read"), EnvironmentIsolation.SANDBOXED));
        assertThat(descriptor.isOfferable()).isTrue();
        assertThat(descriptor.getApprovalKey()).isEqualTo("Bash");
        assertThat(descriptor.getApprovalVia()).contains("ExecuteCode (Python)");
        assertThat(descriptor.getEffectiveDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
        assertThat(descriptor.isApprovalDelegated()).isFalse();
    }

    @Test
    @DisplayName("the Python description says the functions are predefined globals and the program is already in an"
            + " event loop; both say results are strings to parse (TC-27)")
    void descriptionSaysWhereFunctionsComeFrom() {
        final ExecuteCodeTool python = new ExecuteCodeTool(List.of(new ScriptedEngine(CodeExecutionEngine.PYTHON,
                ToolGrade.builder().sideEffectLevel(SideEffectLevel.MUTATING)
                        .destructiveBehavior(DestructiveBehavior.DESTRUCTIVE).dataReach(DataReach.ENVIRONMENT)
                        .approvalKey("Bash").build())),
                CodeExecutionLimits.defaults());
        final String pythonText = python.describe(offer(tools("Read"), EnvironmentIsolation.SANDBOXED)).getDefinition()
                .getDescription();
        assertThat(pythonText).contains("already defined as globals").contains("do not import them")
                .contains("never call asyncio.run()").contains("json.loads")
                .contains("Functions (already defined; do not import them):");
        final String javaScriptText = tool.describe(offer(tools("Read"), EnvironmentIsolation.HOST)).getDefinition()
                .getDescription();
        assertThat(javaScriptText).contains("JSON.parse").doesNotContain("asyncio");
    }

    @Test
    @DisplayName("a code-only tool is withheld from the direct offer and rendered with its arguments; a direct one is"
            + " rendered as a one-line pointer")
    void codeOnlyRendering() {
        final ProgrammaticToolSettings settings = ProgrammaticToolSettings.builder().tools(List.of("Read", "Grep"))
                .codeOnly(List.of("Grep")).build();
        final ToolOfferContext offer = offer(settings, EnvironmentIsolation.HOST);
        assertThat(offer.offer()).extracting(ToolDescriptor::getName).contains("Read", ExecuteCodeTool.TOOL_NAME)
                .doesNotContain("Grep");
        final String description = tool.describe(offer).getDefinition().getDescription();
        assertThat(description).contains("tools.Grep(args: { path: string }): Promise<string>")
                .contains("tools.Read(args: object)").contains("Arguments are those of the direct tool `Read`");
    }

    @Test
    @DisplayName("a mutating tool named in programmatic-only stays in the direct offer (decision 12 ④)")
    void mutatingCodeOnlyIsIgnored() {
        final ProgrammaticToolSettings settings = ProgrammaticToolSettings.builder().tools(List.of("Write"))
                .codeOnly(List.of("Write")).build();
        assertThat(offer(settings, EnvironmentIsolation.HOST).offer()).extracting(ToolDescriptor::getName)
                .contains("Write");
    }

    @Test
    @DisplayName("execute refuses without an offer, without a dispatcher, and on bad input — never throws")
    void executeRefusals() {
        final ToolResult noOffer = tool.execute(ToolInput.of("code", "x"), ToolContext.empty());
        assertThat(noOffer.isError()).isTrue();
        assertThat(noOffer.getContent()).contains("not available");

        final ToolContext noDispatcher = ToolContext.builder()
                .put(ToolContextKeys.TOOL_OFFER_CONTEXT, offer(tools("Read"), EnvironmentIsolation.HOST)).build();
        final ToolResult refused = tool.execute(ToolInput.of("code", "x"), noDispatcher);
        assertThat(refused.isError()).isTrue();
        assertThat(refused.getContent()).contains("no tool call dispatcher");

        final ToolResult missing = tool.execute(ToolInput.of(Map.of()), noDispatcher);
        assertThat(missing.isError()).isTrue();
        assertThat(missing.getContent()).startsWith("Invalid parameter");
    }

    @Test
    @DisplayName("the static definition carries the schema the model fills")
    void schema() {
        assertThat(tool.getDefinition().getName()).isEqualTo("ExecuteCode");
        assertThat(tool.getDefinition().getInputSchema()).containsEntry("additionalProperties", false)
                .containsEntry("required", List.of("code"));
    }
}
