package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.bootstrap.spec.AgentDescriptor;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.AimonAgentCustomizer;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.OfferFacts;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.programmatic.ToolGrade;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tools.code.ExecuteCodeToolProvider;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The stack is the one owner of the {@code ExecuteCode} provider in every assembly (review-2 of piece C): engines a
 * caller hands to {@link ToolSpec} are registered once, with no "no engine assembled" warning beside them; with
 * programmatic off the warning fires once per agent; nobody can add a second provider.
 */
@DisplayName("AimonStack — ExecuteCode has one owner")
class AimonStackExecuteCodeTest {

    private static final LlmClient STUB_LLM = new LlmClient() {
        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    };

    /** A caller-owned engine: records whether the stack closed it. */
    private static final class OwnedEngine implements CodeExecutionEngine {
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public String language() {
            return JAVASCRIPT;
        }

        @Override
        public EngineAvailability staticAvailability(OfferFacts facts) {
            return EngineAvailability.available();
        }

        @Override
        public ToolGrade intrinsicGrade() {
            return ToolGrade.builder().sideEffectLevel(SideEffectLevel.READ_ONLY)
                    .destructiveBehavior(DestructiveBehavior.NON_DESTRUCTIVE).dataReach(DataReach.ENVIRONMENT)
                    .approvalDelegated(true).build();
        }

        @Override
        public CodeExecutionOutcome execute(CodeExecutionRequest request, ToolCallBridge bridge, ToolContext context) {
            return CodeExecutionOutcome.builder().status(CodeExecutionStatus.COMPLETED).build();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static AgentBundle bundle(String name, ProgrammaticToolSettings programmatic) {
        return AgentBundle.builder().agent(DefaultAgent.builder().name(name).systemPrompt("You are a test agent.")
                .maxIterations(5).programmatic(programmatic).build()).build();
    }

    private static AimonStackSpec.Builder spec(Path workspace, String agent, ToolSpec tools) {
        return spec(workspace, agent, ProgrammaticToolSettings.builder().tools(List.of("Read")).build(), tools);
    }

    private static AimonStackSpec.Builder spec(Path workspace, String agent, ProgrammaticToolSettings programmatic,
            ToolSpec tools) {
        return AimonStackSpec.builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(STUB_LLM))
                .agent(AgentSpec.of(bundle(agent, programmatic))).tools(tools);
    }

    private static OrcaAgentRuntime runtimeOf(AimonStack stack, String agent) {
        return stack.agentRuntimeRegistry().get(AgentRuntimeId.fromName(agent)).map(OrcaAgentRuntime.class::cast)
                .orElseThrow();
    }

    @Test
    @DisplayName("caller-owned engines (the CLI's shared-holder engine): ExecuteCode registered, no 'no engine'"
            + " warning, and the stack leaves the engine open")
    void callerEnginesRegisterOnceWithoutWarning(@TempDir Path workspace) {
        final OwnedEngine engine = new OwnedEngine();
        final ToolSpec tools = ToolSpec.builder().codeExecutionEngines(List.of(engine))
                .programmaticLimits(CodeExecutionLimits.defaults()).build();
        final List<String> warnings = captureWarnings(() -> {
            try (AimonStack stack = AimonStackBuilder.build(spec(workspace, "ops-js-on", tools).build())) {
                assertThat(runtimeOf(stack, "ops-js-on").getToolRegistry().findByName(ExecuteCodeTool.TOOL_NAME))
                        .isPresent();
            }
        });
        assertThat(warnings).noneMatch(w -> w.contains("no code execution engine"));
        assertThat(engine.closed).isFalse();
    }

    @Test
    @DisplayName("engines assembled: ExecuteCode is registered once, and not offered to an agent that opens nothing"
            + " to code")
    void registeredOnceButNotOfferedWithoutSettings(@TempDir Path workspace) {
        final ToolSpec tools = ToolSpec.builder().codeExecutionEngines(List.of(new OwnedEngine()))
                .programmaticLimits(CodeExecutionLimits.defaults()).build();
        try (AimonStack stack = AimonStackBuilder
                .build(spec(workspace, "ops-no-code", ProgrammaticToolSettings.none(), tools).build())) {
            final OrcaAgentRuntime runtime = runtimeOf(stack, "ops-no-code");
            assertThat(runtime.getToolRegistry().findAll()).filteredOn(ExecuteCodeTool.class::isInstance).hasSize(1);
            // The offer as the main loop computes it for this agent.
            final List<String> offered = ToolOfferContext.builder().registry(runtime.getToolRegistry())
                    .programmatic(runtime.getAgent().getMetadata().getProgrammatic()).build().offer().stream()
                    .map(ToolDescriptor::getName).toList();
            assertThat(offered).isNotEmpty().doesNotContain(ExecuteCodeTool.TOOL_NAME);
        }
    }

    @Test
    @DisplayName("programmatic off: no ExecuteCode, and one warning naming the switches")
    void offWarnsOnce(@TempDir Path workspace) {
        final List<String> warnings = captureWarnings(() -> {
            try (AimonStack stack = AimonStackBuilder
                    .build(spec(workspace, "ops-js-off", ToolSpec.defaults()).build())) {
                assertThat(runtimeOf(stack, "ops-js-off").getToolRegistry().findByName(ExecuteCodeTool.TOOL_NAME))
                        .isEmpty();
            }
        });
        assertThat(warnings).filteredOn(w -> w.contains("no code execution engine")).singleElement()
                .satisfies(w -> assertThat(w).contains("ops-js-off").contains("programmatic.enableJs"));
    }

    @Test
    @DisplayName("a second ExecuteCode provider is refused — through ToolSpec and through a customizer")
    void secondOwnerRefused(@TempDir Path workspace) {
        final ExecuteCodeToolProvider extra = new ExecuteCodeToolProvider(List.of(), CodeExecutionLimits.defaults());
        assertThatThrownBy(() -> ToolSpec.builder().addProvider(extra).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("codeExecutionEngines");

        final AimonAgentCustomizer customizer = new AimonAgentCustomizer() {
            @Override
            public boolean supports(AgentDescriptor agent) {
                return true;
            }

            @Override
            public List<OrcaToolProvider> toolProviders(AgentDescriptor agent) {
                return List.of(extra);
            }
        };
        assertThatThrownBy(() -> AimonStackBuilder
                .build(spec(workspace, "ops-custom", ToolSpec.defaults()).agentCustomizer(customizer).build()))
                .hasStackTraceContaining("ExecuteCodeToolProvider");
    }

    private static List<String> captureWarnings(Runnable action) {
        final Logger logger = (Logger) LoggerFactory.getLogger(ExecuteCodeToolProvider.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
