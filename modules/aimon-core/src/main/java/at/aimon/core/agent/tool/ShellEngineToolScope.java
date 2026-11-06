package at.aimon.core.agent.tool;

import java.util.Locale;
import java.util.Objects;

/**
 * How far the tools the shell engine opens to code may reach — {@code shell-engine-tool-scope}
 * ({@code docs/design/tool/programmatic-tool-calling-shell.md} §12.2, decision 5).
 *
 * <p>
 * Without a dedicated uid, a process co-resident in the sandbox can call the shell engine's callable tools while a
 * script runs and read their results (shell doc §12.1, R1·R2). The scope bounds that set by {@link DataReach}: it is
 * the last step of the callable set (§7.3 step 5) and applies to an engine that is
 * {@linkplain at.aimon.core.programmatic.CodeExecutionEngine#isToolScopeBound() bound by it} only.
 *
 * <p>
 * The application value is the ceiling and an agent can only narrow it: an agent value wider than the application's is
 * ignored with a warning.
 */
public enum ShellEngineToolScope {

    /** Only tools that reach nothing beyond the environment ({@link DataReach#ENVIRONMENT}). The default. */
    ENVIRONMENT(DataReach.ENVIRONMENT),

    /** Environment tools and closed host-side systems ({@link DataReach#HOST}). Pair it with an egress deny-default. */
    HOST(DataReach.HOST),

    /** Every tool in {@code programmatic-tools}, external ones included. Warned at startup. */
    UNRESTRICTED(DataReach.EXTERNAL);

    private final DataReach maxReach;

    ShellEngineToolScope(DataReach maxReach) {
        this.maxReach = maxReach;
    }

    /**
     * Returns the widest reach a callable tool may declare under this scope.
     *
     * @return the reach (never null)
     */
    public DataReach maxReach() {
        return maxReach;
    }

    /**
     * Returns whether a tool of {@code reach} stays in the callable set under this scope.
     *
     * @param reach
     *            the tool's reach (must not be null)
     * @return {@code true} when {@code reach} is not wider than {@link #maxReach()}
     */
    public boolean admits(DataReach reach) {
        Objects.requireNonNull(reach, "reach cannot be null");
        return maxReach.max(reach) == maxReach;
    }

    /**
     * Returns the narrower of this scope and {@code other}.
     *
     * @param other
     *            the other scope (must not be null)
     * @return whichever admits less (never null)
     */
    public ShellEngineToolScope min(ShellEngineToolScope other) {
        Objects.requireNonNull(other, "other cannot be null");
        return other.ordinal() < ordinal() ? other : this;
    }

    /**
     * Returns the spelling used in configuration: the constant's name in lower case.
     *
     * @return {@code "environment"}, {@code "host"} or {@code "unrestricted"} (never null)
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a configuration value, case ignored.
     *
     * @param value
     *            the value (must not be null)
     * @return the scope (never null)
     * @throws IllegalArgumentException
     *             if the value names no scope
     */
    public static ShellEngineToolScope parse(String value) {
        Objects.requireNonNull(value, "value cannot be null");
        final String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ShellEngineToolScope scope : values()) {
            if (scope.name().equals(normalized)) {
                return scope;
            }
        }
        throw new IllegalArgumentException(
                "unknown shell engine tool scope '" + value + "' (expected environment, host or unrestricted)");
    }
}
