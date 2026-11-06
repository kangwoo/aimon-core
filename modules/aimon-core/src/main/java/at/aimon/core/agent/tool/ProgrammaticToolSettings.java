package at.aimon.core.agent.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What an agent opens to model-written code: the tools {@code ExecuteCode} may call, the ones it offers only through
 * code, and the engine it runs that code in ({@code docs/design/tool/programmatic-tool-calling.md} §7.2).
 *
 * <p>
 * The frontmatter keys are {@code programmatic-tools}, {@code programmatic-only}, {@code programmatic-engine} and
 * {@code shell-engine-tool-scope} (which can only narrow the application's value, shell doc §12.2).
 * Without {@code programmatic-tools} there is no programmatic tool calling at all ({@link #none()}):
 * {@code ExecuteCode}
 * is not offered.
 *
 * <p>
 * Names are exact — no wildcard and no argument pattern — because a wildcard would let an MCP server that adds a tool
 * widen what code may call without anyone writing its name. What this class can check without the tools themselves is
 * checked here: the name form and {@code programmatic-only ⊆ programmatic-tools}. What needs a tool's axes (a mutating
 * tool may not be code-only, decision 12 ④) is checked when the tools are known, at offer time.
 *
 * <p>
 * Immutable. Use {@link #builder()}.
 */
public final class ProgrammaticToolSettings {

    private static final ProgrammaticToolSettings NONE = new ProgrammaticToolSettings(new Builder());

    private final List<String> tools;
    private final Set<String> codeOnly;
    private final ProgrammaticEngineChoice engine;
    private final ShellEngineToolScope shellEngineToolScope;

    private ProgrammaticToolSettings(Builder builder) {
        this.tools = List.copyOf(new LinkedHashSet<>(builder.tools));
        this.codeOnly = Set.copyOf(builder.codeOnly);
        this.engine = Objects.requireNonNull(builder.engine, "engine cannot be null");
        this.shellEngineToolScope = builder.shellEngineToolScope;
        for (String name : tools) {
            requireExactName(name, "programmatic-tools");
        }
        for (String name : codeOnly) {
            requireExactName(name, "programmatic-only");
            if (!tools.contains(name)) {
                throw new IllegalArgumentException("programmatic-only names '" + name
                        + "', which is not in programmatic-tools; programmatic-only must be a subset of it");
            }
        }
    }

    /**
     * Returns the settings of an agent that opens nothing to code.
     *
     * @return the shared empty settings (never null)
     */
    public static ProgrammaticToolSettings none() {
        return NONE;
    }

    /**
     * Returns a new builder.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether programmatic tool calling is on — whether {@code programmatic-tools} names any tool.
     *
     * @return {@code true} when at least one tool is opened to code
     */
    public boolean isEnabled() {
        return !tools.isEmpty();
    }

    /**
     * Returns the tools code may call, in declaration order, without duplicates.
     *
     * @return the names (never null, possibly empty)
     */
    public List<String> getTools() {
        return tools;
    }

    /**
     * Returns the tools offered only through code — withheld from the direct offer.
     *
     * @return the names (never null, possibly empty); a subset of {@link #getTools()}
     */
    public Set<String> getCodeOnly() {
        return codeOnly;
    }

    /**
     * Returns the engine choice.
     *
     * @return the choice (never null); {@link ProgrammaticEngineChoice#AUTO} unless set
     */
    public ProgrammaticEngineChoice getEngine() {
        return engine;
    }

    /**
     * Returns the agent's {@code shell-engine-tool-scope}: how far the shell engine's callable tools may reach for this
     * agent. It can only narrow the application's value (shell doc §12.2); a wider value is ignored with a warning.
     *
     * @return the agent's scope, empty when the agent leaves it to the application
     */
    public Optional<ShellEngineToolScope> getShellEngineToolScope() {
        return Optional.ofNullable(shellEngineToolScope);
    }

    private static void requireExactName(String name, String key) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(key + " contains a blank tool name");
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == '*' || c == '(' || c == ')' || Character.isWhitespace(c)) {
                throw new IllegalArgumentException(key + " entry '" + name
                        + "' is not an exact tool name: wildcards and argument patterns are not allowed");
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProgrammaticToolSettings other)) {
            return false;
        }
        return tools.equals(other.tools) && codeOnly.equals(other.codeOnly) && engine == other.engine
                && shellEngineToolScope == other.shellEngineToolScope;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tools, codeOnly, engine, shellEngineToolScope);
    }

    @Override
    public String toString() {
        return isEnabled()
                ? "ProgrammaticToolSettings{tools=" + tools + ", codeOnly=" + codeOnly + ", engine=" + engine.wireName()
                        + (shellEngineToolScope == null
                                ? ""
                                : ", shellEngineToolScope=" + shellEngineToolScope.wireName())
                        + '}'
                : "ProgrammaticToolSettings.none()";
    }

    /** Builder for {@link ProgrammaticToolSettings}. */
    public static final class Builder {

        private final List<String> tools = new ArrayList<>();
        private final Set<String> codeOnly = new LinkedHashSet<>();
        private ProgrammaticEngineChoice engine = ProgrammaticEngineChoice.AUTO;
        private ShellEngineToolScope shellEngineToolScope;

        private Builder() {
        }

        /**
         * Sets the agent's {@code shell-engine-tool-scope}, which can only narrow the application's.
         *
         * @param shellEngineToolScope
         *            the scope, or {@code null} to leave it to the application
         * @return this builder
         */
        public Builder shellEngineToolScope(ShellEngineToolScope shellEngineToolScope) {
            this.shellEngineToolScope = shellEngineToolScope;
            return this;
        }

        /**
         * Sets the tools code may call ({@code programmatic-tools}), replacing any set before.
         *
         * @param tools
         *            exact tool names (must not be null)
         * @return this builder
         */
        public Builder tools(Collection<String> tools) {
            Objects.requireNonNull(tools, "tools cannot be null");
            this.tools.clear();
            this.tools.addAll(tools);
            return this;
        }

        /**
         * Sets the tools offered only through code ({@code programmatic-only}), replacing any set before.
         *
         * @param codeOnly
         *            exact tool names, each also in {@link #tools} (must not be null)
         * @return this builder
         */
        public Builder codeOnly(Collection<String> codeOnly) {
            Objects.requireNonNull(codeOnly, "codeOnly cannot be null");
            this.codeOnly.clear();
            this.codeOnly.addAll(codeOnly);
            return this;
        }

        /**
         * Sets the engine choice ({@code programmatic-engine}).
         *
         * @param engine
         *            the choice (must not be null)
         * @return this builder
         */
        public Builder engine(ProgrammaticEngineChoice engine) {
            this.engine = Objects.requireNonNull(engine, "engine cannot be null");
            return this;
        }

        /**
         * Builds the settings.
         *
         * @return the settings (never null)
         * @throws IllegalArgumentException
         *             if a name is not an exact tool name, or {@code programmatic-only} is not a subset of
         *             {@code programmatic-tools}
         */
        public ProgrammaticToolSettings build() {
            return new ProgrammaticToolSettings(this);
        }
    }
}
