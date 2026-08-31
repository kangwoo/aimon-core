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
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.credential.InMemoryCredentialStore;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llms.anthropic.AnthropicLlmClient;
import at.aimon.core.llms.openai.OpenAILlmClient;
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
        minimal(workspace).withPropertyValues("aimon.llm.provider=openai")
                .run(ctx -> assertThat(ctx).getBean(LlmClient.class).isInstanceOf(OpenAILlmClient.class));
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
