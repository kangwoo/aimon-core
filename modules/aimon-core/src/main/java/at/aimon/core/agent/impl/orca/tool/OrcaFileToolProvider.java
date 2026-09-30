package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.tools.artifact.ArtifactArchive;
import at.aimon.core.tools.artifact.ArtifactAwareEditTool;
import at.aimon.core.tools.artifact.ArtifactAwareWriteTool;
import at.aimon.core.tools.artifact.ArtifactPolicy;
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
 * <p>
 * None of the tools is given a filesystem: each reads the execution's environment from its {@code ToolContext} on
 * every call (execution-environment design §6). The only branch left here is the artifact one. The artifact-aware
 * variants archive into the context's control store when an environment is not durable (§9.3).
 *
 * @see OrcaToolProvider
 */
public class OrcaFileToolProvider implements OrcaToolProvider {

    private final ArtifactPolicy artifactPolicy;

    /**
     * Creates an OrcaFileToolProvider with artifact support disabled.
     */
    public OrcaFileToolProvider() {
        this(ArtifactPolicy.disabled());
    }

    /**
     * Creates an OrcaFileToolProvider.
     *
     * @param artifactPolicy
     *            {@linkplain ArtifactPolicy#isEnabled() 활성}이면 파일 쓰기/편집 시 아티팩트를 자동 등록하는
     *            {@link ArtifactAwareWriteTool}, {@link ArtifactAwareEditTool}을 사용하고, 비영속 환경의 파일을 제어
     *            저장소로 옮길 때 이 정책의 상한을 따른다 (must not be null)
     */
    public OrcaFileToolProvider(ArtifactPolicy artifactPolicy) {
        this.artifactPolicy = Objects.requireNonNull(artifactPolicy, "artifactPolicy must not be null");
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        final Tool writeTool;
        final Tool editTool;
        if (artifactPolicy.isEnabled()) {
            final ArtifactArchive archive = new ArtifactArchive(context.getControlFileSystem(), artifactPolicy);
            writeTool = new ArtifactAwareWriteTool(archive);
            editTool = new ArtifactAwareEditTool(archive);
        } else {
            writeTool = new WriteTool();
            editTool = new EditTool();
        }

        registry.register(new ReadTool());
        registry.register(writeTool);
        registry.register(editTool);
        registry.register(new GrepTool());
    }
}
