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
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;

/**
 * A runtime whose build fails part-way leaves nothing open behind it (EE-23).
 *
 * <p>
 * The resources a runtime is built with — its execution environment provider, its control store — exist before the
 * runtime does. When a later step throws, there is no runtime to close them with, and for a tenant there is not even a
 * {@code ProvisionedAgentRuntime} to carry them. Each failed first request would then leave a provider (and the shell
 * processes behind it) running until the process exits. The provisioner closes them itself; these rows pin that, and
 * that the startup path, whose teardown plan would also have closed them, closes each exactly once.
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
    @DisplayName("a tenant whose build fails has its provider closed, and the next attempt builds afresh")
    void failedTenantBuildClosesItsProvider(@TempDir Path workspace) {
        final CountingProviders providers = new CountingProviders();
        final AtomicInteger failuresLeft = new AtomicInteger(1);
        final AgentRuntimeId acme = AgentRuntimeId.fromName("ops", "acme");
        final AimonStackSpec spec = AimonStackSpec.builder().workspaceRoot(workspace.toString())
                .llm(LlmSpec.of(STUB_LLM)).executionEnvironment(ExecutionEnvironmentSpec.factory(providers::create))
                .agent(AgentSpec.builder().bundle(bundle("ops")).addCustomizer(runtime -> {
                    if (runtime.getId().equals(acme) && failuresLeft.getAndDecrement() > 0) {
                        throw new IllegalStateException("customizer failed");
                    }
                }).build()).build();

        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            assertThatThrownBy(() -> stack.agentRuntimes().acquire(acme)).hasMessageContaining("customizer failed");

            assertThat(providers.closesOf(acme)).containsExactly(1);

            // Nothing half-built was kept: the retry creates a second provider and owns it like any other tenant.
            try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(acme)) {
                assertThat(lease.runtime().getId()).isEqualTo(acme);
            }
            assertThat(providers.closesOf(acme)).containsExactly(1, 0);
        }
    }

    @Test
    @DisplayName("a declared agent whose build fails fails the stack, and its provider is closed exactly once")
    void failedStartupBuildClosesItsProviderOnce(@TempDir Path workspace) {
        final CountingProviders providers = new CountingProviders();
        final AimonStackSpec spec = AimonStackSpec.builder().workspaceRoot(workspace.toString())
                .llm(LlmSpec.of(STUB_LLM)).executionEnvironment(ExecutionEnvironmentSpec.factory(providers::create))
                .agent(AgentSpec.of(bundle("ops")))
                .agent(AgentSpec.builder().bundle(bundle("audit")).addCustomizer(runtime -> {
                    throw new IllegalStateException("customizer failed");
                }).build()).build();

        assertThatThrownBy(() -> AimonStackBuilder.build(spec)).hasMessageContaining("customizer failed");

        // The failed runtime's provider is closed by the rollback and never reaches the teardown plan; the one built
        // before it is closed by the plan. Once each — a second close would mean both paths claimed it.
        assertThat(providers.closesOf(AgentRuntimeId.of("agent:audit"))).containsExactly(1);
        assertThat(providers.closesOf(AgentRuntimeId.of("agent:ops"))).containsExactly(1);
    }

    /** Hands out one closeable provider per call and remembers how often each was closed. */
    private static final class CountingProviders {

        private final Map<AgentRuntimeId, List<CountingProvider>> created = new ConcurrentHashMap<>();

        ExecutionEnvironmentProvider create(AgentRuntimeId id) {
            final CountingProvider provider = new CountingProvider();
            created.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(provider);
            return provider;
        }

        List<Integer> closesOf(AgentRuntimeId id) {
            return created.getOrDefault(id, List.of()).stream().map(p -> p.closes.get()).toList();
        }
    }

    private static final class CountingProvider implements ExecutionEnvironmentProvider, AutoCloseable {

        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public ExecutionEnvironment resolve(EnvironmentRequest request) {
            throw new UnsupportedOperationException("no execution runs in these tests");
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
