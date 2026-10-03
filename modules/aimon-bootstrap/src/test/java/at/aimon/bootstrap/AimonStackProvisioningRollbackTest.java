package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.runtime.AgentRuntimeLease;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.ExecutionEnvironmentSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.RuntimeBinding;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;

/**
 * A runtime whose build fails part-way leaves nothing open behind it (EE-23), and the stack's execution environment
 * provider is closed by whoever owns it (EE-7).
 *
 * <p>
 * The resources a runtime is built with — its binding to the execution environment provider, its control store —
 * exist before the runtime does. When a later step throws, there is no runtime to close them with, and for a tenant
 * there is not even a {@code ProvisionedAgentRuntime} to carry them. Each failed first request would then leave the
 * provider holding that tenant's share until the process exits. The provisioner closes them itself; these rows pin
 * that, and that the startup path, whose teardown plan would also have closed them, closes each exactly once.
 */
class AimonStackProvisioningRollbackTest {

    private static final LlmClient STUB_LLM = new LlmClient() {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    };

    private static AgentBundle bundle(String name) {
        return AgentBundle.builder()
                .agent(DefaultAgent.builder().name(name).systemPrompt("You are " + name + ".").maxIterations(5).build())
                .build();
    }

    @Test
    @DisplayName("a tenant whose build fails has its binding closed, and the next attempt binds afresh")
    void failedTenantBuildClosesItsBinding(@TempDir Path workspace) {
        final CountingProvider provider = new CountingProvider();
        final AtomicInteger failuresLeft = new AtomicInteger(1);
        final AgentRuntimeId acme = AgentRuntimeId.fromName("ops", "acme");
        final AimonStackSpec spec = AimonStackSpec.builder().workspaceRoot(workspace.toString())
                .llm(LlmSpec.of(STUB_LLM)).executionEnvironment(ExecutionEnvironmentSpec.provider(() -> provider))
                .agent(AgentSpec.builder().bundle(bundle("ops")).addCustomizer(runtime -> {
                    if (runtime.getId().equals(acme) && failuresLeft.getAndDecrement() > 0) {
                        throw new IllegalStateException("customizer failed");
                    }
                }).build()).build();

        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            assertThatThrownBy(() -> stack.agentRuntimes().acquire(acme)).hasMessageContaining("customizer failed");

            assertThat(provider.closesOf(acme)).containsExactly(1);

            // Nothing half-built was kept: the retry binds a second time and owns that binding like any other tenant.
            try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(acme)) {
                assertThat(lease.runtime().getId()).isEqualTo(acme);
            }
            assertThat(provider.closesOf(acme)).containsExactly(1, 0);
            // A failed tenant is no reason to close what every other runtime resolves its environment from.
            assertThat(provider.closes).hasValue(0);
        }
    }

    @Test
    @DisplayName("a declared agent whose build fails fails the stack, and each binding and the provider close once")
    void failedStartupBuildClosesEachBindingOnce(@TempDir Path workspace) {
        final CountingProvider provider = new CountingProvider();
        final AimonStackSpec spec = AimonStackSpec.builder().workspaceRoot(workspace.toString())
                .llm(LlmSpec.of(STUB_LLM)).executionEnvironment(ExecutionEnvironmentSpec.provider(() -> provider))
                .agent(AgentSpec.of(bundle("ops")))
                .agent(AgentSpec.builder().bundle(bundle("audit")).addCustomizer(runtime -> {
                    throw new IllegalStateException("customizer failed");
                }).build()).build();

        assertThatThrownBy(() -> AimonStackBuilder.build(spec)).hasMessageContaining("customizer failed");

        // The failed runtime's binding is closed by the rollback and never reaches the teardown plan; the one built
        // before it is closed by the plan. Once each — a second close would mean both paths claimed it.
        assertThat(provider.closesOf(AgentRuntimeId.of("agent:audit"))).containsExactly(1);
        assertThat(provider.closesOf(AgentRuntimeId.of("agent:ops"))).containsExactly(1);
        // The provider was built for the stack that failed to come up, so the same teardown plan closes it — after
        // the bindings made on it.
        assertThat(provider.closes).hasValue(1);
        assertThat(provider.bindingsOpenAtClose).isZero();
    }

    @Test
    @DisplayName("a provider from provider(...) is closed with the stack; evicting a tenant closes only its binding")
    void suppliedProviderIsClosedByTheStackNotByEviction(@TempDir Path workspace) {
        final CountingProvider provider = new CountingProvider();
        final AgentRuntimeId acme = AgentRuntimeId.fromName("ops", "acme");
        final AimonStackSpec spec = AimonStackSpec.builder().workspaceRoot(workspace.toString())
                .llm(LlmSpec.of(STUB_LLM)).executionEnvironment(ExecutionEnvironmentSpec.provider(() -> provider))
                .agent(AgentSpec.of(bundle("ops"))).build();

        final AimonStack stack = AimonStackBuilder.build(spec);
        try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(acme)) {
            assertThat(lease.runtime().getId()).isEqualTo(acme);
        }

        assertThat(stack.agentRuntimes().invalidate(acme)).isTrue();

        // Eviction is a notice to the provider, not its end: before EE-7 this closed the tenant's whole provider.
        assertThat(provider.closesOf(acme)).containsExactly(1);
        assertThat(provider.closes).hasValue(0);

        stack.close();

        assertThat(provider.closes).hasValue(1);
        assertThat(provider.closesOf(AgentRuntimeId.of("agent:ops"))).containsExactly(1);
        assertThat(provider.bindingsOpenAtClose).as("bindings close before the provider they were made on").isZero();
    }

    @Test
    @DisplayName("a provider from shared(...) is bound like any other and never closed by the stack")
    void sharedProviderIsNeverClosed(@TempDir Path workspace) {
        final CountingProvider provider = new CountingProvider();
        final AimonStackSpec spec = AimonStackSpec.builder().workspaceRoot(workspace.toString())
                .llm(LlmSpec.of(STUB_LLM)).executionEnvironment(ExecutionEnvironmentSpec.shared(provider))
                .agent(AgentSpec.of(bundle("ops"))).build();

        AimonStackBuilder.build(spec).close();

        assertThat(provider.closesOf(AgentRuntimeId.of("agent:ops"))).containsExactly(1);
        assertThat(provider.closes).hasValue(0);
    }

    /** One closeable provider that remembers each binding it handed out and how often each was closed. */
    private static final class CountingProvider implements ExecutionEnvironmentProvider, AutoCloseable {

        private final Map<AgentRuntimeId, List<AtomicInteger>> bindings = new ConcurrentHashMap<>();
        private final AtomicInteger closes = new AtomicInteger();
        private volatile long bindingsOpenAtClose = -1;

        @Override
        public ExecutionEnvironment resolve(EnvironmentRequest request) {
            throw new UnsupportedOperationException("no execution runs in these tests");
        }

        @Override
        public RuntimeBinding bindRuntime(AgentRuntimeId agentRuntimeId) {
            final AtomicInteger closed = new AtomicInteger();
            bindings.computeIfAbsent(agentRuntimeId, k -> new CopyOnWriteArrayList<>()).add(closed);
            return closed::incrementAndGet;
        }

        List<Integer> closesOf(AgentRuntimeId id) {
            return bindings.getOrDefault(id, List.of()).stream().map(AtomicInteger::get).toList();
        }

        @Override
        public void close() {
            bindingsOpenAtClose = bindings.values().stream().flatMap(List::stream).filter(c -> c.get() == 0).count();
            closes.incrementAndGet();
        }
    }
}
