package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;

/**
 * The direct-core embedding path of {@code docs/getting-started/embedding-agent-in-application.md} §A (review 2,
 * blocking 3). The manager used to default to a factory without an {@code ExecutionEnvironmentProvider}, so the guide's
 * snippet built fine and then failed on the first {@code getOrCreateRuntime(...)}. It now refuses at build time, and
 * the guide's corrected snippet — a provider over the workspace, the control root passed to
 * {@code getOrCreateRuntime} — builds a runtime that finds the skills an embedder already keeps under
 * {@code {workspace}/.aimon/skills}.
 */
@DisplayName("OrcaAgentRuntimeManager — direct-core embedding")
class OrcaAgentRuntimeManagerEmbeddingTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("building without a factory is refused with a message that names the provider")
    void noFactoryRefused() {
        assertThatThrownBy(() -> OrcaAgentRuntimeManager.builder().agentExecutor(executor()).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("withExecutionEnvironmentProvider");
    }

    @Test
    @DisplayName("building with a factory that has no provider is refused at build time")
    void factoryWithoutProviderRefused() {
        assertThatThrownBy(() -> OrcaAgentRuntimeManager.builder().agentExecutor(executor())
                .agentRuntimeFactory(new OrcaAgentRuntimeFactory()).build()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ExecutionEnvironmentProvider");
    }

    @Test
    @DisplayName("the guide's snippet creates a runtime whose control root still holds {workspace}/.aimon/skills")
    void guideSnippetWorks() throws IOException {
        Files.createDirectories(workspace.resolve(".aimon/skills/embed-demo"));
        Files.writeString(workspace.resolve(".aimon/skills/embed-demo/SKILL.md"),
                "---\nname: embed-demo\ndescription: A skill the embedder already had\n---\nDo the thing.\n");
        final LocalFileSystem controlFileSystem = new LocalFileSystem(
                new LocalFileSystemConfig(workspace.resolve(".aimon").toString()));
        controlFileSystem.initialize();

        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace).contentSearch(false).build()) {
            final OrcaAgentRuntimeManager manager = OrcaAgentRuntimeManager.builder().agentExecutor(executor())
                    .agentRuntimeFactory(new OrcaAgentRuntimeFactory().withExecutionEnvironmentProvider(provider))
                    .build();
            final Agent agent = DefaultAgent.builder().name("embedded").maxIterations(3).systemPrompt("test").build();

            final OrcaAgentRuntime runtime = manager.getOrCreateRuntime(AgentBundle.builder().agent(agent).build(),
                    controlFileSystem, null);

            assertThat(runtime.getExecutionEnvironmentProvider()).isSameAs(provider);
            assertThat(runtime.getControlFileSystem()).isSameAs(controlFileSystem);
            assertThat(runtime.getSkillRegistry().getSkill("embed-demo")).isPresent();
            manager.destroyRuntime(runtime.getId());
        } finally {
            controlFileSystem.close();
        }
    }

    private static OrcaAgentExecutor executor() {
        final LlmClient client = org.mockito.Mockito.mock(LlmClient.class);
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, new DefaultCommandExecutionManager(client),
                new DefaultSubagentExecutionManager(client, toolManager, hookManager));
    }
}
