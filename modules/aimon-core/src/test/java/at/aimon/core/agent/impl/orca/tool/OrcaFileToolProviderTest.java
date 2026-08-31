package at.aimon.core.agent.impl.orca.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.orca.OrcaProviderDependencies;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.tools.artifact.ArtifactAwareEditTool;
import at.aimon.core.tools.artifact.ArtifactAwareWriteTool;
import at.aimon.core.tools.file.EditTool;
import at.aimon.core.tools.file.WriteTool;

class OrcaFileToolProviderTest {

    private final VirtualFileSystem fileSystem = mock(VirtualFileSystem.class);

    private OrcaToolProviderContext createContext() {
        return OrcaToolProviderContext.builder().fileSystem(fileSystem)
                .dependencies(OrcaProviderDependencies.builder().build()).build();
    }

    @Test
    void shouldRegisterAllFileTools() {
        OrcaFileToolProvider provider = new OrcaFileToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();

        provider.registerTools(registry, createContext());

        assertThat(registry.findByName("Read")).isPresent();
        assertThat(registry.findByName("Write")).isPresent();
        assertThat(registry.findByName("Edit")).isPresent();
        assertThat(registry.findByName("Grep")).isPresent();
    }

    @Test
    void shouldRegisterBaseWriteToolWhenArtifactDisabled() {
        OrcaFileToolProvider provider = new OrcaFileToolProvider(false);
        ToolRegistry registry = new DefaultToolRegistry();

        provider.registerTools(registry, createContext());

        Tool tool = registry.findByName("Write").orElseThrow();
        assertThat(tool).isInstanceOf(WriteTool.class);
    }

    @Test
    void shouldRegisterBaseEditToolWhenArtifactDisabled() {
        OrcaFileToolProvider provider = new OrcaFileToolProvider(false);
        ToolRegistry registry = new DefaultToolRegistry();

        provider.registerTools(registry, createContext());

        Tool tool = registry.findByName("Edit").orElseThrow();
        assertThat(tool).isInstanceOf(EditTool.class);
    }

    @Test
    void shouldRegisterArtifactAwareWriteToolWhenArtifactEnabled() {
        OrcaFileToolProvider provider = new OrcaFileToolProvider(true);
        ToolRegistry registry = new DefaultToolRegistry();

        provider.registerTools(registry, createContext());

        Tool tool = registry.findByName("Write").orElseThrow();
        assertThat(tool).isInstanceOf(ArtifactAwareWriteTool.class);
    }

    @Test
    void shouldRegisterArtifactAwareEditToolWhenArtifactEnabled() {
        OrcaFileToolProvider provider = new OrcaFileToolProvider(true);
        ToolRegistry registry = new DefaultToolRegistry();

        provider.registerTools(registry, createContext());

        Tool tool = registry.findByName("Edit").orElseThrow();
        assertThat(tool).isInstanceOf(ArtifactAwareEditTool.class);
    }

    @Test
    void shouldDefaultToArtifactDisabled() {
        OrcaFileToolProvider provider = new OrcaFileToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();

        provider.registerTools(registry, createContext());

        Tool writeTool = registry.findByName("Write").orElseThrow();
        assertThat(writeTool).isInstanceOf(WriteTool.class);
        Tool editTool = registry.findByName("Edit").orElseThrow();
        assertThat(editTool).isInstanceOf(EditTool.class);
    }

    @Test
    void shouldThrowWhenRegistryIsNull() {
        OrcaFileToolProvider provider = new OrcaFileToolProvider();

        assertThatThrownBy(() -> provider.registerTools(null, createContext()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenContextIsNull() {
        OrcaFileToolProvider provider = new OrcaFileToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();

        assertThatThrownBy(() -> provider.registerTools(registry, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenFileSystemIsNull() {
        OrcaFileToolProvider provider = new OrcaFileToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext context = OrcaToolProviderContext.builder()
                .dependencies(OrcaProviderDependencies.builder().build()).build();

        assertThatThrownBy(() -> provider.registerTools(registry, context)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fileSystem");
    }
}
