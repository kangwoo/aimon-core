package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.toolinvocation.DefaultToolCallDispatcher;
import at.aimon.core.toolinvocation.SingleToolInvoker;
import at.aimon.core.tools.ToolContextKeys;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * When {@code ExecuteCode} cannot be offered, an agent's code-path settings must not make tools disappear, and the
 * operator must be told why (review-1 of piece C).
 */
@DisplayName("ExecuteCode unavailable — code-only tools stay direct, and the reason is logged")
class ExecuteCodeUnavailableTest {

    private static final ProgrammaticToolSettings GREP_CODE_ONLY = ProgrammaticToolSettings.builder()
            .tools(List.of("Grep")).codeOnly(List.of("Grep")).build();

    private final TestTool grep = TestTool.reader("Grep");

    private DefaultToolRegistry registry(List<CodeExecutionEngine> engines, boolean withExecuteCode) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        if (withExecuteCode) {
            registry.register(new ExecuteCodeTool(engines, CodeExecutionLimits.defaults()));
        }
        registry.register(grep);
        return registry;
    }

    private static ToolOfferContext offer(DefaultToolRegistry registry, EnvironmentIsolation isolation, String agent) {
        final ToolOfferContext.Builder builder = ToolOfferContext.builder().registry(registry)
                .programmatic(GREP_CODE_ONLY).agentName(agent);
        if (isolation != null) {
            builder.put(CodeOfferKeys.ENVIRONMENT_DESCRIPTOR,
                    EnvironmentDescriptor.builder().workingDirectory("/w").isolation(isolation).build());
        }
        return builder.build();
    }

    private static List<String> offered(ToolOfferContext offer) {
        return offer.offer().stream().map(ToolDescriptor::getName).toList();
    }

    @Test
    @DisplayName("with an engine and a host environment, the code-only tool is withheld and hosted by ExecuteCode")
    void withheldWhenHosted() {
        final ToolOfferContext offer = offer(registry(List.of(ScriptedEngine.javascript()), true),
                EnvironmentIsolation.HOST, "a");
        assertThat(offered(offer)).containsExactly(ExecuteCodeTool.TOOL_NAME);
    }

    @Test
    @DisplayName("no ExecuteCode registered (programmatic off in the assembly): the tool stays in the direct offer")
    void noExecuteCodeRegistered() {
        assertThat(offered(offer(registry(List.of(), false), EnvironmentIsolation.HOST, "a"))).containsExactly("Grep");
    }

    @Test
    @DisplayName("auto on an environment that does not declare its isolation: the tool stays in the direct offer")
    void undeclaredIsolation() {
        assertThat(offered(offer(registry(List.of(ScriptedEngine.javascript()), true), null, "a")))
                .containsExactly("Grep");
    }

    @Test
    @DisplayName("auto on a sandboxed environment with no python engine: the tool stays in the direct offer")
    void sandboxedWithoutPython() {
        assertThat(offered(
                offer(registry(List.of(ScriptedEngine.javascript()), true), EnvironmentIsolation.SANDBOXED, "a")))
                .containsExactly("Grep");
    }

    @Test
    @DisplayName("a direct call of the code-only tool runs when ExecuteCode is unavailable, and is refused when it is"
            + " available — the refusal reads the same source as the offer")
    void directRefusalFollowsTheOffer() {
        assertThat(dispatchGrep(registry(List.of(ScriptedEngine.javascript()), true), null).isError()).isFalse();
        final ToolDispatchResult refused = dispatchGrep(registry(List.of(ScriptedEngine.javascript()), true),
                EnvironmentIsolation.HOST);
        assertThat(refused.isError()).isTrue();
        assertThat(refused.getText()).contains("offered only to code");
    }

    private static ToolDispatchResult dispatchGrep(DefaultToolRegistry registry, EnvironmentIsolation isolation) {
        final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        try {
            final SingleToolInvoker invoker = new SingleToolInvoker(new DefaultToolExecutionManager(),
                    new DefaultHookExecutionManager());
            final DefaultToolCallDispatcher dispatcher = DefaultToolCallDispatcher.builder().invoker(invoker)
                    .invokerType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(new DefaultHookRegistry())
                    .executionAttributes(Map.of()).toolRegistry(registry).sessionRegistry(registry)
                    .allowedTools(List.of()).coordinator(coordinator).programmatic(ProgrammaticToolSettings.none())
                    .build();
            final ToolContext context = ToolContext.builder()
                    .put(ToolContextKeys.TOOL_OFFER_CONTEXT, offer(registry, isolation, "a")).build();
            return dispatcher.dispatch(ToolUse.of("t", "Grep", Map.of("path", "/p")), ToolCallOrigin.direct(), context);
        } finally {
            coordinator.close();
        }
    }

    @Test
    @DisplayName("the reason ExecuteCode is not offered is a WARN, once per agent and reason")
    void reasonIsLoggedOncePerAgent() {
        final List<String> warnings = captureWarnings(() -> {
            final DefaultToolRegistry registry = registry(List.of(ScriptedEngine.javascript()), true);
            offer(registry, null, "agent-undeclared-1").offer();
            offer(registry, null, "agent-undeclared-1").offer();
            offer(registry, null, "agent-undeclared-2").offer();
            return null;
        });
        assertThat(warnings).filteredOn(w -> w.contains("ExecuteCode is not offered")).hasSize(2)
                .allSatisfy(w -> assertThat(w).contains("does not declare"));
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("agent-undeclared-1"))
                .anySatisfy(w -> assertThat(w).contains("agent-undeclared-2"));
    }

    @Test
    @DisplayName("no engine of the chosen language is a WARN too")
    void noEngineIsLogged() {
        final List<String> warnings = captureWarnings(() -> {
            offer(registry(List.of(ScriptedEngine.javascript()), true), EnvironmentIsolation.SANDBOXED,
                    "agent-sandboxed").offer();
            return null;
        });
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("agent-sandboxed").contains("no python engine"));
    }

    @Test
    @DisplayName("TC-30: a ceiling that emptied the settings is reported as the unavailable reason, without a WARN")
    void ceilingReasonIsReported() {
        final DefaultToolRegistry registry = registry(List.of(ScriptedEngine.javascript()), true);
        final List<String> warnings = captureWarnings(() -> {
            final ToolOfferContext emptied = ToolOfferContext.builder().registry(registry)
                    .programmatic(ProgrammaticToolSettings.none()).programmaticCeilingReason("caller allows none")
                    .agentName("fork-emptied").build();
            assertThat(offered(emptied)).containsExactly("Grep");
            assertThat(emptied.getUnavailableReasons()).containsEntry(ExecuteCodeTool.TOOL_NAME, "caller allows none");
            final ToolOfferContext plain = ToolOfferContext.builder().registry(registry)
                    .programmatic(ProgrammaticToolSettings.none()).agentName("fork-plain").build();
            plain.offer();
            assertThat(plain.getUnavailableReasons()).isEmpty();
            return null;
        });
        assertThat(warnings).noneSatisfy(w -> assertThat(w).contains("fork-"));
    }

    private static List<String> captureWarnings(Supplier<Void> action) {
        final Logger logger = (Logger) LoggerFactory.getLogger(ExecuteCodePlan.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.get();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
