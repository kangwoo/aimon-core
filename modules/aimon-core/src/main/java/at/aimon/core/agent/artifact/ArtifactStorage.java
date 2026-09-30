package at.aimon.core.agent.artifact;

/**
 * Which store an artifact's {@link FileArtifact#getPath() path} points into (execution-environment design §9.3). A
 * download endpoint needs to know which one to open.
 */
public enum ArtifactStorage {

    /**
     * The execution environment's filesystem — the file the tool wrote, where it wrote it. Used when the environment
     * is durable (its files outlive the execution).
     */
    WORKSPACE,

    /**
     * The control store — a copy the artifact-aware tool archived under {@code /artifacts/{archiveKey}/} because the
     * environment was not durable (a sandbox workspace, an isolated workflow branch) and the original may disappear.
     */
    CONTROL
}
