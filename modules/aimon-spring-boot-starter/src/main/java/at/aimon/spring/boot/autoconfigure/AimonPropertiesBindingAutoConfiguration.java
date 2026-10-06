package at.aimon.spring.boot.autoconfigure;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.context.annotation.Bean;

/**
 * Makes a misspelled key fail startup under the parts of {@code aimon.*} where a misspelling is expensive.
 *
 * <p>
 * {@code @ConfigurationProperties} ignores a property no field answers to, so
 * {@code aimon.llm.model-capabilities.prod-assistant.supports-sampling-parameter=false} used to bind nothing and
 * say nothing — and the deployment that wrote it kept receiving the HTTP 400 that key exists to prevent. This slice
 * publishes one {@link ConfigurationPropertiesBindHandlerAdvisor} that refuses such a property under
 * {@link #STRICT_SUBTREES} and nowhere else.
 *
 * <p>
 * <b>Why these subtrees and not all of {@code aimon.*}.</b> Turning {@code ignoreUnknownFields} off for the whole
 * prefix would fail an application that keeps a key of its own beside the starter's. The three subtrees here are
 * closed sets this starter defines every leaf of: a model's capability flags, and the two vendor blocks. A scalar
 * directly under {@code aimon.llm} — {@code aimon.llm.reasoning-effor} — is still ignored, because the only prefix
 * that covers it is {@code aimon.llm} itself.
 *
 * <p>
 * <b>What it does not reach.</b> An advisor is applied to every {@code @ConfigurationProperties} bind in the
 * application, so the narrowing happens inside the handler: a bean that never binds one of these names is not
 * checked at all. Properties that come from environment variables or JVM system properties are not checked either,
 * the same exemption Boot's own {@code ignoreUnknownFields = false} makes.
 *
 * <p>
 * A separate auto-configuration, rather than a bean on one of the slices, so that the rule holds whichever slices an
 * application keeps — and so that an application which does keep a key of its own under one of these subtrees has a
 * single class to name in {@code spring.autoconfigure.exclude}.
 */
@AutoConfiguration
@ConditionalOnProperty(name = AimonProperties.ENABLED, havingValue = "true", matchIfMissing = true)
public class AimonPropertiesBindingAutoConfiguration {

    /**
     * The property names under which an unbound element fails startup.
     *
     * <p>
     * Each is a subtree whose every leaf this starter defines. Adding a prefix here is the whole of extending the
     * rule to another such subtree.
     */
    static final List<String> STRICT_SUBTREES = List.of(AimonProperties.LLM_MODEL_CAPABILITIES,
            AimonProperties.LLM_ANTHROPIC, AimonProperties.LLM_OPENAI);

    /**
     * Wraps every configuration-properties bind in a handler that is strict under {@link #STRICT_SUBTREES}.
     *
     * @return the advisor; it creates a fresh handler per bind, because the handler keeps per-bind state
     */
    @Bean
    ConfigurationPropertiesBindHandlerAdvisor aimonStrictSubtreeBindHandlerAdvisor() {
        return parent -> new StrictSubtreeBindHandler(parent, STRICT_SUBTREES);
    }
}
