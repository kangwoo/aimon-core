package at.aimon.core.environment;

/**
 * Where an execution environment runs the model's commands relative to the host
 * ({@code docs/design/tool/programmatic-tool-calling.md} §6.2): on the host itself, or inside a sandbox.
 */
public enum EnvironmentIsolation {

    /** Commands run on the host (the local environment, a local worktree branch). */
    HOST,

    /** Commands run inside a sandbox the host provisions. */
    SANDBOXED
}
