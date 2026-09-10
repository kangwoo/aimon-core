package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.runtime.AgentRuntimeLease;
import at.aimon.bootstrap.spec.AgentDescriptor;
import at.aimon.bootstrap.spec.AimonAgentCustomizer;
import at.aimon.bootstrap.spec.CredentialStoreFactory;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.input.ImageInput;
import at.aimon.core.agent.input.MultimodalInput;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.credential.InMemoryCredentialStore;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
import at.aimon.core.llms.anthropic.AnthropicConfig;
import at.aimon.core.llms.anthropic.AnthropicLlmClient;
import at.aimon.core.llms.anthropic.AnthropicThinkingDisplay;
import at.aimon.core.llms.anthropic.AnthropicThinkingMode;
import at.aimon.core.llms.openai.OpenAILlmClient;
import at.aimon.core.llms.openai.OpenAiReasoningSummary;
import at.aimon.session.routing.SubmitRequest;
import at.aimon.spring.boot.AimonAgents;
import at.aimon.spring.boot.AimonDisabledException;
import at.aimon.spring.boot.AimonSessions;
import at.aimon.spring.boot.DisabledAimonAgents;
import at.aimon.spring.boot.DisabledAimonSessions;

/**
 * Slice tests for the four autoconfigurations.
 *
 * <p>
 * {@code ApplicationContextRunner} rather than {@code @SpringBootTest}: these stay in the fast unit tier, and
 * each case needs a context built from a different property set, which a shared cached context cannot give.
 * Every case here assembles a real stack — real agent runtime, real session router, real teardown — and the
 * runner closes it, so a leak in assembly shows up as a hanging build rather than passing quietly.
 *
 * <p>
 * No turn is ever run. The vendor clients are constructed with throwaway keys and never called.
 */
class AimonAutoConfigurationTest {

    private static final String AGENT = "test-agent";

    private static final String SECOND_AGENT = "second-agent";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(AimonLlmAutoConfiguration.class, AimonFileSystemAutoConfiguration.class,
                    AimonSessionAutoConfiguration.class, AimonSchedulingAutoConfiguration.class,
                    AimonKnowledgeAutoConfiguration.class, AimonMemoryAutoConfiguration.class,
                    AimonObservabilityAutoConfiguration.class, AimonAutoConfiguration.class));

    private ApplicationContextRunner minimal(Path workspace) {
        return runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.llm.api-key=test-key",
                "aimon.agent-defaults.default-agent=" + AGENT);
    }

    /**
     * Two agents from two bundles, one of them named as the default and carrying a property.
     *
     * <p>
     * Built from {@code runner} rather than {@code minimal}, because {@code minimal} names a default that is not
     * one of these two — and a default naming an undeclared agent is a configuration the properties bean
     * rejects.
     */
    private ApplicationContextRunner twoAgents(Path workspace) {
        return runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.llm.api-key=test-key",
                "aimon.agents.ops.bundle=" + AGENT, "aimon.agents.ops.properties.region=eu-west-1",
                "aimon.agents.inquiry.bundle=" + SECOND_AGENT, "aimon.agent-defaults.default-agent=ops");
    }

    @Test
    @DisplayName("the documented minimum starts a stack and publishes the facade")
    void documentedMinimumStarts(@TempDir Path workspace) {
        minimal(workspace).run(ctx -> {
            assertThat(ctx).hasSingleBean(AimonSessions.class).hasSingleBean(AimonStack.class);
            assertThat(ctx).getBean(LlmClient.class).isInstanceOf(AnthropicLlmClient.class);
            assertThat(ctx.getBean(AimonStack.class).primaryRuntimeId().toString()).isEqualTo("agent:" + AGENT);
        });
    }

    @Test
    @DisplayName("an empty configuration fails by naming the property that would fix it")
    void emptyConfigurationNamesTheProperty() {
        // Not "no beans" — a starter that silently produces nothing is indistinguishable from one that is
        // broken. The first thing a user with an empty configuration needs is the name of the first property.
        runner.run(ctx -> assertThat(ctx).hasFailed().getFailure()
                .hasStackTraceContaining(AimonProperties.WORKSPACE_ROOT));
    }

    @Test
    @DisplayName("a configuration with a workspace but no agent names both ways to declare one")
    void missingAgentNamesTheProperty(@TempDir Path workspace) {
        runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.llm.api-key=test-key")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.DEFAULT_AGENT)
                        .hasStackTraceContaining(AimonProperties.AGENTS));
    }

    @Test
    @DisplayName("the provider selector picks OpenAI")
    void providerSelectorPicksOpenAi(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-4o")
                .run(ctx -> assertThat(ctx).getBean(LlmClient.class).isInstanceOf(OpenAILlmClient.class));
    }

    @Test
    @DisplayName("an OpenAI deployment with no model is rejected by property name")
    void starterRejectsOpenAiWithNoModel(@TempDir Path workspace) {
        // OpenAIConfig has no default model any more (#45), and a stack trace saying "Model is required" without
        // naming the property would be a worse failure than the stale default it replaces.
        minimal(workspace).withPropertyValues("aimon.llm.provider=openai")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(AimonProperties.LLM_MODEL)
                        .hasStackTraceContaining(AimonProperties.LLM_PROVIDER));
    }

    @Test
    @DisplayName("a missing vendor module backs the branch off instead of failing to load it")
    void missingVendorModuleBacksOff(@TempDir Path workspace) {
        // The nested configuration references AnthropicLlmClient in a @Bean method body. If @ConditionalOnClass
        // were evaluated by loading the class rather than by reading bytecode, this would be a
        // NoClassDefFoundError instead of the configuration failure below.
        minimal(workspace).withClassLoader(new FilteredClassLoader(AnthropicLlmClient.class)).run(ctx -> {
            assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(AimonProperties.LLM_PROVIDER)
                    .hasStackTraceContaining("aimon-llm-anthropic");
            assertThat(stackTraceOf(ctx.getStartupFailure())).doesNotContain("NoClassDefFoundError");
        });
    }

    @Test
    @DisplayName("a missing OpenAI module backs its branch off too, without loading OpenAIConfig")
    void missingOpenAiModuleBacksOff(@TempDir Path workspace) {
        // The mirror of the test above, and it exists for a reason the Anthropic side does not cover: the OpenAI
        // branch now declares a method whose return type is OpenAIConfig. Spring calls getDeclaredMethods() on a
        // configuration class while post-processing it, which loads every declared method's return type -- so the
        // same method on the enclosing AimonLlmAutoConfiguration would ask the classloader for a vendor type in a
        // deployment that carries only the other vendor. Inside the @ConditionalOnClass-guarded nested class it is
        // never inspected. Without this case that regression is invisible: the existing back-off test hides the
        // opposite vendor.
        minimal(workspace).withPropertyValues("aimon.llm.provider=anthropic")
                .withClassLoader(new FilteredClassLoader(OpenAILlmClient.class)).run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).getBean(LlmClient.class).isInstanceOf(AnthropicLlmClient.class);
                });
    }

    @Test
    @DisplayName("a capability declaration reaches the OpenAI client and keeps the built-in rows")
    void modelCapabilityDeclarationsReachTheOpenAiClient(@TempDir Path workspace) {
        // The starter half of the seam. The raw-request assertion this chain exists for cannot run here -- the SDK
        // that owns JsonMissing is an implementation dependency of aimon-llm-openai and is not on this module's
        // compile classpath -- so what is bound here is "the declaration arrives in the config the client is built
        // from", and OpenAILlmClientModelCapabilityTest carries it the rest of the way from the same translator.
        minimal(workspace).withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=prod-assistant",
                "aimon.llm.model-capabilities.prod-assistant.supports-sampling-parameters=false").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final ModelCapabilityRegistry registry = AimonLlmAutoConfiguration.OpenAiConfiguration
                            .openAiConfig(ctx.getBean(AimonProperties.class).getLlm()).getModelCapabilityRegistry();
                    assertThat(registry.resolve("prod-assistant").supportsSamplingParameters()).isFalse();
                    // One name per built-in SHAPE: gpt-5-mini lands on a prefix row, the other two on exact ones.
                    // Round 9 gave gpt-5.6-terra an exact row, so without the first line no prefix row is sampled.
                    assertThat(registry.resolve("gpt-5-mini"))
                            .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5-mini"));
                    assertThat(registry.resolve("gpt-5.6-terra"))
                            .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5.6-terra"));
                    assertThat(registry.resolve("o3-mini"))
                            .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("o3-mini"));
                });
    }

    @Test
    @DisplayName("no declaration leaves the config on the shipped default registry")
    void noDeclarationLeavesTheDefaultRegistry(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-4o").run(ctx -> {
            final ModelCapabilityRegistry registry = AimonLlmAutoConfiguration.OpenAiConfiguration
                    .openAiConfig(ctx.getBean(AimonProperties.class).getLlm()).getModelCapabilityRegistry();
            assertThat(registry.resolve("gpt-5-mini"))
                    .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5-mini"));
            assertThat(registry.resolve("gpt-5.6-terra"))
                    .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5.6-terra"));
        });
    }

    @Test
    @DisplayName("a capability declaration reaches the Anthropic client and keeps the built-in rows")
    void modelCapabilityDeclarationsReachTheAnthropicClient(@TempDir Path workspace) {
        // This branch used to refuse the block by name, because only the OpenAI client read the registry. Both read
        // it now, so what keeps the shared aimon.llm.* namespace honest is that both branches consume it -- and the
        // refusal is deleted rather than reworded, because it named a state that no longer exists.
        minimal(workspace).withPropertyValues("aimon.llm.model=prod-assistant",
                "aimon.llm.model-capabilities.prod-assistant.supports-sampling-parameters=false").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final ModelCapabilityRegistry registry = AimonLlmAutoConfiguration.AnthropicConfiguration
                            .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm()).getModelCapabilityRegistry();
                    assertThat(registry.resolve("prod-assistant").supportsSamplingParameters()).isFalse();
                    assertThat(registry.resolve("claude-opus-5"))
                            .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("claude-opus-5"));
                });
    }

    @Test
    @DisplayName("aimon.llm.reasoning-effort reaches BOTH vendor configs")
    void theSharedReasoningEffortKeyReachesBothBranches(@TempDir Path workspace) {
        // What the shared namespace means, as an assertion over the pair: a key under aimon.llm.* that only one
        // branch consumed would be a lie for half its users, and that is exactly the state the aimon.llm.anthropic.*
        // subtree exists to keep this key out of. Written as one test because the claim is about both at once.
        minimal(workspace).withPropertyValues("aimon.llm.reasoning-effort=high").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            final AimonProperties.Llm llm = ctx.getBean(AimonProperties.class).getLlm();
            assertThat(AimonLlmAutoConfiguration.AnthropicConfiguration.anthropicConfig(llm).getReasoningEffort())
                    .contains(ReasoningEffort.HIGH);
        });

        minimal(workspace).withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-5.1",
                "aimon.llm.reasoning-effort=high").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(AimonLlmAutoConfiguration.OpenAiConfiguration
                            .openAiConfig(ctx.getBean(AimonProperties.class).getLlm()).getReasoningEffort())
                            .contains(ReasoningEffort.HIGH);
                });
    }

    @Test
    @DisplayName("no reasoning-effort leaves both vendor configs as they were")
    void noSharedReasoningEffortChangesNeitherBranch(@TempDir Path workspace) {
        minimal(workspace).run(ctx -> assertThat(AimonLlmAutoConfiguration.AnthropicConfiguration
                .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm()).getReasoningEffort()).isEmpty());

        minimal(workspace).withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-5.1")
                .run(ctx -> assertThat(AimonLlmAutoConfiguration.OpenAiConfiguration
                        .openAiConfig(ctx.getBean(AimonProperties.class).getLlm()).getReasoningEffort()).isEmpty());
    }

    @Test
    @DisplayName("the four anthropic thinking keys reach the vendor config")
    void thinkingKeysReachTheAnthropicClient(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.llm.anthropic.thinking-mode=extended",
                "aimon.llm.anthropic.thinking-budget-tokens=4000", "aimon.llm.anthropic.replay-thinking-blocks=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AnthropicConfig config = AimonLlmAutoConfiguration.AnthropicConfiguration
                            .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm());
                    assertThat(config.getThinkingMode()).isEqualTo(AnthropicThinkingMode.EXTENDED);
                    assertThat(config.getThinkingBudgetTokens()).isEqualTo(4000);
                    assertThat(config.isReplayThinkingBlocks()).isFalse();
                });

        // The display key rides the other dialect, so it gets its own context rather than an illegal combination.
        minimal(workspace).withPropertyValues("aimon.llm.anthropic.thinking-mode=adaptive",
                "aimon.llm.anthropic.thinking-display=summarized").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(AimonLlmAutoConfiguration.AnthropicConfiguration
                            .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm()).getThinkingDisplay())
                            .contains(AnthropicThinkingDisplay.SUMMARIZED);
                });
    }

    @Test
    @DisplayName("every thinking display binds, in any case")
    void everyThinkingDisplayBinds(@TempDir Path workspace) {
        // Sourced from values() for the same reason the mode loop below is, and asserted rather than inherited
        // because the property is a String here and the fold is this module's own.
        for (AnthropicThinkingDisplay display : AnthropicThinkingDisplay.values()) {
            for (String written : new String[]{display.name(), display.name().toLowerCase(java.util.Locale.ROOT)}) {
                minimal(workspace).withPropertyValues("aimon.llm.anthropic.thinking-display=" + written)
                        .run(ctx -> assertThat(AimonLlmAutoConfiguration.AnthropicConfiguration
                                .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm()).getThinkingDisplay())
                                .as("thinking-display written as `%s`", written).contains(display));
            }
        }
    }

    @Test
    @DisplayName("an unusable thinking display fails naming the property and the accepted spellings")
    void anUnusableThinkingDisplayIsRefused(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.llm.anthropic.thinking-display=verbose")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_ANTHROPIC_THINKING_DISPLAY)
                        .hasStackTraceContaining("summarized"));
    }

    @Test
    @DisplayName("the openai reasoning summary reaches the vendor config, in any case")
    void reasoningSummaryReachesTheOpenAiClient(@TempDir Path workspace) {
        for (OpenAiReasoningSummary summary : OpenAiReasoningSummary.values()) {
            for (String written : new String[]{summary.name(), summary.name().toLowerCase(java.util.Locale.ROOT)}) {
                minimal(workspace)
                        .withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-5.1",
                                "aimon.llm.openai.reasoning-summary=" + written)
                        .run(ctx -> assertThat(AimonLlmAutoConfiguration.OpenAiConfiguration
                                .openAiConfig(ctx.getBean(AimonProperties.class).getLlm()).getReasoningSummary())
                                .as("reasoning-summary written as `%s`", written).contains(summary));
            }
        }
    }

    @Test
    @DisplayName("an unusable reasoning summary fails naming the property and the accepted spellings")
    void anUnusableReasoningSummaryIsRefused(@TempDir Path workspace) {
        minimal(workspace)
                .withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-5.1",
                        "aimon.llm.openai.reasoning-summary=verbose")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_OPENAI_REASONING_SUMMARY)
                        .hasStackTraceContaining("concise"));
    }

    @Test
    @DisplayName("an openai block under the anthropic provider is refused, naming both properties")
    void anOpenAiBlockUnderAnthropicIsRefused(@TempDir Path workspace) {
        // The mirror of the anthropic-under-openai refusal, which could not exist until this round opened
        // aimon.llm.openai.*. The Anthropic branch is the default, so no provider property is needed.
        minimal(workspace).withPropertyValues("aimon.llm.openai.reasoning-summary=auto")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(AimonProperties.LLM_OPENAI)
                        .hasStackTraceContaining(AimonProperties.LLM_PROVIDER));
    }

    @Test
    @DisplayName("the anthropic refusal survives the OpenAI module being absent")
    void theAnthropicRefusalDoesNotNeedTheOpenAiModule(@TempDir Path workspace) {
        // The reason AimonProperties.Llm.OpenAi's reasoning-summary is a String and its isEmpty() reads a field
        // rather than calling a vendor type: on this classpath OpenAiReasoningSummary does not exist.
        minimal(workspace).withClassLoader(new FilteredClassLoader("at.aimon.core.llms.openai"))
                .withPropertyValues("aimon.llm.openai.reasoning-summary=auto")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(AimonProperties.LLM_OPENAI)
                        .hasStackTraceContaining(AimonProperties.LLM_PROVIDER));
    }

    @Test
    @DisplayName("an empty openai block does not trip the anthropic branch")
    void anEmptyOpenAiBlockDoesNotTripTheAnthropicBranch(@TempDir Path workspace) {
        minimal(workspace).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("every thinking mode binds, in any case")
    void everyThinkingModeBinds(@TempDir Path workspace) {
        // Sourced from values() so a fifth constant fails here rather than going untested. The fold is this
        // module's own -- the property is a String -- so unlike the CLI it has to be asserted, not inherited.
        for (AnthropicThinkingMode mode : AnthropicThinkingMode.values()) {
            for (String written : new String[]{mode.name(), mode.name().toLowerCase(java.util.Locale.ROOT)}) {
                final List<String> properties = new ArrayList<>();
                properties.add("aimon.llm.anthropic.thinking-mode=" + written);
                if (mode == AnthropicThinkingMode.EXTENDED) {
                    properties.add("aimon.llm.anthropic.thinking-budget-tokens=2048");
                }
                minimal(workspace).withPropertyValues(properties.toArray(new String[0]))
                        .run(ctx -> assertThat(AimonLlmAutoConfiguration.AnthropicConfiguration
                                .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm()).getThinkingMode())
                                .as("thinking-mode written as `%s`", written).isEqualTo(mode));
            }
        }
    }

    @Test
    @DisplayName("nothing set leaves the vendor defaults, so the request is what it was")
    void noThinkingKeysIsTodaysConfig(@TempDir Path workspace) {
        // Criterion 5 of the issue, as one equality rather than three getters: AnthropicConfig.equals covers every
        // value an operator can write, so "equal to the config built with no keys" says no setter ran.
        minimal(workspace).run(ctx -> {
            final AnthropicConfig config = AimonLlmAutoConfiguration.AnthropicConfiguration
                    .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm());
            assertThat(config.getThinkingMode()).isEqualTo(AnthropicThinkingMode.OFF);
            assertThat(config.getThinkingBudgetTokens()).isNull();
            assertThat(config.getThinkingDisplay()).isEmpty();
            assertThat(config.isReplayThinkingBlocks()).isTrue();
        });
    }

    @Test
    @DisplayName("an unusable thinking mode fails naming the property and the accepted spellings")
    void anUnusableThinkingModeIsRefused(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.llm.anthropic.thinking-mode=adaptiv")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_ANTHROPIC_THINKING_MODE)
                        .hasStackTraceContaining("adaptive"));
    }

    @Test
    @DisplayName("a thinking mode YAML turned into a boolean is refused with the quoting remedy")
    void aYamlBooleanThinkingModeNamesTheQuotingRemedy(@TempDir Path workspace) {
        // `off` is a YAML 1.1 boolean, so an unquoted one in an application.yml arrives here as the string
        // "false" -- Boot loads it as Boolean.FALSE and converts. Nothing in a list of four spellings tells the
        // operator that quoting is the fix, so the message says it. The CLI answers the same collision by reading
        // the parser's original scalar, which this surface never receives.
        minimal(workspace).withPropertyValues("aimon.llm.anthropic.thinking-mode=false")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_ANTHROPIC_THINKING_MODE)
                        .hasStackTraceContaining("quoted"));
    }

    @Test
    @DisplayName("a budget without the extended mode is refused, naming the block")
    void aBudgetOutsideExtendedIsRefused(@TempDir Path workspace) {
        // Two shapes, one rule, and the rule is AnthropicConfig's. Under auto the dialect is not known until the
        // request is built; with nothing set the mode is OFF and the number reaches nothing.
        minimal(workspace)
                .withPropertyValues("aimon.llm.anthropic.thinking-mode=auto",
                        "aimon.llm.anthropic.thinking-budget-tokens=4000")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_ANTHROPIC).hasStackTraceContaining("EXTENDED"));

        minimal(workspace).withPropertyValues("aimon.llm.anthropic.thinking-budget-tokens=4000")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_ANTHROPIC).hasStackTraceContaining("OFF"));
    }

    @Test
    @DisplayName("a budget below the API floor is refused, naming the block")
    void aBudgetBelowTheFloorIsRefused(@TempDir Path workspace) {
        minimal(workspace)
                .withPropertyValues("aimon.llm.anthropic.thinking-mode=extended",
                        "aimon.llm.anthropic.thinking-budget-tokens=512")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_ANTHROPIC).hasStackTraceContaining("1024"));
    }

    @Test
    @DisplayName("an anthropic block under the openai provider is refused, naming both properties")
    void anAnthropicBlockUnderOpenAiIsRefused(@TempDir Path workspace) {
        minimal(workspace)
                .withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-4o",
                        "aimon.llm.anthropic.thinking-mode=auto")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_ANTHROPIC)
                        .hasStackTraceContaining(AimonProperties.LLM_PROVIDER));
    }

    @Test
    @DisplayName("the openai refusal survives the Anthropic module being absent")
    void theOpenAiRefusalDoesNotNeedTheAnthropicModule(@TempDir Path workspace) {
        // The assertion that would have gone red had the property been typed as the vendor enum. On this
        // classpath AnthropicThinkingMode does not exist, so a signature naming it anywhere the binder or this
        // refusal walks would be a NoClassDefFoundError instead of a message about a property.
        minimal(workspace).withClassLoader(new FilteredClassLoader("at.aimon.core.llms.anthropic"))
                .withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-4o",
                        "aimon.llm.anthropic.thinking-mode=auto")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_ANTHROPIC)
                        .hasStackTraceContaining(AimonProperties.LLM_PROVIDER));
    }

    @Test
    @DisplayName("an empty anthropic block does not trip the openai branch")
    void anEmptyAnthropicBlockDoesNotTripTheOpenAiBranch(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-4o")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("no AimonProperties signature names a vendor LLM type")
    void noPropertiesSignatureNamesAVendorType() {
        // The invariant behind the String-typed thinking-mode, stated as a rule rather than as its one instance.
        // Both vendor modules are compileOnly here, and Spring's JavaBeanBinder calls getDeclaredMethods() on
        // every bean it binds -- which resolves the signatures it finds. Measured: with the vendor class absent,
        // reading a vendor-typed field for a null check costs nothing, but getDeclaredMethods() on a class that
        // declares a vendor-typed accessor throws NoClassDefFoundError, an Error that Boot's BindException
        // wrapping does not catch. The allow-list is by package rather than by exclusion so that the other two
        // compileOnly families (org.quartz, the actuator) are covered by the same assertion -- but it needs the
        // one explicit deny below to keep covering the family it was written for. Both vendor modules live under
        // at.aimon.core.llms, which "at.aimon.core." swallows, so the allow-list alone would pass a vendor-typed
        // accessor: exactly the case this test is named after.
        final List<String> allowedPrefixes = List.of("java.", "at.aimon.core.", "at.aimon.bootstrap.",
                "at.aimon.session.routing.", "at.aimon.spring.boot.");
        final String vendorPrefix = "at.aimon.core.llms.";
        final List<String> offenders = new ArrayList<>();
        final List<Class<?>> pending = new ArrayList<>(List.of(AimonProperties.class));
        final Set<Class<?>> seen = new java.util.LinkedHashSet<>();
        while (!pending.isEmpty()) {
            final Class<?> type = pending.remove(0);
            if (!seen.add(type)) {
                continue;
            }
            Collections.addAll(pending, type.getDeclaredClasses());
            for (Method method : type.getDeclaredMethods()) {
                final List<Class<?>> named = new ArrayList<>(List.of(method.getParameterTypes()));
                named.add(method.getReturnType());
                for (Class<?> signatureType : named) {
                    Class<?> component = signatureType;
                    while (component.isArray()) {
                        component = component.getComponentType();
                    }
                    if (component.isPrimitive()) {
                        continue;
                    }
                    final String name = component.getName();
                    if (name.startsWith(vendorPrefix) || allowedPrefixes.stream().noneMatch(name::startsWith)) {
                        offenders.add(type.getSimpleName() + "." + method.getName() + " -> " + name);
                    }
                }
            }
        }
        assertThat(offenders).as("AimonProperties signatures naming a type outside the api dependencies").isEmpty();
    }

    @Test
    @DisplayName("no declaration leaves the Anthropic config on the shipped default registry")
    void noDeclarationLeavesTheAnthropicDefaultRegistry(@TempDir Path workspace) {
        minimal(workspace).run(ctx -> {
            final ModelCapabilityRegistry registry = AimonLlmAutoConfiguration.AnthropicConfiguration
                    .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm()).getModelCapabilityRegistry();
            assertThat(registry.resolve("claude-opus-5"))
                    .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("claude-opus-5"));
        });
    }

    @Test
    @DisplayName("a misspelled provider is reported as a property, not as a missing bean")
    void misspelledProviderNamesTheProperty(@TempDir Path workspace) {
        // The cost of aimon.llm.provider being a String is that a typo cannot fail at binding time: it simply
        // matches no branch. What Spring then reports is a missing LlmClient — true, and no help at all in
        // finding the two transposed letters.
        minimal(workspace).withPropertyValues("aimon.llm.provider=anthropci")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_PROVIDER + "=anthropci")
                        .hasStackTraceContaining(AimonProperties.PROVIDER_ANTHROPIC));
    }

    @Test
    @DisplayName("provider=none without a bean says which half of the bargain is missing")
    void providerNoneWithoutBeanNamesTheProperty(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.llm.provider=none")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_PROVIDER + "=" + AimonProperties.PROVIDER_NONE));
    }

    @Test
    @DisplayName("provider=none with a bean is the supported way to bring your own client")
    void providerNoneWithApplicationBeanStarts(@TempDir Path workspace) {
        // Distinct from simply defining the bean and leaving the provider alone: `none` is how a configuration
        // says so out loud, which is what makes the failure above possible when the bean later disappears.
        runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.agent-defaults.default-agent=" + AGENT,
                "aimon.llm.provider=none").withUserConfiguration(ApplicationLlmConfiguration.class).run(ctx -> {
                    assertThat(ctx).hasSingleBean(AimonSessions.class);
                    assertThat(ctx).getBean(LlmClient.class).isSameAs(ApplicationLlmConfiguration.INSTANCE);
                });
    }

    @Test
    @DisplayName("an application-defined LlmClient wins and no vendor client is built")
    void applicationLlmClientWins(@TempDir Path workspace) {
        minimal(workspace).withUserConfiguration(ApplicationLlmConfiguration.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(LlmClient.class);
            assertThat(ctx).getBean(LlmClient.class).isSameAs(ApplicationLlmConfiguration.INSTANCE);
        });
    }

    @Test
    @DisplayName("an application-defined LlmClient makes the api key unnecessary")
    void applicationLlmClientNeedsNoApiKey(@TempDir Path workspace) {
        // The credential check lives in the slice, not in the properties bean, precisely so this configuration
        // is not rejected for missing a key it has no use for.
        runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.agent-defaults.default-agent=" + AGENT)
                .withUserConfiguration(ApplicationLlmConfiguration.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(AimonSessions.class));
    }

    @Test
    @DisplayName("a missing api key names the property, not the vendor builder's argument")
    void missingApiKeyNamesTheProperty(@TempDir Path workspace) {
        runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.agent-defaults.default-agent=" + AGENT)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_API_KEY));
    }

    @Test
    @DisplayName("the kill switch starts, injects, and throws on use")
    void killSwitchStartsAndThrows() {
        // Note the absence of every other property: the disabled branch has to start from nothing, or it is not
        // usable as the profile-level switch it exists to be.
        runner.withPropertyValues("aimon.enabled=false").run(ctx -> {
            assertThat(ctx).hasSingleBean(AimonSessions.class).doesNotHaveBean(AimonStack.class)
                    .doesNotHaveBean(LlmClient.class);
            assertThat(ctx).getBean(AimonSessions.class).isInstanceOf(DisabledAimonSessions.class);
            assertThatThrownBy(() -> ctx.getBean(AimonSessions.class).submit(SessionId.generate(), "hi"))
                    .isInstanceOf(AimonDisabledException.class).hasMessageContaining(AimonProperties.ENABLED);
        });
    }

    @Test
    @DisplayName("the configured budget reaches the request the facade builds")
    void budgetReachesTheRequest(@TempDir Path workspace) {
        // AimonStackSpec.getDefaultBudget() has no consumer inside the stack — the live-session opener passes
        // the caller's options through untouched. Without the facade putting it on the request, every
        // aimon.budget.* property would bind, validate, and do nothing.
        minimal(workspace).run(ctx -> {
            assertThat(ctx.getBean(AimonStack.class).spec().getDefaultBudget().isUnlimited()).isFalse();
            final SubmitRequest request = ctx.getBean(AimonSessions.class).newRequest(SessionId.generate(), "hi")
                    .build();
            assertThat(request.getOptions().getBudget().isUnlimited()).isFalse();
            assertThat(request.getOptions().getBudget().getMaxIterations()).contains(20);
            assertThat(request.getAgentRef()).isEqualTo(AGENT);
            assertThat(request.getInitiator()).isNotNull();
        });
    }

    @Test
    @DisplayName("a multimodal request carries the same defaults the text one does")
    void multimodalRequestKeepsTheConfiguredDefaults(@TempDir Path workspace) {
        // The starter is the scale-out shape, so it is where a multimodal turn most needs a route that is not a
        // workaround. newRequest(id, "") followed by .userInput(image) would have worked and would have submitted a
        // turn whose text part is an empty string; this overload is the reason nobody has to discover that.
        minimal(workspace).run(ctx -> {
            final UserInput input = MultimodalInput.of(TextInput.of("what is in this?"),
                    ImageInput.of(new byte[]{1, 2, 3, 4}, "image/png"));

            final SubmitRequest request = ctx.getBean(AimonSessions.class).newRequest(SessionId.generate(), input)
                    .build();

            assertThat(request.getUserInput()).isEqualTo(input);
            assertThat(request.getOptions().getBudget().isUnlimited()).isFalse();
            assertThat(request.getAgentRef()).isEqualTo(AGENT);
            assertThat(request.getInitiator()).isNotNull();
        });
    }

    @Test
    @DisplayName("two declared agents become two runtimes, and the facade says what each was built from")
    void twoAgentsBecomeTwoRuntimes(@TempDir Path workspace) {
        twoAgents(workspace).run(ctx -> {
            final AimonStack stack = ctx.getBean(AimonStack.class);
            assertThat(stack.runtimes().keySet()).extracting(Object::toString).containsExactlyInAnyOrder("agent:ops",
                    "agent:inquiry");
            assertThat(stack.primaryRuntimeId().toString()).isEqualTo("agent:ops");

            // The ref is what a submit routes on; the bundle is only where the definition was read from. Keeping
            // them apart is what lets one bundle run under two refs, and it is only visible here.
            final List<AgentDescriptor> agents = ctx.getBean(AimonAgents.class).list();
            assertThat(agents).extracting(AgentDescriptor::getAgentRef).containsExactly("ops", "inquiry");
            assertThat(agents).extracting(AgentDescriptor::getBundleName).containsExactly(AGENT, SECOND_AGENT);
            assertThat(agents.get(0).getProperty("region")).contains("eu-west-1");
            assertThat(agents.get(0).getDiscriminator()).isEmpty();
        });
    }

    @Test
    @DisplayName("a customizer bean reaches the agent it selects, its tenants, and nothing else")
    void customizerBeanReachesOnlyTheAgentItSelects(@TempDir Path workspace) {
        // The row this seam exists for. The startup runtime is built by the builder's loop and the tenant one on
        // a request thread hours later; if the tool list were assembled in two places, this test would pass for
        // agent:ops and fail for agent:ops:acme, which is the shape of the bug the single path prevents.
        twoAgents(workspace).withUserConfiguration(TicketingCustomizerConfiguration.class).run(ctx -> {
            final AimonStack stack = ctx.getBean(AimonStack.class);
            assertThat(markerIn(stack, AgentRuntimeId.fromName("ops"))).isTrue();
            assertThat(markerIn(stack, AgentRuntimeId.fromName("inquiry"))).isFalse();

            try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(AgentRuntimeId.fromName("ops", "acme"))) {
                assertThat(((OrcaAgentRuntime) lease.runtime()).getToolRegistry().findByName(MarkerTool.TOOL_NAME))
                        .isPresent();
            }

            // The descriptors the customizer was asked about are the ones the facade lists, tenants included —
            // so a customizer that reads a property sees the same value an admin endpoint would report.
            assertThat(TicketingCustomizerConfiguration.INSTANCE.seen).contains("ops", "inquiry", "ops:acme");
        });
    }

    @Test
    @DisplayName("tenant runtimes are built on first use, kept apart, and not counted as configured agents")
    void tenantRuntimesAreLazyAndIsolated(@TempDir Path workspace) {
        twoAgents(workspace).run(ctx -> {
            final AimonStack stack = ctx.getBean(AimonStack.class);
            assertThat(stack.agentRuntimes().trackedCount()).isZero();

            try (AgentRuntimeLease acme = stack.agentRuntimes().acquire(AgentRuntimeId.fromName("ops", "acme"));
                    AgentRuntimeLease globex = stack.agentRuntimes()
                            .acquire(AgentRuntimeId.fromName("ops", "globex"))) {
                assertThat(acme.runtime()).isNotSameAs(globex.runtime());
                assertThat(stack.agentRuntimes().trackedCount()).isEqualTo(2);
            }

            // Two tenants of one agent are two runtimes and still one agent: list() answers a question about
            // configuration, which does not grow with traffic.
            assertThat(ctx.getBean(AimonAgents.class).list()).hasSize(2);
        });
    }

    @Test
    @DisplayName("invalidating drops one tenant, or every tenant of an agent, and never the startup runtime")
    void invalidationIsScopedToTenants(@TempDir Path workspace) {
        twoAgents(workspace).run(ctx -> {
            final AimonStack stack = ctx.getBean(AimonStack.class);
            final AimonAgents agents = ctx.getBean(AimonAgents.class);
            stack.agentRuntimes().acquire(AgentRuntimeId.fromName("ops", "acme")).close();
            stack.agentRuntimes().acquire(AgentRuntimeId.fromName("ops", "globex")).close();
            stack.agentRuntimes().acquire(AgentRuntimeId.fromName("inquiry", "acme")).close();

            agents.invalidate("ops", "acme");
            assertThat(stack.agentRuntimes().trackedIds()).extracting(Object::toString)
                    .containsExactlyInAnyOrder("agent:ops:globex", "agent:inquiry:acme");

            agents.invalidate("ops");
            assertThat(stack.agentRuntimes().trackedIds()).extracting(Object::toString)
                    .containsExactly("agent:inquiry:acme");

            // The startup runtimes were never tracked, so neither call could have taken one — which is the
            // point: they are what the stack's startup checks ran against.
            assertThat(stack.runtimes().keySet()).extracting(Object::toString).containsExactlyInAnyOrder("agent:ops",
                    "agent:inquiry");
        });
    }

    @Test
    @DisplayName("the runtime cache properties reach the resolver")
    void runtimeCachePropertiesReachTheResolver(@TempDir Path workspace) {
        // Without this the whole aimon.agent-runtime subtree would bind, validate, and be dropped on the floor —
        // the same failure mode the budget test exists for, one layer along.
        twoAgents(workspace).withPropertyValues("aimon.agent-runtime.max-entries=3")
                .run(ctx -> assertThat(ctx.getBean(AimonStack.class).agentRuntimes().maxEntries()).isEqualTo(3));
    }

    @Test
    @DisplayName("credential properties become the stack's shared store, and no properties leave it unset")
    void credentialPropertiesBecomeTheStore(@TempDir Path workspace) {
        // The empty case first, because it is the one an empty map would get wrong: an InMemoryCredentialStore
        // holding nothing answers every lookup with "not found", which is what a store whose backend is down
        // also answers. Leaving the spec's store unset is what lets a tool say the deployment configured none.
        minimal(workspace).run(ctx -> assertThat(ctx.getBean(AimonStack.class).spec().getCredentialStore()).isEmpty());

        minimal(workspace)
                .withPropertyValues("aimon.credentials.jira.username=admin", "aimon.credentials.jira.password=hunter2")
                .run(ctx -> {
                    final CredentialStore store = ctx.getBean(AimonStack.class).spec().getCredentialStore()
                            .orElseThrow();
                    assertThat(store.getProfiles()).containsExactly("jira");
                    assertThat(store.getFields("jira")).containsExactlyInAnyOrder("username", "password");
                    assertThat(store.get("jira", "password")).contains("hunter2");
                });
    }

    @Test
    @DisplayName("properties and a CredentialStore bean refuse each other rather than one winning quietly")
    void credentialPropertiesAndAStoreBeanRefuseEachOther(@TempDir Path workspace) {
        // Bean-beats-properties is the conventional resolution and the wrong one here. What loses is not a
        // default someone can retype but a secret, and dropping it is invisible until a tool reports a missing
        // credential hours after startup — at which point the configuration still reads as if it were set.
        minimal(workspace).withPropertyValues("aimon.credentials.jira.password=hunter2")
                .withUserConfiguration(ApplicationCredentialStoreConfiguration.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.CREDENTIALS)
                        .hasStackTraceContaining(CredentialStore.class.getName()));
    }

    @Test
    @DisplayName("properties and a CredentialStoreFactory bean refuse each other too")
    void credentialPropertiesAndAFactoryBeanRefuseEachOther(@TempDir Path workspace) {
        // Not the same conflict twice: this pair disagrees about scope as well as about source. The properties
        // build one store every runtime shares; the factory builds one per tenant.
        minimal(workspace).withPropertyValues("aimon.credentials.jira.password=hunter2")
                .withUserConfiguration(TenantCredentialStoreFactoryConfiguration.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.CREDENTIALS)
                        .hasStackTraceContaining(CredentialStoreFactory.class.getName()));
    }

    @Test
    @DisplayName("a store bean and a factory bean refuse each other, as they did before the properties existed")
    void aCredentialStoreBeanAndAFactoryBeanRefuseEachOther(@TempDir Path workspace) {
        // The pre-existing half of the rule, untested until now — which is why the properties could not simply
        // be added to a branch and assumed covered.
        minimal(workspace)
                .withUserConfiguration(ApplicationCredentialStoreConfiguration.class,
                        TenantCredentialStoreFactoryConfiguration.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(CredentialStore.class.getName())
                        .hasStackTraceContaining(CredentialStoreFactory.class.getName()));
    }

    @Test
    @DisplayName("the kill switch publishes an agents facade that lists nothing and refuses to invalidate")
    void killSwitchPublishesADisabledAgentsFacade() {
        runner.withPropertyValues("aimon.enabled=false").run(ctx -> {
            assertThat(ctx).hasSingleBean(AimonAgents.class);
            assertThat(ctx).getBean(AimonAgents.class).isInstanceOf(DisabledAimonAgents.class);
            assertThat(ctx.getBean(AimonAgents.class).list()).isEmpty();
            assertThatThrownBy(() -> ctx.getBean(AimonAgents.class).invalidate("ops", "acme"))
                    .isInstanceOf(AimonDisabledException.class).hasMessageContaining(AimonProperties.ENABLED);
        });
    }

    @Test
    @DisplayName("the in-memory session store is a logged degradation, not a silent default")
    void inMemoryStoreIsDeclared(@TempDir Path workspace) {
        // Publishing an explicit InMemorySessionRecordStore would satisfy the stack's check and lose this.
        minimal(workspace).run(
                ctx -> assertThat(ctx.getBean(AimonStack.class).degradations().has("session-durability")).isTrue());
    }

    @Test
    @DisplayName("fail-fast turns those degradations into a startup failure")
    void failFastRefusesToStartDegraded(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("aimon.fail-fast=true").run(
                ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(AimonProperties.FAIL_FAST));
    }

    @Test
    @DisplayName("Spring closes the stack and the LLM client, and nothing else")
    void onlyTheStackAndTheClientAreClosedBySpring(@TempDir Path workspace) {
        // D4's real content is not "exactly one bean is closeable" but "no resource has two destruction
        // edges". The LLM client is the second closeable bean on purpose: it is not enrolled in the stack's
        // teardown, so if Spring did not close it, AnthropicLlmClient's connection pool would leak. Ordering is
        // safe because AimonStackSpec takes the client as a dependency and Spring destroys dependents first.
        minimal(workspace).run(
                ctx -> assertThat(beansSpringWouldClose(ctx)).containsExactly("aimonAnthropicLlmClient", "aimonStack"));
    }

    @Test
    @DisplayName("a knowledge store is the third such bean, and it is Spring that closes it")
    void theKnowledgeStoreIsClosedBySpringToo(@TempDir Path workspace) {
        // Same rule as the LLM client, applied to the one component the builder deliberately does not enrol:
        // AimonStackSpec.knowledgeStore borrows rather than owns, so the stack's teardown plan has no edge to
        // this store and Spring's inferred close() is the only one. Making it @Bean(destroyMethod = "") — the
        // rule for everything the stack does own — would leak it instead.
        //
        // Under backend=supplied the store is the application's and closing it is the application's business;
        // keyword is the case where the starter built it, which is why the assertion is on keyword.
        minimal(workspace).withPropertyValues("aimon.knowledge.backend=keyword")
                .run(ctx -> assertThat(beansSpringWouldClose(ctx)).containsExactly("aimonAnthropicLlmClient",
                        "aimonKeywordKnowledgeStore", "aimonStack"));
    }

    /**
     * Names the AIMON-owned beans Spring's destroy-method inference would actually call something on.
     *
     * <p>
     * Reading {@code destroyMethodName} off the bean definitions would answer a different question: every
     * {@code @Bean} without an explicit value carries the {@code (inferred)} marker, whether or not the runtime
     * class has anything to infer. What matters is the class.
     */
    private static Set<String> beansSpringWouldClose(AssertableApplicationContext ctx) {
        final ConfigurableListableBeanFactory factory = ctx.getBeanFactory();
        final Set<String> closed = new TreeSet<>();
        for (String name : factory.getBeanDefinitionNames()) {
            if (!name.startsWith("aimon")) {
                continue;
            }
            final Object bean = factory.getBean(name);
            if (bean instanceof DisposableBean || bean instanceof AutoCloseable || hasNoArgMethod(bean, "close")
                    || hasNoArgMethod(bean, "shutdown")) {
                closed.add(name);
            }
        }
        return closed;
    }

    private static boolean markerIn(AimonStack stack, AgentRuntimeId agentRuntimeId) {
        return stack.runtime(agentRuntimeId).orElseThrow().getToolRegistry().findByName(MarkerTool.TOOL_NAME)
                .isPresent();
    }

    private static String stackTraceOf(Throwable failure) {
        final StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static boolean hasNoArgMethod(Object bean, String name) {
        try {
            final Method method = bean.getClass().getMethod(name);
            return method.getParameterCount() == 0;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Stands in for an application that builds its own client — a gateway, a recorded fixture, a new vendor. */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationLlmConfiguration {

        static final LlmClient INSTANCE = new StubLlmClient();

        @Bean
        LlmClient applicationLlmClient() {
            return INSTANCE;
        }
    }

    /** Stands in for an application that resolves its secrets elsewhere — Vault, a KMS, a mounted file. */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationCredentialStoreConfiguration {

        @Bean
        CredentialStore applicationCredentialStore() {
            return InMemoryCredentialStore.builder().profile("jira", Map.of("password", "from-the-bean")).build();
        }
    }

    /** Stands in for the multi-tenant deployment, where one shared store is the wrong shape entirely. */
    @Configuration(proxyBeanMethods = false)
    static class TenantCredentialStoreFactoryConfiguration {

        @Bean
        CredentialStoreFactory tenantCredentialStores() {
            return discriminator -> InMemoryCredentialStore.builder()
                    .profile("jira", Map.of("password", "for-" + discriminator)).build();
        }
    }

    /** Stands in for the host that answers "agent A gets the ticketing tools and agent B does not". */
    @Configuration(proxyBeanMethods = false)
    static class TicketingCustomizerConfiguration {

        static final TicketingCustomizer INSTANCE = new TicketingCustomizer();

        @Bean
        AimonAgentCustomizer ticketingCustomizer() {
            return INSTANCE;
        }
    }

    /**
     * Contributes one tool to {@code ops} and records every runtime it was asked about.
     *
     * <p>
     * A single instance shared across the two runners is fine because only one test uses it, but the
     * {@code seen} list is still synchronized: the stack asks {@code supports} on request threads, and this is
     * the shape a real customizer has to have.
     */
    static final class TicketingCustomizer implements AimonAgentCustomizer {

        final List<String> seen = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean supports(AgentDescriptor agent) {
            seen.add(agent.getAgentRef() + agent.getDiscriminator().map(tenant -> ":" + tenant).orElse(""));
            return "ops".equals(agent.getAgentRef());
        }

        @Override
        public List<OrcaToolProvider> toolProviders(AgentDescriptor agent) {
            return List.of((registry, context) -> registry.register(new MarkerTool()));
        }
    }

    /** Present or absent, which is the entire assertion — it is never executed. */
    static final class MarkerTool extends AbstractTool {

        static final String TOOL_NAME = "Marker";

        MarkerTool() {
            super(TOOL_NAME, "Marks that a customizer reached this runtime.", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("marked");
        }
    }
}
