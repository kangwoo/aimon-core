package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.bootstrap.spec.AgentDescriptor;
import at.aimon.bootstrap.spec.AimonAgentCustomizer;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ShellEngineToolScope;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionEngineFactory;
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

/**
 * The starter's half of {@code ExecuteCode} assembly: {@code aimon.programmatic.enabled} hands engine discovery to the
 * stack, which registers {@code ExecuteCode} exactly once; an agent that opens nothing to code is not offered it; and
 * an {@link AimonAgentCustomizer} bean cannot add a second provider.
 *
 * <p>
 * The engine is discovered through {@code META-INF/services} in this module's test resources
 * ({@link TestEngineFactory}), so the path is the one a real engine module takes.
 */
@DisplayName("Starter — ExecuteCode assembly")
class AimonProgrammaticTest {

    private static final String AGENT = "test-agent";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(AimonLlmAutoConfiguration.class, AimonFileSystemAutoConfiguration.class,
                    AimonSessionAutoConfiguration.class, AimonSchedulingAutoConfiguration.class,
                    AimonKnowledgeAutoConfiguration.class, AimonMemoryAutoConfiguration.class,
                    AimonObservabilityAutoConfiguration.class, AimonAutoConfiguration.class));

    private ApplicationContextRunner minimal(Path workspace) {
        return runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.llm.api-key=test-key",
                "aimon.agent-defaults.default-agent=" + AGENT);
    }

    @Test
    @DisplayName("enabled: ExecuteCode is registered once, and not offered to an agent that opens nothing to code")
    void enabledRegistersOnceAndDoesNotOfferToAnAgentWithoutSettings(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.programmatic.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            final AimonStack stack = ctx.getBean(AimonStack.class);
            final OrcaAgentRuntime runtime = stack.runtimes().get(stack.primaryRuntimeId());
            assertThat(runtime.getToolRegistry().findAll()).filteredOn(t -> t instanceof ExecuteCodeTool).hasSize(1);
            // The offer as the main loop computes it for this agent, whose definition sets no programmatic-tools.
            final List<String> offered = ToolOfferContext.builder().registry(runtime.getToolRegistry())
                    .programmatic(runtime.getAgent().getMetadata().getProgrammatic()).build().offer().stream()
                    .map(ToolDescriptor::getName).toList();
            assertThat(offered).isNotEmpty().doesNotContain(ExecuteCodeTool.TOOL_NAME);
        });
    }

    @Test
    @DisplayName("off by default: no engine is discovered and ExecuteCode is not registered")
    void offByDefault(@TempDir Path workspace) {
        minimal(workspace).run(ctx -> {
            final AimonStack stack = ctx.getBean(AimonStack.class);
            final OrcaAgentRuntime runtime = stack.runtimes().get(stack.primaryRuntimeId());
            assertThat(runtime.getToolRegistry().findByName(ExecuteCodeTool.TOOL_NAME)).isEmpty();
        });
    }

    @Test
    @DisplayName("aimon.programmatic.shell-engine-tool-scope reaches the stack's ToolSpec; environment by default")
    void shellEngineToolScope(@TempDir Path workspace) {
        minimal(workspace).run(ctx -> assertThat(ctx.getBean(AimonStackSpec.class).getTools().getShellEngineToolScope())
                .isEqualTo(ShellEngineToolScope.ENVIRONMENT));
        minimal(workspace)
                .withPropertyValues("aimon.programmatic.enabled=true",
                        "aimon.programmatic.shell-engine-tool-scope=host")
                .run(ctx -> assertThat(ctx.getBean(AimonStackSpec.class).getTools().getShellEngineToolScope())
                        .isEqualTo(ShellEngineToolScope.HOST));
        minimal(workspace).withPropertyValues("aimon.programmatic.shell-engine-tool-scope=everything")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("a customizer bean contributing an ExecuteCodeToolProvider is refused")
    void customizerProviderRefused(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.programmatic.enabled=true")
                .withUserConfiguration(SecondProviderConfiguration.class).run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("ExecuteCodeToolProvider"));
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondProviderConfiguration {
        @Bean
        AimonAgentCustomizer secondExecuteCodeProvider() {
            return new AimonAgentCustomizer() {
                @Override
                public boolean supports(AgentDescriptor agent) {
                    return true;
                }

                @Override
                public List<OrcaToolProvider> toolProviders(AgentDescriptor agent) {
                    return List.of(new ExecuteCodeToolProvider(List.of(), CodeExecutionLimits.defaults()));
                }
            };
        }
    }

    /** Discovered through this module's test {@code META-INF/services}; only ever offered, never run. */
    public static final class TestEngineFactory implements CodeExecutionEngineFactory {

        @Override
        public String language() {
            return CodeExecutionEngine.JAVASCRIPT;
        }

        @Override
        public CodeExecutionEngine create() {
            return new CodeExecutionEngine() {
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
                public CodeExecutionOutcome execute(CodeExecutionRequest request, ToolCallBridge bridge,
                        ToolContext context) {
                    return CodeExecutionOutcome.builder().status(CodeExecutionStatus.COMPLETED).build();
                }
            };
        }
    }
}
