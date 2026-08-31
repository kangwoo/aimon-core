package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;
import java.util.Set;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.tools.artifact.ArtifactAwareEditTool;
import at.aimon.core.tools.artifact.ArtifactAwareWriteTool;
import at.aimon.core.tools.file.EditTool;
import at.aimon.core.tools.file.GrepTool;
import at.aimon.core.tools.file.ReadTool;
import at.aimon.core.tools.file.WriteTool;

/**
 * Provides file operation tools to the Orca agent system.
 *
 * <p>
 * This provider registers file manipulation tools including:
 *
 * <ul>
 * <li>{@link ReadTool} - Read file contents
 * <li>{@link WriteTool} - Write files (or {@link ArtifactAwareWriteTool} when artifact support is enabled)
 * <li>{@link EditTool} - Edit existing files (or {@link ArtifactAwareEditTool} when artifact support is enabled)
 * <li>{@link GrepTool} - Search file contents
 * </ul>
 *
 * @see OrcaToolProvider
 */
public class OrcaFileToolProvider implements OrcaToolProvider {

    /**
     * Canonical, immutable set of the VFS-backed file-tool names registered by {@link #registerTools}
     * ({@code Read}/{@code Write}/{@code Edit}/{@code Grep}).
     *
     * <p>
     * Lockstep contract (design §6.3):
     * {@link at.aimon.core.agent.impl.orca.environment.WorktreeToolEnvironmentFactory} consumes this set to decide
     * which tools to exclude from the base-registry carry-over and rebind to a branch-scoped filesystem when deriving
     * an isolated worktree environment. Any new VFS-backed tool added to {@link #registerTools} MUST be added here
     * (and given a rebinding branch in that factory) in the same change — otherwise the new tool would be carried
     * over still bound to the base filesystem, silently defeating isolation.
     */
    public static final Set<String> FILE_TOOL_NAMES = Set.of(ReadTool.TOOL_NAME, WriteTool.TOOL_NAME,
            EditTool.TOOL_NAME, GrepTool.TOOL_NAME);

    private final boolean artifactEnabled;

    /**
     * Creates an OrcaFileToolProvider with artifact support disabled.
     */
    public OrcaFileToolProvider() {
        this(false);
    }

    /**
     * Creates an OrcaFileToolProvider.
     *
     * @param artifactEnabled
     *            {@code true}이면 파일 쓰기/편집 시 아티팩트를 자동 등록하는 {@link ArtifactAwareWriteTool},
     *            {@link ArtifactAwareEditTool}을 사용한다
     */
    public OrcaFileToolProvider(boolean artifactEnabled) {
        this.artifactEnabled = artifactEnabled;
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        final VirtualFileSystem fileSystem = context.getFileSystem();
        Objects.requireNonNull(fileSystem, "fileSystem must not be null in context");

        final Tool writeTool = artifactEnabled ? new ArtifactAwareWriteTool(fileSystem) : new WriteTool(fileSystem);
        final Tool editTool = artifactEnabled ? new ArtifactAwareEditTool(fileSystem) : new EditTool(fileSystem);

        registry.register(new ReadTool(fileSystem));
        registry.register(writeTool);
        registry.register(editTool);
        registry.register(new GrepTool(fileSystem));
    }
}
