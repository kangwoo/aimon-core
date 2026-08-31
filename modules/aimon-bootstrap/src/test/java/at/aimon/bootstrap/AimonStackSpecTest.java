package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.ExecutorSpec;
import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.bootstrap.spec.SchedulingSpec;
import at.aimon.bootstrap.spec.SkillApprovalSpec;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.memory.MemoryContextProvider;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;

/** Validation lives in the spec, so it can be exercised without standing anything up. */
class AimonStackSpecTest {

    /** Never called — the spec only holds the reference. */
    private static final LlmClient STUB_LLM = new LlmClient() {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

    };

    private static AimonStackSpec.Builder minimal() {
        return AimonStackSpec.builder().workspaceRoot("/tmp/aimon-test").llm(LlmSpec.of(STUB_LLM))
                .agent(AgentSpec.named("ops"));
    }

    @Test
    @DisplayName("a minimal spec fills in every optional part")
    void appliesDefaults() {
        final AimonStackSpec spec = minimal().build();

        assertThat(spec.getAgents()).hasSize(1);
        assertThat(spec.getFileSystem().isStackOwned()).isTrue();
        assertThat(spec.getFileSystem().getWorkspaceRoot()).contains("/tmp/aimon-test");
        assertThat(spec.getSession().getRecordStore()).isEmpty();
        assertThat(spec.getSkillApproval().getChannelMode()).isEqualTo(SkillApprovalSpec.ChannelMode.DENY_ALL);
        assertThat(spec.getScheduling().isEnabled()).isFalse();
        assertThat(spec.getCredentialStore()).isEmpty();
    }

    @Test
    @DisplayName("rejects a spec with no LLM")
    void requiresLlm() {
        assertThatThrownBy(() -> AimonStackSpec.builder().workspaceRoot("/tmp").agent(AgentSpec.named("ops")).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("LLM spec is required");
    }

    @Test
    @DisplayName("rejects a spec with no agent")
    void requiresAgent() {
        assertThatThrownBy(() -> AimonStackSpec.builder().workspaceRoot("/tmp").llm(LlmSpec.of(STUB_LLM)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At least one AgentSpec");
    }

    @Test
    @DisplayName("rejects a spec with neither a workspace root nor a file system")
    void requiresWorkspaceRootOrFileSystem() {
        assertThatThrownBy(
                () -> AimonStackSpec.builder().llm(LlmSpec.of(STUB_LLM)).agent(AgentSpec.named("ops")).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workspaceRoot");
    }

    @Test
    @DisplayName("an explicit file system spec makes the workspace root optional")
    void explicitFileSystemNeedsNoWorkspaceRoot() {
        final AimonStackSpec spec = AimonStackSpec.builder().llm(LlmSpec.of(STUB_LLM)).agent(AgentSpec.named("ops"))
                .fileSystem(FileSystemSpec.localAt("/srv/aimon")).build();

        assertThat(spec.getWorkspaceRoot()).isEmpty();
        assertThat(spec.getFileSystem().getWorkspaceRoot()).contains("/srv/aimon");
    }

    @Test
    @DisplayName("rejects two agents that would resolve to the same runtime id")
    void rejectsDuplicateRuntimeIdentity() {
        assertThatThrownBy(() -> minimal().agent(AgentSpec.named("ops")).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate agent runtime identity 'agent:ops'");
    }

    @Test
    @DisplayName("the same agent name with different discriminators is not a duplicate")
    void discriminatorDisambiguates() {
        final AimonStackSpec spec = minimal().agent(AgentSpec.builder().name("ops").discriminator("tenant-a").build())
                .build();

        assertThat(spec.getAgents()).hasSize(2);
    }

    @Test
    @DisplayName("scheduling can be enabled without supplying a scheduler")
    void schedulingEnabledWithoutScheduler() {
        final AimonStackSpec spec = minimal().scheduling(SchedulingSpec.enabled()).build();

        assertThat(spec.getScheduling().isEnabled()).isTrue();
        assertThat(spec.getScheduling().getTaskScheduler()).isEmpty();
    }

    @Test
    @DisplayName("rejects a memory spec and an explicit memory context provider together")
    void rejectsTwoMemoryContextProviders() {
        // The executor takes exactly one provider, so one of the two would be dropped — and a dropped provider
        // does not fail. It produces a memory part that is present, plausible, and read from the other store.
        final MemorySpec memory = MemorySpec.forPeer(Workspace.builder().id("ws-1").build(), Principal.user("kangwoo"))
                .representationStore(mock(RepresentationStore.class)).build();

        assertThatThrownBy(() -> minimal().memory(memory)
                .executor(ExecutorSpec.builder().memoryContextProvider(mock(MemoryContextProvider.class)).build())
                .build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not both");
    }

    @Test
    @DisplayName("a memory spec without a representation store coexists with an explicit provider")
    void observationOnlyMemoryCoexistsWithAnExplicitProvider() {
        // Nothing collides: this spec wires only the tools, and the injected part is the caller's provider.
        final MemorySpec memory = MemorySpec.forPeer(Workspace.builder().id("ws-1").build(), Principal.user("kangwoo"))
                .observationStore(mock(ObservationStore.class)).build();

        final AimonStackSpec spec = minimal().memory(memory)
                .executor(ExecutorSpec.builder().memoryContextProvider(mock(MemoryContextProvider.class)).build())
                .build();

        assertThat(spec.getMemory()).contains(memory);
        assertThat(spec.getExecutor().getMemoryContextProvider()).isPresent();
    }
}
