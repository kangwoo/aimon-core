package at.aimon.spring.boot.autoconfigure;

import static at.aimon.spring.boot.autoconfigure.AimonProperties.LLM_API_KEY;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.LLM_PROVIDER;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.PROVIDER_ANTHROPIC;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.PROVIDER_OPENAI;

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

    /** Anthropic branch — also the branch taken when {@code aimon.llm.provider} is absent. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AnthropicLlmClient.class)
    @ConditionalOnProperty(name = LLM_PROVIDER, havingValue = PROVIDER_ANTHROPIC, matchIfMissing = true)
    static class AnthropicConfiguration {

        @Bean
        @ConditionalOnMissingBean(LlmClient.class)
        LlmClient aimonAnthropicLlmClient(AimonProperties properties) {
            final AimonProperties.Llm llm = properties.getLlm();
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
            return new AnthropicLlmClient(config.build());
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
            final AimonProperties.Llm llm = properties.getLlm();
            requireApiKey(llm, PROVIDER_OPENAI);
            final OpenAIConfig.Builder config = OpenAIConfig.builder().apiKey(llm.getApiKey());
            if (llm.getModel() != null) {
                config.model(llm.getModel());
            }
            if (llm.getBaseUrl() != null) {
                config.baseUrl(llm.getBaseUrl());
            }
            if (llm.getTimeout() != null) {
                config.timeout(llm.getTimeout());
            }
            return new OpenAILlmClient(config.build());
        }
    }
}
