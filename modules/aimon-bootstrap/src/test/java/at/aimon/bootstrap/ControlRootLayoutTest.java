package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.AgentWorkspaceLayout;
import at.aimon.bootstrap.spec.ExecutionEnvironmentSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileAccessDeniedException;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.subagent.task.VfsSessionSnapshotStore;
import at.aimon.core.subagent.task.VfsTaskOutputStore;
import at.aimon.core.subagent.task.VfsTaskResultStore;
import at.aimon.core.workflow.impl.VfsStepResultCache;

/**
 * The control store's root moved from "the workspace" to "the workspace's {@code .aimon/}" while every default
 * directory lost its {@code .aimon/} prefix (execution-environment design §9.2). The two halves have to move together:
 * one without the other lands data at {@code .aimon/.aimon/...} and silently orphans what users already have. These
 * tests drive real writes through each control-plane store of a stack-built runtime and check the physical paths.
 */
@DisplayName("A local stack keeps its control store at {workspace}/.aimon — no doubled root, existing data found")
class ControlRootLayoutTest {

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

    private static AimonStackSpec.Builder spec(Path workspace) {
        return AimonStackSpec.builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(STUB_LLM))
                .executionEnvironment(ExecutionEnvironmentSpec.builder().contentSearch(false).build())
                .agent(AgentSpec.of(bundle("ops"))).agent(AgentSpec.of(bundle("audit")));
    }

    @Test
    @DisplayName("control-plane stores write under .aimon/, a pre-existing user skill is found, and there is no .aimon/.aimon")
    void controlStoresLandAtTheOldPhysicalPaths(@TempDir Path root) throws Exception {
        final AgentRuntimeId ops = AgentRuntimeId.fromName("ops");
        final Path workspace = Path.of(AgentWorkspaceLayout.resolve(root.toString(), ops));
        // Data a user already has, at the location it has always had.
        final Path existingSkill = workspace.resolve(".aimon/skills/existing/SKILL.md");
        Files.createDirectories(existingSkill.getParent());
        Files.writeString(existingSkill,
                "---\nname: existing\ndescription: \"Already on disk before the upgrade\"\n---\nDo the thing.\n",
                StandardCharsets.UTF_8);

        try (AimonStack stack = AimonStackBuilder.build(spec(root).build())) {
            final OrcaAgentRuntime runtime = stack.runtime(ops).orElseThrow();

            assertThat(runtime.getSkillRegistry().getSkill("existing")).as("the pre-seeded user skill").isPresent();
            assertThat(runtime.getSkillRegistry().getSkill("existing").orElseThrow().getStagedResource()).isPresent();

            new VfsTaskOutputStore(runtime.getControlFileSystem()).append("t1", "chunk");
            new VfsStepResultCache(runtime.getControlFileSystem());
            assertThat(VfsTaskResultStore.DEFAULT_BASE_DIR).isEqualTo("task-result");
            assertThat(VfsSessionSnapshotStore.DEFAULT_BASE_DIR).isEqualTo("task-snapshot");
            assertThat(VfsStepResultCache.DEFAULT_BASE_DIR).isEqualTo("step-cache");

            assertThat(Files.isDirectory(workspace.resolve(".aimon/task-output"))).isTrue();
            assertThat(Files.exists(workspace.resolve(".aimon/.aimon"))).as("no doubled control root").isFalse();
            assertThat(Files.exists(workspace.resolve("task-output"))).as("nothing at the workspace root").isFalse();
        }
    }

    @Test
    @DisplayName("each runtime gets its own provider over its own workspace, and its file tools cannot see .aimon/")
    void providerPerRuntimeHidesTheControlStore(@TempDir Path root) throws Exception {
        try (AimonStack stack = AimonStackBuilder.build(spec(root).build())) {
            final ExecutionEnvironment ops = environmentOf(stack, AgentRuntimeId.fromName("ops"));
            final ExecutionEnvironment audit = environmentOf(stack, AgentRuntimeId.fromName("audit"));

            assertThat(ops.descriptor().workingDirectory())
                    .isEqualTo(Path.of(AgentWorkspaceLayout.resolve(root.toString(), AgentRuntimeId.fromName("ops")))
                            .toAbsolutePath().normalize().toString());
            assertThat(audit.descriptor().workingDirectory()).isNotEqualTo(ops.descriptor().workingDirectory());

            ops.fileSystem().write("notes.txt", "visible");
            stack.runtime(AgentRuntimeId.fromName("ops")).orElseThrow().getControlFileSystem().write("probe.txt",
                    "control");
            assertThat(ops.fileSystem().exists("notes.txt")).isTrue();
            assertThat(ops.fileSystem().exists(".aimon/probe.txt")).as("the control store is hidden").isFalse();
            assertThatThrownBy(() -> ops.fileSystem().read(".aimon/probe.txt"))
                    .isInstanceOf(FileAccessDeniedException.class);
        }
    }

    @Test
    @DisplayName("EE-22: with a caller's provider the stack does not know the workspace, and fileSystem(id) is the control store")
    void callerProviderLeavesFileSystemAtTheControlStore(@TempDir Path root, @TempDir Path elsewhere) throws Exception {
        final AgentRuntimeId ops = AgentRuntimeId.fromName("ops");
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(elsewhere).contentSearch(false).build();
                AimonStack stack = AimonStackBuilder
                        .build(spec(root).executionEnvironment(ExecutionEnvironmentSpec.shared(provider)).build())) {
            final VirtualFileSystem answered = stack.fileSystem(ops).orElseThrow();
            answered.write("probe.txt", "where does this land");

            final Path workspace = Path.of(AgentWorkspaceLayout.resolve(root.toString(), ops));
            assertThat(workspace.resolve(".aimon/probe.txt")).as("inside the control store").exists();
            assertThat(workspace.resolve("probe.txt")).doesNotExist();
            assertThat(elsewhere.resolve("probe.txt")).as("the provider's workspace is not reached").doesNotExist();
            assertThat(stack.runtime(ops).orElseThrow().getControlFileSystem().exists("probe.txt")).isTrue();
        }
    }

    private static ExecutionEnvironment environmentOf(AimonStack stack, AgentRuntimeId id) {
        final OrcaAgentRuntime runtime = stack.runtime(id).orElseThrow();
        return runtime.getExecutionEnvironmentProvider()
                .resolve(EnvironmentRequest.builder().agentRuntimeId(id).agent(runtime.getAgent()).build());
    }
}
