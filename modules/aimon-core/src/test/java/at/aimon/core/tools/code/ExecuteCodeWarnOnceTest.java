package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.orca.OrcaProviderDependencies;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Operator warnings are logged once per owner, not once per JVM (scope-model: state lives with its owner): the
 * provider owns the set for its own warning and for the plans of every tool it registers.
 */
@DisplayName("ExecuteCode warn-once — scoped to the provider")
class ExecuteCodeWarnOnceTest {

    private static final ProgrammaticToolSettings GREP = ProgrammaticToolSettings.builder().tools(List.of("Grep"))
            .build();

    @Test
    @DisplayName("two providers in one JVM each warn once about an agent with programmatic-tools and no engine")
    void eachProviderWarnsOnce() {
        final Agent agent = DefaultAgent.builder().name("same-agent").systemPrompt("p").programmatic(GREP).build();
        final OrcaToolProviderContext context = OrcaToolProviderContext.builder()
                .dependencies(OrcaProviderDependencies.builder().build()).agent(agent).build();
        final List<String> warnings = capture(ExecuteCodeToolProvider.class, () -> {
            for (int stack = 0; stack < 2; stack++) {
                final ExecuteCodeToolProvider provider = new ExecuteCodeToolProvider(List.of(),
                        CodeExecutionLimits.defaults());
                // A runtime rebuilt after eviction registers again: still once per provider.
                provider.registerTools(new DefaultToolRegistry(), context);
                provider.registerTools(new DefaultToolRegistry(), context);
            }
            return null;
        });
        assertThat(warnings).filteredOn(w -> w.contains("same-agent")).hasSize(2);
    }

    @Test
    @DisplayName("a plan warning through two tools of one provider is logged once; another provider logs it again")
    void planWarningsAreTheProviders() {
        final List<CodeExecutionEngine> engines = List.of(ScriptedEngine.javascript());
        final OrcaToolProviderContext context = OrcaToolProviderContext.builder()
                .dependencies(OrcaProviderDependencies.builder().build())
                .agent(DefaultAgent.builder().name("plan-agent").systemPrompt("p").build()).build();
        final List<String> warnings = capture(ExecuteCodePlan.class, () -> {
            for (int stack = 0; stack < 2; stack++) {
                final ExecuteCodeToolProvider provider = new ExecuteCodeToolProvider(engines,
                        CodeExecutionLimits.defaults());
                for (int runtime = 0; runtime < 2; runtime++) {
                    final DefaultToolRegistry registry = new DefaultToolRegistry();
                    provider.registerTools(registry, context);
                    registry.findByName(ExecuteCodeTool.TOOL_NAME).orElseThrow().describe(offer(registry));
                }
            }
            return null;
        });
        // "Grep" is not registered: left out of the callable set, so the plan is unavailable — two warnings per
        // provider, each once.
        assertThat(warnings).filteredOn(w -> w.contains("plan-agent") && w.contains("'Grep'")).hasSize(2);
        assertThat(warnings).filteredOn(w -> w.contains("plan-agent") && w.contains("ExecuteCode is not offered"))
                .hasSize(2);
    }

    private static ToolOfferContext offer(DefaultToolRegistry registry) {
        return ToolOfferContext.builder().registry(registry).programmatic(GREP).agentName("plan-agent").put(
                CodeOfferKeys.ENVIRONMENT_DESCRIPTOR,
                EnvironmentDescriptor.builder().workingDirectory("/w").isolation(EnvironmentIsolation.HOST).build())
                .build();
    }

    private static List<String> capture(Class<?> source, Supplier<Void> action) {
        final Logger logger = (Logger) LoggerFactory.getLogger(source);
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
