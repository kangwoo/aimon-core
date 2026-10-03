package at.aimon.core.environment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.RuntimeBinding;

@DisplayName("PerRuntimeLocalEnvironmentProvider — one provider, a local workspace per runtime id")
class PerRuntimeLocalEnvironmentProviderTest {

    private static final AgentRuntimeId ACME = AgentRuntimeId.fromName("ops", "acme");
    private static final AgentRuntimeId GLOBEX = AgentRuntimeId.fromName("ops", "globex");

    @TempDir
    Path root;

    private final List<AgentRuntimeId> built = new ArrayList<>();
    private PerRuntimeLocalEnvironmentProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PerRuntimeLocalEnvironmentProvider(id -> {
            built.add(id);
            return LocalExecutionEnvironmentProvider.builder()
                    .workspaceRoot(root.resolve(id.discriminator().orElse("default"))).contentSearch(false).build();
        });
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    private static EnvironmentRequest request(AgentRuntimeId id) {
        return EnvironmentRequest.builder().agentRuntimeId(id).build();
    }

    private static boolean available(ExecutionEnvironment environment) {
        return environment.fileSystem().getStatus().isAvailable();
    }

    @Test
    @DisplayName("each runtime id resolves to its own workspace, and the same id to the same one")
    void workspacePerRuntimeId() {
        final ExecutionEnvironment acme = provider.resolve(request(ACME));
        final ExecutionEnvironment globex = provider.resolve(request(GLOBEX));

        assertThat(acme.descriptor().workingDirectory()).isEqualTo(root.resolve("acme").toString());
        assertThat(globex.descriptor().workingDirectory()).isEqualTo(root.resolve("globex").toString());
        assertThat(provider.resolve(request(ACME))).isSameAs(acme);
        assertThat(provider.workspace(ACME).fileSystem()).isSameAs(acme.fileSystem());
        assertThat(built).containsExactly(ACME, GLOBEX);
    }

    @Test
    @DisplayName("a fork's request is answered with its parent environment, whatever runtime id it carries")
    void forkGetsItsParent() {
        final ExecutionEnvironment parent = provider.resolve(request(ACME)).isolate("k").orElseThrow();

        final ExecutionEnvironment forked = provider
                .resolve(EnvironmentRequest.builder().agentRuntimeId(GLOBEX).parent(parent).build());

        assertThat(forked).isSameAs(parent);
        assertThat(built).as("no workspace is made for the fork's runtime id").containsExactly(ACME);
    }

    @Test
    @DisplayName("the workspace of an id lives until its last binding closes")
    void slotLivesUntilTheLastBindingCloses() {
        final RuntimeBinding old = provider.bindRuntime(ACME);
        final RuntimeBinding successor = provider.bindRuntime(ACME);
        final ExecutionEnvironment acme = provider.resolve(request(ACME));

        // Two runtimes of one id overlap when an invalidated one is still held: the old one's close must not take
        // the workspace from the one that replaced it.
        old.close();
        assertThat(available(acme)).isTrue();
        assertThat(provider.resolve(request(ACME))).isSameAs(acme);

        successor.close();
        assertThat(available(acme)).isFalse();

        // The id is not retired with its slot: the next runtime gets a fresh workspace over the same directory.
        final ExecutionEnvironment rebuilt = provider.resolve(request(ACME));
        assertThat(rebuilt).isNotSameAs(acme);
        assertThat(available(rebuilt)).isTrue();
        assertThat(built).containsExactly(ACME, ACME);
    }

    @Test
    @DisplayName("closing one id's binding leaves every other id's workspace alone")
    void otherIdsAreUntouched() {
        final RuntimeBinding acmeBinding = provider.bindRuntime(ACME);
        final RuntimeBinding globexBinding = provider.bindRuntime(GLOBEX);
        final ExecutionEnvironment acme = provider.resolve(request(ACME));
        final ExecutionEnvironment globex = provider.resolve(request(GLOBEX));

        acmeBinding.close();

        assertThat(available(acme)).isFalse();
        assertThat(available(globex)).isTrue();
        globexBinding.close();
    }

    @Test
    @DisplayName("closing a binding twice releases its share once")
    void bindingCloseIsIdempotent() {
        final RuntimeBinding first = provider.bindRuntime(ACME);
        final RuntimeBinding second = provider.bindRuntime(ACME);
        final ExecutionEnvironment acme = provider.resolve(request(ACME));

        first.close();
        first.close();

        // A double close counted twice would have dropped the slot the second binding still needs.
        assertThat(available(acme)).isTrue();
        second.close();
        assertThat(available(acme)).isFalse();
    }

    @Test
    @DisplayName("an id nobody bound still resolves, and its workspace stays until the provider closes")
    void resolveWithoutBinding() {
        final ExecutionEnvironment acme = provider.resolve(request(ACME));
        assertThat(available(acme)).isTrue();

        provider.close();

        assertThat(available(acme)).isFalse();
    }

    @Test
    @DisplayName("a closed provider refuses to resolve or bind, and a binding closed after it does nothing")
    void closedProvider() {
        final RuntimeBinding binding = provider.bindRuntime(ACME);
        final ExecutionEnvironment acme = provider.resolve(request(ACME));

        provider.close();
        provider.close();

        assertThat(available(acme)).isFalse();
        // The executor turns this into an UnavailableExecutionEnvironment; it must not quietly rebuild a workspace.
        assertThatThrownBy(() -> provider.resolve(request(ACME))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> provider.bindRuntime(GLOBEX)).isInstanceOf(IllegalStateException.class);
        binding.close();
        assertThat(built).containsExactly(ACME);
    }

    @Test
    @DisplayName("a workspace that cannot be built fails the binding and leaves no slot behind")
    void failedWorkspaceBuild() {
        final PerRuntimeLocalEnvironmentProvider failing = new PerRuntimeLocalEnvironmentProvider(id -> {
            throw new IllegalStateException("cannot create the workspace of " + id);
        });

        assertThatThrownBy(() -> failing.bindRuntime(ACME)).hasMessageContaining("cannot create the workspace");
        assertThatThrownBy(() -> failing.resolve(request(ACME))).hasMessageContaining("cannot create the workspace");
        failing.close();
    }
}
