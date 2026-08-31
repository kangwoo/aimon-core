package at.aimon.core.agent.impl.orca.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.impl.orca.tool.OrcaFileToolProvider;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.orca.OrcaProviderDependencies;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.artifact.ArtifactAwareEditTool;
import at.aimon.core.tools.artifact.ArtifactAwareWriteTool;
import at.aimon.core.tools.file.EditTool;
import at.aimon.core.tools.file.GrepTool;
import at.aimon.core.tools.file.ReadTool;
import at.aimon.core.tools.file.WriteTool;

@DisplayName("WorktreeToolEnvironmentFactory — per-branch scoped file-tool registry (design §6.3)")
class WorktreeToolEnvironmentFactoryTest {

    @Test
    @DisplayName("derive rebinds file tools to the branch subtree, carries non-file tools, preserves the signal")
    void deriveScopesFileToolsAndPreservesSignal(@TempDir Path tempDir) {
        final LocalFileSystem baseVfs = newVfs(tempDir);

        final DefaultToolRegistry baseRegistry = new DefaultToolRegistry();
        baseRegistry.register(new ReadTool(baseVfs));
        baseRegistry.register(new WriteTool(baseVfs));
        baseRegistry.register(new EditTool(baseVfs));
        baseRegistry.register(new GrepTool(baseVfs));
        baseRegistry.register(namedTool("Dummy")); // a non-file tool that must be carried over unchanged

        final CancellationSignal signal = new DefaultInterruptCoordinator().getSignal();
        final SubagentExecutionEnvironment baseEnv = baseEnv(baseRegistry, signal);

        final SubagentExecutionEnvironment derived = new WorktreeToolEnvironmentFactory(baseVfs).derive(baseEnv, "b0");
        final ToolRegistry scopedRegistry = derived.getToolRegistry();

        // Non-file tool carried over; the four file tools present.
        assertThat(scopedRegistry.findByName("Dummy")).isPresent();
        assertThat(scopedRegistry.findByName("Read")).isPresent();
        assertThat(scopedRegistry.findByName("Write")).isPresent();
        assertThat(scopedRegistry.findByName("Edit")).isPresent();

        // The scoped Write tool lands its writes in the branch subtree, not at the canonical root.
        final Tool scopedWrite = scopedRegistry.findByName("Write").orElseThrow();
        scopedWrite.execute(ToolInput.of(Map.of("file_path", "out.txt", "content", "hi")), ToolContext.empty());
        assertThat(baseVfs.exists(".worktrees/b0/out.txt")).isTrue();
        assertThat(baseVfs.exists("out.txt")).isFalse();

        // The per-run cancellation signal is preserved (required for stop(runId) to reach the isolated leaf).
        assertThat(derived.getCancellationSignal()).isSameAs(signal);
    }

    @Test
    @DisplayName("FILE_TOOL_NAMES covers exactly the provider's registrations and derive rebinds every one")
    void fileToolNamesStayInLockstepWithProviderRegistrations(@TempDir Path tempDir) {
        final LocalFileSystem baseVfs = newVfs(tempDir);

        // The canonical constant must name exactly the tools OrcaFileToolProvider registers. A fifth VFS-backed
        // tool added to registerTools without updating FILE_TOOL_NAMES (or vice versa) fails here before it can
        // silently defeat worktree isolation by carrying the new tool over still bound to the base filesystem.
        final DefaultToolRegistry providerRegistry = new DefaultToolRegistry();
        new OrcaFileToolProvider().registerTools(providerRegistry, OrcaToolProviderContext.builder().fileSystem(baseVfs)
                .dependencies(OrcaProviderDependencies.builder().build()).build());
        assertThat(providerRegistry.getToolNames())
                .containsExactlyInAnyOrderElementsOf(OrcaFileToolProvider.FILE_TOOL_NAMES);

        // And the factory must have a rebinding branch for every excluded name: a name in the set that derive()
        // fails to re-register would otherwise surface only at agent runtime as a missing tool.
        final SubagentExecutionEnvironment derived = new WorktreeToolEnvironmentFactory(baseVfs)
                .derive(baseEnv(providerRegistry, new DefaultInterruptCoordinator().getSignal()), "b1");
        for (final String name : OrcaFileToolProvider.FILE_TOOL_NAMES) {
            assertThat(derived.getToolRegistry().findByName(name)).as("rebinding branch for '%s'", name).isPresent();
        }
    }

    @Test
    @DisplayName("derive keeps artifact-aware Write/Edit variants, rebound to the scoped VFS")
    void deriveRebindsArtifactAwareVariantsToScopedVfs(@TempDir Path tempDir) throws Exception {
        final LocalFileSystem baseVfs = newVfs(tempDir);

        final DefaultToolRegistry baseRegistry = new DefaultToolRegistry();
        baseRegistry.register(new ReadTool(baseVfs));
        baseRegistry.register(new ArtifactAwareWriteTool(baseVfs));
        baseRegistry.register(new ArtifactAwareEditTool(baseVfs));
        baseRegistry.register(new GrepTool(baseVfs));

        final ToolRegistry scopedRegistry = new WorktreeToolEnvironmentFactory(baseVfs)
                .derive(baseEnv(baseRegistry, new DefaultInterruptCoordinator().getSignal()), "b2").getToolRegistry();

        // Regression guard: an artifact-enabled deployment must keep the artifact-aware variants inside isolated
        // branches — rebinding to the plain tools would silently drop artifact registration.
        final Tool scopedWrite = scopedRegistry.findByName(WriteTool.TOOL_NAME).orElseThrow();
        final Tool scopedEdit = scopedRegistry.findByName(EditTool.TOOL_NAME).orElseThrow();
        assertThat(scopedWrite).isInstanceOf(ArtifactAwareWriteTool.class);
        assertThat(scopedEdit).isInstanceOf(ArtifactAwareEditTool.class);

        final ArtifactCollector collector = new ArtifactCollector();
        final ToolContext toolContext = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector)
                .put(ReadTool.READ_FILES_KEY, Set.of("report.md")).build();

        // The rebound Write lands in the branch subtree and still registers its artifact.
        assertThat(scopedWrite.execute(ToolInput.of(Map.of("file_path", "report.md", "content", "v1")), toolContext)
                .isSuccess()).isTrue();
        assertThat(baseVfs.exists(".worktrees/b2/report.md")).isTrue();
        assertThat(baseVfs.exists("report.md")).isFalse();

        // The rebound Edit reads and writes through the scoped VFS (the file exists only in the branch subtree).
        assertThat(scopedEdit
                .execute(ToolInput.of(Map.of("file_path", "report.md", "old_string", "v1", "new_string", "v2")),
                        toolContext)
                .isSuccess()).isTrue();
        try (InputStream in = baseVfs.read(".worktrees/b2/report.md")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("v2");
        }
        assertThat(collector.getArtifacts()).hasSize(2);
    }

    @Test
    @DisplayName("derive keeps the plain Write/Edit tools when the base deployment is not artifact-enabled")
    void deriveKeepsPlainWriteAndEditVariants(@TempDir Path tempDir) {
        final LocalFileSystem baseVfs = newVfs(tempDir);

        final DefaultToolRegistry baseRegistry = new DefaultToolRegistry();
        baseRegistry.register(new ReadTool(baseVfs));
        baseRegistry.register(new WriteTool(baseVfs));
        baseRegistry.register(new EditTool(baseVfs));
        baseRegistry.register(new GrepTool(baseVfs));

        final ToolRegistry scopedRegistry = new WorktreeToolEnvironmentFactory(baseVfs)
                .derive(baseEnv(baseRegistry, new DefaultInterruptCoordinator().getSignal()), "b3").getToolRegistry();

        // isNotInstanceOf keeps this decisive even if the artifact-aware variants ever subclass the plain tools.
        assertThat(scopedRegistry.findByName(WriteTool.TOOL_NAME).orElseThrow()).isInstanceOf(WriteTool.class)
                .isNotInstanceOf(ArtifactAwareWriteTool.class);
        assertThat(scopedRegistry.findByName(EditTool.TOOL_NAME).orElseThrow()).isInstanceOf(EditTool.class)
                .isNotInstanceOf(ArtifactAwareEditTool.class);
    }

    private static LocalFileSystem newVfs(Path tempDir) {
        final LocalFileSystem vfs = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        vfs.initialize();
        return vfs;
    }

    private static SubagentExecutionEnvironment baseEnv(ToolRegistry toolRegistry, CancellationSignal signal) {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(toolRegistry)
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).cancellationSignal(signal).build();
    }

    private static Tool namedTool(String name) {
        final Tool tool = mock(Tool.class);
        final ToolDefinition def = mock(ToolDefinition.class);
        when(def.getName()).thenReturn(name);
        when(tool.getDefinition()).thenReturn(def);
        return tool;
    }
}
