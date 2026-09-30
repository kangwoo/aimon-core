package at.aimon.bootstrap.assemble;

/**
 * The conventional locations a stack reads agent material from.
 *
 * <p>
 * The directories below are relative to the <b>control</b> store's root, which a local stack puts at
 * {@code {workspace}/.aimon/}; the physical paths are the {@code .aimon/...} ones they have always been.
 *
 * <p>
 * Held in one place because two of them are now read from two: the stack builder stands up the agents named in
 * configuration, and {@link StackAgentRuntimeProvisioner} stands up tenant runtimes on first use. A path that
 * drifted between those would not fail — it would give tenants an agent with no skills.
 */
public final class StackPaths {

    /** Classpath root under which agent bundles are discovered. Matches the core loader default. */
    public static final String AGENT_BUNDLE_BASE_PATH = "agents";

    /**
     * The control store's directory under a runtime's workspace: agent, skill and command definitions, task outputs,
     * snapshots. Hidden from the model's file tools (execution-environment design §9.2).
     */
    public static final String CONTROL_DIRECTORY = ".aimon";

    /** Where the control store holds user-authored skills (physically {@code {workspace}/.aimon/skills}). */
    public static final String USER_SKILLS_DIRECTORY = "skills";

    /** Where bundled skills are materialised into the control store (physically {@code .aimon/bundled-skills}). */
    public static final String BUNDLED_SKILLS_DIRECTORY = "bundled-skills";

    /** Where the control store holds slash commands (physically {@code .aimon/commands}). */
    public static final String COMMANDS_DIRECTORY = "commands";

    /** Where the control store holds subagent definitions (physically {@code .aimon/agents}). */
    public static final String AGENTS_DIRECTORY = "agents";

    private StackPaths() {
    }
}
