package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.InMemoryRepresentationStore;
import at.aimon.core.memory.MemoryInjectionMode;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.redaction.DefaultRedactionPolicy;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.memory.redaction.StrictRedactionPolicy;
import at.aimon.spring.boot.autoconfigure.AimonMemoryAutoConfiguration.MemoryContribution;

/**
 * The memory slice on its own, with no stack behind it.
 *
 * <p>
 * Three selectors resolved against three beans, which is why this file is longer than its knowledge counterpart.
 * Most of what is asserted here is a refusal: the slice has more ways to be configured into doing nothing than it
 * has ways to be configured wrongly enough to throw on its own, and every one of those has to be turned into a
 * named startup failure by this class or it becomes an empty memory that nobody can tell from a cold one.
 *
 * <p>
 * The properties bean is registered by the slice's {@code @EnableConfigurationProperties}, so
 * {@code validateMemory()} runs in every case below. Cases that are about the binding rather than about the
 * wiring live in {@link AimonPropertiesValidationTest}; the two files overlap only where a rule is enforced twice
 * on purpose.
 */
class AimonMemorySliceTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AimonMemoryAutoConfiguration.class))
            .withPropertyValues("aimon.workspace.root=/workspace", "aimon.agent-defaults.default-agent=test-agent");

    /** The two properties every configured case needs, so no test repeats them to say something else. */
    private final ApplicationContextRunner configured = runner.withPropertyValues("aimon.memory.workspace-id=ops",
            "aimon.memory.peer-id=alice");

    @Test
    @DisplayName("no backend named means no stores and a contribution that says so")
    void memoryIsOffByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(RepresentationStore.class);
            assertThat(ctx).doesNotHaveBean(ObservationStore.class);
            assertThat(ctx).hasSingleBean(MemoryContribution.class);
            assertThat(ctx.getBean(MemoryContribution.class).getSpec()).isNull();
        });
    }

    @Test
    @DisplayName("in-memory builds both stores and files them under the named workspace and peer")
    void inMemoryBuildsBothStores() {
        configured.withPropertyValues("aimon.memory.backend=in-memory").run(ctx -> {
            assertThat(ctx.getBean(RepresentationStore.class)).isInstanceOf(InMemoryRepresentationStore.class);
            assertThat(ctx.getBean(ObservationStore.class)).isInstanceOf(InMemoryObservationStore.class);

            final MemorySpec spec = ctx.getBean(MemoryContribution.class).getSpec();
            assertThat(spec.getWorkspace().getId()).isEqualTo("ops");
            assertThat(spec.getFixedPeer()).hasValueSatisfying(peer -> assertThat(peer.getId()).isEqualTo("alice"));
            assertThat(spec.isPerCaller()).isFalse();
            // Same instances, not merely the same types: two stores would mean observations written into one and
            // read back from the other, which reads as a memory that never remembers anything.
            assertThat(spec.getRepresentationStore()).hasValue(ctx.getBean(RepresentationStore.class));
            assertThat(spec.getObservationStore()).hasValue(ctx.getBean(ObservationStore.class));
        });
    }

    @Test
    @DisplayName("the settings with defaults land on the spec as those defaults")
    void unsetSettingsTakeTheirDefaults() {
        configured.withPropertyValues("aimon.memory.backend=in-memory").run(ctx -> {
            final MemorySpec spec = ctx.getBean(MemoryContribution.class).getSpec();
            assertThat(spec.getInjectionMode()).isEqualTo(MemoryInjectionMode.SUMMARY_ONLY);
            assertThat(spec.getMaxTokens()).isZero();
            // The one default in this slice that is a choice rather than a passthrough. See MemoryRedaction.
            assertThat(spec.getRedactionPolicy()).containsInstanceOf(DefaultRedactionPolicy.class);
        });
    }

    @Test
    @DisplayName("the settings that are set land on the spec as set")
    void settingsReachTheSpec() {
        configured.withPropertyValues("aimon.memory.backend=in-memory", "aimon.memory.injection-mode=full",
                "aimon.memory.max-tokens=2000", "aimon.memory.redaction=strict").run(ctx -> {
                    final MemorySpec spec = ctx.getBean(MemoryContribution.class).getSpec();
                    assertThat(spec.getInjectionMode()).isEqualTo(MemoryInjectionMode.FULL);
                    assertThat(spec.getMaxTokens()).isEqualTo(2000);
                    assertThat(spec.getRedactionPolicy()).containsInstanceOf(StrictRedactionPolicy.class);
                });
    }

    @Test
    @DisplayName("redaction=none stores observations verbatim, and the spec carries that as an absence")
    void redactionNoneLeavesThePolicyAbsent() {
        configured.withPropertyValues("aimon.memory.backend=in-memory", "aimon.memory.redaction=none").run(ctx -> {
            assertThat(ctx.getBean(MemoryContribution.class).getSpec().getRedactionPolicy()).isEmpty();
        });
    }

    @Test
    @DisplayName("supplied adopts the application's stores and builds none of its own")
    void suppliedAdoptsTheApplicationBeans() {
        configured.withUserConfiguration(SuppliedStores.class).withPropertyValues("aimon.memory.backend=supplied")
                .run(ctx -> {
                    final MemorySpec spec = ctx.getBean(MemoryContribution.class).getSpec();
                    assertThat(spec.getRepresentationStore()).hasValue(ctx.getBean(RepresentationStore.class));
                    assertThat(spec.getObservationStore()).hasValue(ctx.getBean(ObservationStore.class));
                    assertThat(ctx.getBean(RepresentationStore.class))
                            .isNotInstanceOf(InMemoryRepresentationStore.class);
                });
    }

    @Test
    @DisplayName("supplied takes one store when that is all there is")
    void suppliedAcceptsASingleStore() {
        // Not an oversight to be rejected: a representation store alone is the injected memory part with no
        // tools, which is what a deployment whose representations are derived elsewhere actually wants.
        configured.withUserConfiguration(RepresentationsOnly.class).withPropertyValues("aimon.memory.backend=supplied")
                .run(ctx -> {
                    final MemorySpec spec = ctx.getBean(MemoryContribution.class).getSpec();
                    assertThat(spec.getRepresentationStore()).isPresent();
                    assertThat(spec.getObservationStore()).isEmpty();
                });
    }

    @Test
    @DisplayName("supplied with nothing supplied fails, naming the value that would have built both")
    void suppliedWithoutAnyStoreFailsByName() {
        configured.withPropertyValues("aimon.memory.backend=supplied")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.MEMORY_BACKEND)
                        .hasStackTraceContaining(RepresentationStore.class.getName()));
    }

    @Test
    @DisplayName("a store bean under backend=none is refused rather than quietly ignored")
    void storesWithNoBackendAreRefusedByName() {
        // The expensive half is the observation store: an application can write into one for months through its
        // own code while no agent is ever handed a tool that reads it back, and nothing errors.
        runner.withUserConfiguration(SuppliedStores.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.MEMORY_BACKEND)
                        .hasStackTraceContaining(ObservationStore.class.getName()));
    }

    @Test
    @DisplayName("caller mode with only an observation store wires nothing, and says so by name")
    void callerModeWithoutRepresentationsFailsByName() {
        // The one combination where both halves are missing: caller mode registers no tools, so the observation
        // store has no writer, and with no representation store there is no part to inject either.
        runner.withUserConfiguration(ObservationsOnly.class)
                .withPropertyValues("aimon.memory.backend=supplied", "aimon.memory.workspace-id=ops",
                        "aimon.memory.peer-mode=caller")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.MEMORY_PEER_MODE)
                        .hasStackTraceContaining(AimonProperties.MEMORY_PEER_ID));
    }

    @Test
    @DisplayName("caller mode builds a per-caller spec with no fixed peer")
    void callerModeBuildsAPerCallerSpec() {
        runner.withPropertyValues("aimon.memory.backend=in-memory", "aimon.memory.workspace-id=ops",
                "aimon.memory.peer-mode=caller").run(ctx -> {
                    final MemorySpec spec = ctx.getBean(MemoryContribution.class).getSpec();
                    assertThat(spec.isPerCaller()).isTrue();
                    assertThat(spec.getFixedPeer()).isEmpty();
                });
    }

    @Test
    @DisplayName("a policy bean under any value but supplied is refused rather than left unconsulted")
    void aPolicyBeanUnderTheDefaultIsRefusedByName() {
        // A RedactionPolicy that is defined and never called looks exactly like one that is called, right up
        // until someone reads the stored observations.
        configured.withUserConfiguration(SuppliedPolicy.class).withPropertyValues("aimon.memory.backend=in-memory")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.MEMORY_REDACTION)
                        .hasStackTraceContaining(RedactionPolicy.class.getName()));
    }

    @Test
    @DisplayName("redaction=supplied with no policy bean fails by name")
    void suppliedRedactionWithoutABeanFailsByName() {
        configured.withPropertyValues("aimon.memory.backend=in-memory", "aimon.memory.redaction=supplied")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.MEMORY_REDACTION)
                        .hasStackTraceContaining(RedactionPolicy.class.getName()));
    }

    @Test
    @DisplayName("redaction=supplied adopts the application's policy")
    void suppliedRedactionAdoptsTheApplicationBean() {
        configured.withUserConfiguration(SuppliedPolicy.class)
                .withPropertyValues("aimon.memory.backend=in-memory", "aimon.memory.redaction=supplied").run(ctx -> {
                    assertThat(ctx.getBean(MemoryContribution.class).getSpec().getRedactionPolicy())
                            .hasValue(ctx.getBean(RedactionPolicy.class));
                });
    }

    @Test
    @DisplayName("application stores win over the in-memory branches")
    void applicationStoresBackOffTheInMemoryBranches() {
        // @ConditionalOnMissingBean on both branches, asserted rather than assumed: the alternative is two beans
        // per interface and an injection point that fails on ambiguity at the far end of the context.
        configured.withUserConfiguration(SuppliedStores.class).withPropertyValues("aimon.memory.backend=in-memory")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RepresentationStore.class);
                    assertThat(ctx).hasSingleBean(ObservationStore.class);
                    assertThat(ctx.getBean(RepresentationStore.class))
                            .isNotInstanceOf(InMemoryRepresentationStore.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class SuppliedStores {

        @Bean
        RepresentationStore applicationRepresentationStore() {
            return mock(RepresentationStore.class);
        }

        @Bean
        ObservationStore applicationObservationStore() {
            return mock(ObservationStore.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RepresentationsOnly {

        @Bean
        RepresentationStore applicationRepresentationStore() {
            return mock(RepresentationStore.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ObservationsOnly {

        @Bean
        ObservationStore applicationObservationStore() {
            return mock(ObservationStore.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SuppliedPolicy {

        @Bean
        RedactionPolicy applicationRedactionPolicy() {
            return mock(RedactionPolicy.class);
        }
    }
}
