package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.bootstrap.runtime.AgentRuntimeLease;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.tracing.TraceSpanStore;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.TracingLlmClient;
import at.aimon.spring.boot.health.AimonHealthIndicator;
import at.aimon.spring.boot.metrics.AimonMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The observability slice, over a real stack.
 *
 * <p>
 * Unlike the spec slices, this one cannot be run alone: the health indicator and the meters read an assembled
 * {@link AimonStack}, and the tracer has to exist before the spec is built or it reaches neither of the two seams
 * that use it. So the full chain runs, and the assertions are about which of the three branches turned on.
 *
 * <p>
 * <b>Three switches, asserted separately.</b> Tracing is a property; health follows Actuator being present;
 * metrics follow a {@link MeterRegistry} bean existing. They are written as three independent conditions in the
 * slice, and the only way to show they really are independent is to remove them one at a time — hence the two
 * {@link FilteredClassLoader} cases below, which hide one library while leaving the other.
 */
class AimonObservabilitySliceTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(AimonLlmAutoConfiguration.class, AimonFileSystemAutoConfiguration.class,
                    AimonSessionAutoConfiguration.class, AimonSchedulingAutoConfiguration.class,
                    AimonKnowledgeAutoConfiguration.class, AimonMemoryAutoConfiguration.class,
                    AimonObservabilityAutoConfiguration.class, AimonAutoConfiguration.class));

    private ApplicationContextRunner minimal(Path workspace) {
        return runner.withPropertyValues("aimon.workspace.root=" + workspace, "aimon.llm.api-key=test-key",
                "aimon.agent-defaults.default-agent=test-agent");
    }

    @Test
    @DisplayName("tracing is off unless asked for, and costs no beans when off")
    void tracingIsOffByDefault(@TempDir Path workspace) {
        minimal(workspace).run(ctx -> {
            assertThat(ctx).doesNotHaveBean(Tracer.class);
            assertThat(ctx).doesNotHaveBean(TraceSpanStore.class);
            // And the client reaches the stack unwrapped — a decorator that records nowhere is still a call per
            // LLM invocation.
            assertThat(ctx.getBean(AimonStackSpec.class).getLlm().getClient()).isNotInstanceOf(TracingLlmClient.class);
            assertThat(ctx.getBean(AimonStackSpec.class).getExecutor().getTracer()).isEmpty();
        });
    }

    @Test
    @DisplayName("one tracer reaches both seams — the executor's and the LLM client's")
    void oneTracerReachesBothSeams(@TempDir Path workspace) {
        // The reason this is a single bean rather than one per seam. ExecutorSpec's tracer writes the parent-span
        // tags onto LLM call metadata and TracingLlmClient reads them back; two instances would each record a
        // parent the other's store had never seen, and every trace would come out as two disjoint trees.
        minimal(workspace).withPropertyValues("aimon.tracing.enabled=true").run(ctx -> {
            final Tracer tracer = ctx.getBean(Tracer.class);
            assertThat(ctx).hasSingleBean(TraceSpanStore.class);

            final AimonStackSpec spec = ctx.getBean(AimonStackSpec.class);
            assertThat(spec.getLlm().getClient()).isInstanceOf(TracingLlmClient.class);
            assertThat(spec.getExecutor().getTracer()).containsSame(tracer);
        });
    }

    @Test
    @DisplayName("the payload policy the executor gets is the one the property asked for")
    void payloadPolicyFollowsTheProperty(@TempDir Path workspace) {
        minimal(workspace)
                .withPropertyValues("aimon.tracing.enabled=true", "aimon.tracing.payload-capture=full",
                        "aimon.tracing.max-chars=1234")
                .run(ctx -> assertThat(ctx.getBean(AimonStackSpec.class).getExecutor().getTracePayloadPolicy())
                        .hasValueSatisfying(policy -> {
                            assertThat(policy.capturesContent()).isTrue();
                            assertThat(policy.getMaxChars()).isEqualTo(1234);
                        }));
    }

    @Test
    @DisplayName("the health indicator reports the stack's own report, degradations included")
    void healthIndicatorReportsTheStack(@TempDir Path workspace) {
        minimal(workspace).run(ctx -> {
            assertThat(ctx).hasSingleBean(AimonHealthIndicator.class);

            // UP with the reason attached, not DOWN: the in-memory session store is a degradation this stack
            // always carries, and a starter whose health endpoint is red on the documented minimum would train
            // every operator to ignore it.
            final org.springframework.boot.actuate.health.Health health = ctx.getBean(AimonHealthIndicator.class)
                    .health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsKey("degradations");
            assertThat(health.getDetails().get("degradations").toString()).contains("session-durability");
        });
    }

    @Test
    @DisplayName("without a meter registry bean the metrics branch stays off, and the context still starts")
    void metricsRequireARegistryBeanRatherThanTheLibrary(@TempDir Path workspace) {
        // Micrometer is on this test classpath, so @ConditionalOnClass alone would have made AimonMetrics a bean
        // with a required MeterRegistry parameter — and every application that has micrometer-core transitively
        // and no registry configured would fail its whole context with a message about metrics it never asked
        // for. That was a real failure here before @ConditionalOnBean was added.
        minimal(workspace).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(AimonMetrics.class);
        });
    }

    @Test
    @DisplayName("a registry bean gets the tenant-runtime meters bound to it")
    void metricsBindToAnApplicationRegistry(@TempDir Path workspace) {
        minimal(workspace).withUserConfiguration(WithRegistry.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(AimonMetrics.class);

            final MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            assertThat(registry.find(AimonMetrics.PREFIX + ".active").gauge()).isNotNull();
            assertThat(registry.find(AimonMetrics.PREFIX + ".leased").gauge()).isNotNull();
            assertThat(registry.find(AimonMetrics.PREFIX + ".max").gauge()).isNotNull();
            assertThat(registry.find(AimonMetrics.PREFIX + ".saturated").gauge()).isNotNull();
            assertThat(registry.find(AimonMetrics.PREFIX + ".exhausted").functionCounter()).isNotNull();
            assertThat(registry.find(AimonMetrics.PREFIX + ".provision.failed").functionCounter()).isNotNull();

            // A gauge that reads the stack on scrape rather than a number copied at bind time. The count is of
            // tenant runtimes, so it starts at zero with only the declared agent — and the whole value of these
            // meters is the moment it stops being zero, which a snapshot taken at bind time would never show.
            final AimonStack stack = ctx.getBean(AimonStack.class);
            assertThat(registry.find(AimonMetrics.PREFIX + ".active").gauge().value()).isZero();
            try (AgentRuntimeLease lease = stack.agentRuntimes()
                    .acquire(AgentRuntimeId.fromName("test-agent", "acme"))) {
                assertThat(lease.runtime()).isNotNull();
                assertThat(registry.find(AimonMetrics.PREFIX + ".active").gauge().value()).isEqualTo(1.0d);
                assertThat(registry.find(AimonMetrics.PREFIX + ".leased").gauge().value()).isEqualTo(1.0d);
            }
            // The two gauges part company here, which is the reason the second one exists: the runtime is still
            // held against the cap, and nobody is using it.
            assertThat(registry.find(AimonMetrics.PREFIX + ".active").gauge().value()).isEqualTo(1.0d);
            assertThat(registry.find(AimonMetrics.PREFIX + ".leased").gauge().value()).isZero();
        });
    }

    @Test
    @DisplayName("without Actuator the health branch backs off and metrics still bind")
    void healthBacksOffWithoutActuator(@TempDir Path workspace) {
        minimal(workspace).withUserConfiguration(WithRegistry.class)
                .withClassLoader(new FilteredClassLoader(HealthIndicator.class)).run(ctx -> {
                    assertThat(ctx).hasNotFailed().doesNotHaveBean(AimonHealthIndicator.class);
                    assertThat(ctx).hasSingleBean(AimonMetrics.class);
                });
    }

    @Test
    @DisplayName("without Micrometer the metrics branch backs off and health still reports")
    void metricsBackOffWithoutMicrometer(@TempDir Path workspace) {
        minimal(workspace).withClassLoader(new FilteredClassLoader(MeterRegistry.class)).run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean(AimonMetrics.class);
            assertThat(ctx).hasSingleBean(AimonHealthIndicator.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class WithRegistry {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
