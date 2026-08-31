package at.aimon.bootstrap.assemble;

/**
 * The conventional locations a stack reads agent material from.
 *
 * <p>
 * Held in one place because two of them are now read from two: the stack builder stands up the agents named in
 * configuration, and {@link StackAgentRuntimeProvisioner} stands up tenant runtimes on first use. A path that
 * drifted between those would not fail — it would give tenants an agent with no skills.
 */
public final class StackPaths {

    /** Classpath root under which agent bundles are discovered. Matches the core loader default. */
    public static final String AGENT_BUNDLE_BASE_PATH = "agents";

    /** Where an agent's own file system holds user-authored skills. */
    public static final String USER_SKILLS_DIRECTORY = ".aimon/skills";

    /** Where bundled skills are materialised into an agent's file system. */
    public static final String BUNDLED_SKILLS_DIRECTORY = ".aimon/bundled-skills";

    /** Where an agent's file system holds slash commands. */
    public static final String COMMANDS_DIRECTORY = ".aimon/commands";

    /** Where an agent's file system holds subagent definitions. */
    public static final String AGENTS_DIRECTORY = ".aimon/agents";

    private StackPaths() {
    }
}
