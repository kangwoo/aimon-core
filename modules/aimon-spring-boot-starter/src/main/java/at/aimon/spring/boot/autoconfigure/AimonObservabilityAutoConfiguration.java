package at.aimon.spring.boot.autoconfigure;

import java.util.List;

import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.bootstrap.AimonStack;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanRedactor;
import at.aimon.core.tracing.TraceSpanStore;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
import at.aimon.spring.boot.health.AimonHealthIndicator;
import at.aimon.spring.boot.metrics.AimonMetrics;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Builds the tracer, and exposes what the stack already knows about itself to Actuator.
 *
 * <p>
 * Ordered {@code before} {@link AimonAutoConfiguration} because the {@link Tracer} is not something the stack is
 * handed after the fact — it goes into {@code ExecutorSpec} and it wraps the {@code LlmClient}, both of which
 * happen while the spec is being assembled. The health and metrics beans below run the other way round and
 * depend on the assembled stack; they take it as a constructor parameter rather than through
 * {@code @ConditionalOnBean}, because a condition evaluated before {@code AimonAutoConfiguration} has registered
 * anything would answer "no stack" every time and quietly publish nothing.
 *
 * <p>
 * It is also ordered {@code after} Boot's two metrics auto-configurations, by name rather than by class literal
 * so that the annotation carries no reference to a {@code compileOnly} type. That is what lets the metrics branch
 * below ask whether a {@link MeterRegistry} bean exists rather than merely whether Micrometer is on the
 * classpath; the two orderings do not conflict, because nothing in Boot's metrics chain is itself ordered after
 * {@link AimonAutoConfiguration}.
 *
 * <h2>Three switches, deliberately not one</h2>
 *
 * <p>
 * {@code aimon.tracing.enabled} governs the tracer alone. The health indicator and the metrics are governed by
 * the application already having somewhere to put them, and by nothing else — an operator who added
 * {@code spring-boot-starter-actuator} has said what they want, and making capacity metrics conditional on
 * tracing would hide the numbers that answer "why is this node refusing tenants?" behind a setting about LLM
 * spans. The three are independent because their costs are: tracing records a span per call, the health report
 * recomputes a handful of field reads, and the meters are gauges read on scrape.
 *
 * <h2>What is not built here</h2>
 *
 * <p>
 * No {@link SpanExporter} other than the no-op one. Exporting means a wire protocol, an endpoint, credentials and
 * a retry policy, and an exporter this starter guessed at would be the one component whose failure mode is to
 * drop spans quietly. An application that wants OTLP defines its own {@link Tracer} bean, which this slice backs
 * off from, and keeps every one of those decisions where it can see them.
 *
 * <p>
 * The redactor <em>is</em> chosen here, and it is not the no-op: {@link SpanRedactor#defaultRedactor()} masks
 * values under key names that look like secrets. Tracing is off by default, but the deployment that turns it on
 * with {@code payload-capture=full} is asking for message content to be written to a span store, and defaulting
 * that to unmasked would make the first tracing session the moment an API key lands in a log aggregator.
 */
@AutoConfiguration(before = AimonAutoConfiguration.class, afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"})
@ConditionalOnProperty(name = AimonProperties.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AimonProperties.class)
public class AimonObservabilityAutoConfiguration {

    /**
     * Holds the spans the tracer records.
     *
     * <p>
     * In-memory and bounded, which is a development and single-node answer rather than a retention story: the
     * store is a ring that drops the oldest span when it is full, and it starts empty on every boot. It is the
     * honest default for a starter — the alternative, silently exporting nowhere, would leave
     * {@code tracing.enabled=true} looking like it had done something.
     *
     * <p>
     * Not enrolled in the stack's teardown and not given a {@code destroyMethod}, because there is nothing to
     * close: {@link InMemoryTraceSpanStore} holds a map and no thread, socket or file.
     *
     * @param properties
     *            source of {@code aimon.tracing.max-spans}
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = AimonProperties.TRACING_ENABLED, havingValue = "true")
    TraceSpanStore aimonTraceSpanStore(AimonProperties properties) {
        return new InMemoryTraceSpanStore(properties.getTracing().maxSpansOrDefault());
    }

    /**
     * Builds the tracer the executor and the LLM decorator share.
     *
     * <p>
     * One bean, deliberately, because there are two seams — {@code ExecutorSpec.tracer(...)} for the agent's own
     * spans, and {@code TracingLlmClient} for the model calls — and two tracers would produce two disjoint span
     * trees whose parent ids point at nothing. {@link AimonAutoConfiguration} injects this same instance into
     * both.
     *
     * @param store
     *            where recorded spans are kept
     * @return the tracer
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = AimonProperties.TRACING_ENABLED, havingValue = "true")
    Tracer aimonTracer(TraceSpanStore store) {
        return new DefaultTracer(store, SpanExporter.noop(), SpanRedactor.defaultRedactor());
    }

    /**
     * Actuator branch.
     *
     * <p>
     * Nested and {@code @ConditionalOnClass}-guarded, the same arrangement the Quartz branch uses: Actuator is
     * {@code compileOnly} here, so an application that does not have it must never cause these types to be
     * loaded. Spring reads the condition from bytecode, so the class is skipped without the classloader being
     * asked for anything inside it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    static class ActuatorHealthConfiguration {

        /**
         * Publishes the stack's own health report at {@code /actuator/health/aimon}.
         *
         * @param stack
         *            the assembled stack, asked fresh on every call
         * @return the indicator
         */
        @Bean
        @ConditionalOnMissingBean
        AimonHealthIndicator aimonHealthIndicator(AimonStack stack) {
            return new AimonHealthIndicator(stack);
        }
    }

    /**
     * Keeps {@code aimon.*} secrets out of {@code /actuator/env} and {@code /actuator/configprops}.
     *
     * <p>
     * Boot does not do this on its own. {@code Sanitizer} masks every value while {@code show-values} sits at its
     * {@code NEVER} default, but the moment an operator moves that setting to {@code ALWAYS} or
     * {@code WHEN_AUTHORIZED} it stops masking and applies only the {@link SanitizingFunction} beans the
     * application published — and Boot 3.x publishes none. The name-based masking people remember is
     * {@code SanitizingFunction.ifLikelyCredential()}, a helper for writing one of these, not a default. So
     * without the bean below, {@code aimon.llm.api-key} is printed in full at exactly the moment an operator was
     * expecting Boot's usual discretion about credentials.
     *
     * <p>
     * Scoped to the {@code aimon} prefix on purpose. An operator who set {@code show-values=ALWAYS} asked to see
     * values, and a starter that masked the application's own properties would be answering a question it was not
     * asked; conversely a starter that leaves its own key visible has published a property it never told anyone
     * was sensitive. The word list is Boot's — the four suffixes and the one substring
     * {@code ifLikelyCredential()} uses — because the two endpoints spell keys differently and matching Boot's
     * rule is what keeps the answer the same across both: {@code /env} reports the property source's own name
     * ({@code aimon.llm.api-key}, or {@code AIMON_LLM_API_KEY} from the environment) while {@code /configprops}
     * qualifies the serialized bean's fields ({@code aimon.llm.apiKey}). All three end in {@code key}. Suffix
     * rather than substring matching is also what keeps {@code aimon.memory.max-tokens} readable — it ends in
     * {@code tokens}, and a masked iteration limit helps nobody.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SanitizingFunction.class)
    static class ActuatorSanitizingConfiguration {

        private static final List<String> SECRET_SUFFIXES = List.of("password", "secret", "key", "token");

        /**
         * Masks the values of {@code aimon.*} keys that name a credential.
         *
         * <p>
         * Backed off by <em>name</em> rather than by type, which is the unusual half. Sanitizing functions
         * compose — Actuator collects every one of them and runs the lot — so a type-level
         * {@code @ConditionalOnMissingBean} would withdraw this one as soon as an application registered a
         * function for its own properties, silently un-masking the API key as a side effect of an unrelated
         * decision. Matching on the bean name means an application overrides this behaviour only by deliberately
         * claiming the name.
         *
         * @return the function
         */
        @Bean
        @ConditionalOnMissingBean(name = "aimonSanitizingFunction")
        SanitizingFunction aimonSanitizingFunction() {
            return data -> masksAimonSecret(data.getLowerCaseKey()) ? data.withSanitizedValue() : data;
        }

        /**
         * Decides whether a key is one of the starter's own credential-bearing properties.
         *
         * @param lowerCaseKey
         *            the key as the endpoint spelled it, already lower-cased
         * @return whether its value should be replaced
         */
        private static boolean masksAimonSecret(String lowerCaseKey) {
            // Both separators, because /env reports relaxed-binding spellings verbatim: a key bound from the
            // environment arrives as AIMON_LLM_API_KEY and would not start with "aimon.".
            if (!lowerCaseKey.startsWith(AimonProperties.PREFIX + ".")
                    && !lowerCaseKey.startsWith(AimonProperties.PREFIX + "_")) {
                return false;
            }
            if (lowerCaseKey.contains("credentials")) {
                return true;
            }
            return SECRET_SUFFIXES.stream().anyMatch(lowerCaseKey::endsWith);
        }
    }

    /**
     * Micrometer branch, independent of the one above — an application may have a meter registry without
     * Actuator's health endpoint, and the reverse.
     *
     * <p>
     * Conditional on the <em>bean</em> and not merely on the class, unlike the health branch. Micrometer on the
     * classpath does not mean a registry was configured: a plain application that depends on a library which
     * happens to bring {@code micrometer-core} has the type and no {@link MeterRegistry} bean at all, and a
     * required parameter there fails the whole context with a message about metrics — for an application that
     * never asked for any. That is the {@code @ConditionalOnBean} caveat the Boot docs warn about, and it is
     * answered the way Boot answers it for its own meter binders: order this after the registry is decided.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    static class MetricsConfiguration {

        /**
         * Registers the tenant-runtime gauges and counters.
         *
         * <p>
         * A {@code MeterBinder} would be the shorter spelling, but it is an Actuator-adjacent contract that
         * arrives with {@code spring-boot-actuator}; binding directly against {@link MeterRegistry} keeps this
         * branch working for an application that has Micrometer and no Actuator, which is the whole reason it is
         * separate from the branch above.
         *
         * @param registry
         *            the application's meter registry
         * @param stack
         *            the assembled stack the meters read from
         * @return the binding, held as a bean so the registration happens exactly once
         */
        @Bean
        @ConditionalOnMissingBean
        AimonMetrics aimonMetrics(MeterRegistry registry, AimonStack stack) {
            return AimonMetrics.bind(registry, stack);
        }
    }
}
