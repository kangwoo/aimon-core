package at.aimon.spring.boot.autoconfigure;

import static at.aimon.spring.boot.autoconfigure.AimonProperties.LLM_ANTHROPIC;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.LLM_ANTHROPIC_THINKING_BUDGET_TOKENS;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.LLM_ANTHROPIC_THINKING_MODE;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.LLM_API_KEY;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.LLM_MODEL;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.LLM_PROVIDER;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.PROVIDER_ANTHROPIC;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.PROVIDER_OPENAI;

import java.util.Locale;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.core.llm.LlmClient;
import at.aimon.core.llms.anthropic.AnthropicConfig;
import at.aimon.core.llms.anthropic.AnthropicLlmClient;
import at.aimon.core.llms.anthropic.AnthropicThinkingMode;
import at.aimon.core.llms.openai.OpenAIConfig;
import at.aimon.core.llms.openai.OpenAILlmClient;

/**
 * Builds the {@link LlmClient} named by {@code aimon.llm.provider}.
 *
 * <p>
 * Each vendor lives in its own nested {@code @Configuration} guarded by {@code @ConditionalOnClass}, so the
 * class reference to a vendor SDK that is not on the classpath is never loaded. That is only safe because
 * Spring reads these conditions from bytecode: the nested class is inspected, found inapplicable, and skipped
 * without the classloader ever being asked for {@code AnthropicLlmClient}. Both vendor modules are
 * {@code compileOnly} here for the same reason — an application that picks one should not carry the other.
 *
 * <p>
 * Anthropic matches when the property is absent, so the documented minimal configuration needs only a
 * workspace and an API key. Both beans are {@code @ConditionalOnMissingBean(LlmClient.class)}: an application
 * that builds its own client — a custom gateway, a recorded fixture in tests, a vendor this starter does not
 * know — wins, and neither branch fires. That is also the extension path for a third-party provider value:
 * define the bean, set {@code aimon.llm.provider} to your own name, and no built-in branch matches.
 *
 * <p>
 * Neither bean overrides {@code destroyMethod}. {@code AnthropicLlmClient} is {@code AutoCloseable} and holds
 * an HTTP connection pool, so Spring's inferred {@code close()} is what releases it; {@code OpenAILlmClient}
 * has no {@code close()} and inference finds nothing to call. Ordering against the stack's own teardown is not
 * left to chance either: {@code AimonStackSpec} takes the client as a constructor-injected dependency, and
 * Spring destroys dependents before their dependencies, so the stack is fully torn down before the transport
 * underneath it goes away.
 */
@AutoConfiguration
@ConditionalOnProperty(name = AimonProperties.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AimonProperties.class)
public class AimonLlmAutoConfiguration {

    /**
     * Rejects a missing credential by name.
     *
     * <p>
     * Checked here rather than in {@code AimonProperties} because the answer depends on a bean: an application
     * that defines its own {@link LlmClient} never reaches these methods, and demanding a key it has no use for
     * would turn a valid configuration into a startup failure. The vendor config would reject a blank key too,
     * but its message talks about its own builder argument — the user needs the property name.
     *
     * @param llm
     *            the bound LLM properties
     * @param provider
     *            the provider value that selected this branch
     */
    private static void requireApiKey(AimonProperties.Llm llm, String provider) {
        if (llm.getApiKey() == null || llm.getApiKey().isBlank()) {
            throw new IllegalStateException(LLM_API_KEY + " must be set for " + LLM_PROVIDER + "=" + provider
                    + ". Supply the credential, or define your own LlmClient bean — this slice backs off when"
                    + " one is already present.");
        }
    }

    /**
     * Rejects a missing model by name, for a provider whose config has no default one.
     *
     * <p>
     * Same reasoning as {@link #requireApiKey}: {@code OpenAIConfig.build()} rejects it too, but its message names a
     * builder argument, and the operator needs the property. Only the OpenAI branch calls this —
     * {@code AnthropicConfig} still carries a current default model, so demanding one there would turn a working
     * configuration into a startup failure.
     *
     * @param llm
     *            the bound LLM properties
     * @param provider
     *            the provider value that selected this branch
     */
    private static void requireModel(AimonProperties.Llm llm, String provider) {
        if (llm.getModel() == null || llm.getModel().isBlank()) {
            throw new IllegalStateException(LLM_MODEL + " must be set for " + LLM_PROVIDER + "=" + provider
                    + ". That provider has no default model; name the one this deployment talks to"
                    + " (e.g. gpt-4o).");
        }
    }

    /**
     * Rejects an {@code aimon.llm.anthropic} block that the selected provider will never read, by name.
     *
     * <p>
     * "Configured and never read" is the failure this repository refuses everywhere else, and a subtree literally
     * named after one vendor leaves no ambiguity about whose it is. The check runs from inside the branch that
     * actually fired, for the reason {@link #requireApiKey} gives: outside a running branch it would turn valid
     * configuration into a startup failure — {@code provider=none}, or an application that supplies its own
     * {@link LlmClient}, are deployments where nobody is entitled to demand anything of this block.
     *
     * <p>
     * Declared on the <em>enclosing</em> class rather than in a guarded slice, which the rest of this file
     * arranges the other way round. That is safe and deliberate: this descriptor names only starter types, and
     * the body reads three fields for null through {@link AimonProperties.Llm.Anthropic#isEmpty()} — reading a
     * field loads no class. It has to be here, because the branch that needs it is the one whose classpath does
     * not have the Anthropic module.
     *
     * @param llm
     *            the bound LLM properties
     * @param provider
     *            the provider value that selected this branch
     */
    private static void refuseAnthropicBlock(AimonProperties.Llm llm, String provider) {
        if (!llm.getAnthropic().isEmpty()) {
            throw new IllegalStateException(LLM_ANTHROPIC + ".* is set but " + LLM_PROVIDER + "=" + provider
                    + ", so nothing reads it. Remove the block, or select the provider that consumes it" + " ("
                    + LLM_PROVIDER + "=" + PROVIDER_ANTHROPIC + ").");
        }
    }

    /** Anthropic branch — also the branch taken when {@code aimon.llm.provider} is absent. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AnthropicLlmClient.class)
    @ConditionalOnProperty(name = LLM_PROVIDER, havingValue = PROVIDER_ANTHROPIC, matchIfMissing = true)
    static class AnthropicConfiguration {

        @Bean
        @ConditionalOnMissingBean(LlmClient.class)
        LlmClient aimonAnthropicLlmClient(AimonProperties properties) {
            final AimonProperties.Llm llm = properties.getLlm();
            return new AnthropicLlmClient(anthropicConfig(llm));
        }

        /**
         * Builds the vendor config from the bound properties.
         *
         * <p>
         * Package-private and declared <em>inside this nested class</em> for the reason
         * {@link OpenAiConfiguration#openAiConfig(AimonProperties.Llm)} spells out: {@code AnthropicConfig} appears in
         * this method's descriptor, and Spring calls {@code getDeclaredMethods()} on a configuration class while
         * post-processing it. On the enclosing class that would ask the classloader for a type a deployment carrying
         * only the OpenAI module does not have.
         *
         * @param llm
         *            the bound LLM properties
         * @return the assembled Anthropic config
         */
        static AnthropicConfig anthropicConfig(AimonProperties.Llm llm) {
            requireApiKey(llm, PROVIDER_ANTHROPIC);
            final AnthropicConfig.Builder config = AnthropicConfig.builder().apiKey(llm.getApiKey());
            if (llm.getModel() != null) {
                config.model(llm.getModel());
            }
            if (llm.getBaseUrl() != null) {
                config.baseUrl(llm.getBaseUrl());
            }
            if (llm.getTimeout() != null) {
                config.timeout(llm.getTimeout());
            }
            if (!llm.getModelCapabilities().isEmpty()) {
                config.modelCapabilityRegistry(AimonProperties.modelCapabilityRegistry(llm));
            }
            applyThinking(config, llm.getAnthropic());
            try {
                return config.build();
            } catch (IllegalArgumentException e) {
                // Narrow on purpose. The IllegalArgumentExceptions build() can throw on this path are a blank API
                // key, an out-of-range temperature, a non-positive maxTokens, a budget below 1024, and a budget
                // under a mode other than EXTENDED. The first three cannot arrive -- requireApiKey rejects a blank
                // key by property name first, and neither of the other two is settable from any configuration
                // surface -- so the two that remain are both the budget's, which is why the message can name that
                // one key rather than the block. If a later round makes temperature settable, that stops being
                // true and the catch has to be split.
                throw new IllegalStateException(LLM_ANTHROPIC_THINKING_BUDGET_TOKENS + " is invalid: " + e.getMessage(),
                        e);
            }
        }

        /**
         * Copies the three {@code aimon.llm.anthropic} keys onto the vendor config, each only when it was
         * written.
         *
         * <p>
         * That "only when written" is the whole of the compatibility claim: a deployment that sets none of them
         * calls none of these setters, so {@code AnthropicConfig}'s own defaults stand and the request is
         * byte-for-byte what it was before this block existed.
         *
         * <p>
         * The mode/budget interaction is not decided here. A budget is legal only under {@code EXTENDED}, and the
         * one place that rule lives is {@code AnthropicConfig}'s constructor; re-checking it here would be a
         * second copy of it that can disagree.
         *
         * @param config
         *            the builder being assembled
         * @param anthropic
         *            the bound {@code aimon.llm.anthropic} block
         */
        private static void applyThinking(AnthropicConfig.Builder config, AimonProperties.Llm.Anthropic anthropic) {
            if (anthropic.isEmpty()) {
                return;
            }
            if (anthropic.getThinkingMode() != null) {
                config.thinkingMode(thinkingMode(anthropic.getThinkingMode()));
            }
            if (anthropic.getThinkingBudgetTokens() != null) {
                config.thinkingBudgetTokens(anthropic.getThinkingBudgetTokens());
            }
            if (anthropic.getReplayThinkingBlocks() != null) {
                config.replayThinkingBlocks(anthropic.getReplayThinkingBlocks());
            }
        }

        /**
         * Folds the bound string onto the vendor enum, case-insensitively.
         *
         * <p>
         * The property is a {@code String} — see {@link AimonProperties.Llm.Anthropic} for why — so the fold is by
         * hand here. It iterates {@code values()} rather than a literal list so that the two surfaces cannot come
         * to accept different spellings, and so that a fifth constant needs no edit here.
         *
         * <p>
         * The message carries one extra sentence for {@code true} / {@code false}, and it is not decoration.
         * {@code off} is a YAML 1.1 boolean: written unquoted in an {@code application.yml} it is loaded as
         * {@code Boolean.FALSE} and converted to the string {@code "false"} before this method ever sees it. So
         * the one value an operator writes to turn thinking off is also the one that arrives unrecognisable, and
         * without the hint the message would list four spellings, one of which is the one they wrote. The CLI
         * surface has the same collision and answers it differently — it can read the parser's original scalar,
         * which this one never receives.
         *
         * @param value
         *            the value as it was written
         * @return the matching constant
         * @throws IllegalStateException
         *             naming the property and every accepted spelling
         */
        private static AnthropicThinkingMode thinkingMode(String value) {
            final String written = value.trim();
            for (AnthropicThinkingMode candidate : AnthropicThinkingMode.values()) {
                if (candidate.name().equalsIgnoreCase(written)) {
                    return candidate;
                }
            }
            final StringBuilder accepted = new StringBuilder();
            for (AnthropicThinkingMode candidate : AnthropicThinkingMode.values()) {
                accepted.append(accepted.length() == 0 ? "" : ", ").append(candidate.name().toLowerCase(Locale.ROOT));
            }
            final String yamlHint = "true".equalsIgnoreCase(written) || "false".equalsIgnoreCase(written)
                    ? " YAML reads an unquoted `off` as a boolean, so write it quoted: thinking-mode: \"off\"."
                    : "";
            throw new IllegalStateException(LLM_ANTHROPIC_THINKING_MODE + "=" + value
                    + " is not a thinking mode. Accepted values: " + accepted + "." + yamlHint);
        }
    }

    /** OpenAI branch. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OpenAILlmClient.class)
    @ConditionalOnProperty(name = LLM_PROVIDER, havingValue = PROVIDER_OPENAI)
    static class OpenAiConfiguration {

        @Bean
        @ConditionalOnMissingBean(LlmClient.class)
        LlmClient aimonOpenAiLlmClient(AimonProperties properties) {
            return new OpenAILlmClient(openAiConfig(properties.getLlm()));
        }

        /**
         * Builds the vendor config from the bound properties.
         *
         * <p>
         * Package-private so a unit test can inspect the config without {@code OpenAILlmClient} having to publish it,
         * and declared <em>inside this nested class</em> rather than on the enclosing one. That placement is
         * load-bearing: {@code OpenAIConfig} appears in this method's descriptor, and Spring calls
         * {@code getDeclaredMethods()} on a configuration class while post-processing it — which loads every declared
         * method's return type, private ones included. On the enclosing class that would ask the classloader for
         * {@code OpenAIConfig} in a deployment that carries only the Anthropic module, which is exactly the
         * {@code NoClassDefFoundError} the {@code @ConditionalOnClass} arrangement described in this file's javadoc
         * exists to prevent. Here the class itself is behind that condition, so it is never inspected at all.
         *
         * @param llm
         *            the bound LLM properties
         * @return the assembled OpenAI config
         */
        static OpenAIConfig openAiConfig(AimonProperties.Llm llm) {
            refuseAnthropicBlock(llm, PROVIDER_OPENAI);
            requireApiKey(llm, PROVIDER_OPENAI);
            requireModel(llm, PROVIDER_OPENAI);
            final OpenAIConfig.Builder config = OpenAIConfig.builder().apiKey(llm.getApiKey()).model(llm.getModel());
            if (llm.getBaseUrl() != null) {
                config.baseUrl(llm.getBaseUrl());
            }
            if (llm.getTimeout() != null) {
                config.timeout(llm.getTimeout());
            }
            if (!llm.getModelCapabilities().isEmpty()) {
                config.modelCapabilityRegistry(AimonProperties.modelCapabilityRegistry(llm));
            }
            return config.build();
        }
    }
}
