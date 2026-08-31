package at.aimon.bootstrap.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory;
import at.aimon.core.agent.impl.orca.tool.OrcaBashToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.mcp.McpClientFactory;
import at.aimon.core.mcp.McpServerConfigProvider;

/**
 * Declares which tools the stack's agent runtimes register.
 *
 * <p>
 * The starting point is always the core default provider set — Todo, File, Bash, Subagent, Skill, Scheduling.
 * This spec then <b>subtracts</b> providers the deployment must not have and <b>adds</b> ones it supplies
 * itself.
 *
 * <h2>Why Bash is a subtraction, not a flag</h2>
 *
 * <p>
 * There is no "bash off" switch in the core default set: {@code defaultToolProviders(...)} always includes the
 * Bash provider and returns an immutable list. Turning it off therefore means filtering the provider out of the
 * list before it reaches the runtime factory, which is what {@link #serverDefaults()} does. Anything that
 * merely sets a flag on the tool would still register a tool the model can see and call.
 *
 * <h2>MCP</h2>
 *
 * <p>
 * MCP servers belong here rather than in a spec of their own because what they contribute <i>is</i> tools —
 * the runtime factory turns them into one more provider appended to this list. They are configured as a pair
 * ({@link Builder#mcp(McpClientFactory, McpServerConfigProvider)}) because neither half is usable alone: a
 * factory with no server list connects to nothing, and a server list with no factory cannot be dialled.
 *
 * <h2>Two workflow switches, not one</h2>
 *
 * <p>
 * {@link Builder#workflowToolEnabled(boolean)} decides whether the built-in {@code Workflow} <i>tool</i> is
 * registered; {@link Builder#workflowRunnerEnabled(boolean)} decides whether the runtime owns a
 * {@code WorkflowRunner} for tools to execute against. They are usually the same answer, so the second defaults
 * to the first — but not always: a deployment that supplies its own workflow tool through
 * {@link Builder#addProvider(OrcaToolProvider)} (the CLI's GraalJS variant is exactly this) needs the runner
 * without the built-in tool. Conflating them there produces a tool whose every invocation fails at the point of
 * use, having looked correctly configured at startup.
 */
public final class ToolSpec {

    private final boolean bashEnabled;
    private final boolean workflowToolEnabled;
    private final boolean workflowRunnerEnabled;
    private final List<OrcaToolProvider> additionalProviders;
    private final List<ToolContextEnricher> contextEnrichers;
    private final McpClientFactory mcpClientFactory;
    private final McpServerConfigProvider mcpServerConfigProvider;

    private ToolSpec(Builder builder) {
        this.bashEnabled = builder.bashEnabled;
        this.workflowToolEnabled = builder.workflowToolEnabled;
        this.workflowRunnerEnabled = (builder.workflowRunnerEnabled == null)
                ? builder.workflowToolEnabled
                : builder.workflowRunnerEnabled;
        this.additionalProviders = List.copyOf(builder.additionalProviders);
        this.contextEnrichers = List.copyOf(builder.contextEnrichers);
        this.mcpClientFactory = builder.mcpClientFactory;
        this.mcpServerConfigProvider = builder.mcpServerConfigProvider;

        if ((this.mcpClientFactory == null) != (this.mcpServerConfigProvider == null)) {
            throw new IllegalArgumentException(
                    "MCP needs both a client factory and a server config provider, or neither");
        }
    }

    /**
     * The core default set, unmodified: Bash on, the opt-in Workflow tool off.
     *
     * <p>
     * Appropriate when the stack runs code the operator already trusts — a developer workstation, a sandboxed
     * container that <i>is</i> the blast radius.
     *
     * @return the spec
     */
    public static ToolSpec defaults() {
        return builder().build();
    }

    /**
     * The default set with the Bash provider removed.
     *
     * <p>
     * The right starting point for a stack embedded in a service process, where the agent shares a JVM and a
     * file system with the application: arbitrary shell execution there is not a sandbox escape, it is simply
     * not a sandbox.
     *
     * @return the spec
     */
    public static ToolSpec serverDefaults() {
        return builder().bashEnabled(false).build();
    }

    /**
     * Creates a builder starting from the core defaults.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Resolves this spec into the provider list to hand the runtime factory.
     *
     * @return an immutable, ordered provider list
     */
    public List<OrcaToolProvider> resolveProviders() {
        final List<OrcaToolProvider> providers = new ArrayList<>(
                OrcaAgentRuntimeFactory.defaultToolProviders(workflowToolEnabled));
        if (!bashEnabled) {
            providers.removeIf(OrcaBashToolProvider.class::isInstance);
        }
        providers.addAll(additionalProviders);
        return List.copyOf(providers);
    }

    /**
     * Returns whether the Bash provider stays in the default set.
     *
     * @return {@code true} when Bash is registered
     */
    public boolean isBashEnabled() {
        return bashEnabled;
    }

    /**
     * Returns whether the opt-in {@code Workflow} tool is registered alongside the subagent tools.
     *
     * @return {@code true} when the Workflow tool is registered
     */
    public boolean isWorkflowToolEnabled() {
        return workflowToolEnabled;
    }

    /**
     * Returns whether the runtime owns a {@code WorkflowRunner} that workflow tools execute against.
     *
     * @return {@code true} when the agent-scoped runner is created
     */
    public boolean isWorkflowRunnerEnabled() {
        return workflowRunnerEnabled;
    }

    /**
     * Returns the caller-supplied providers appended after the defaults.
     *
     * @return an immutable provider list
     */
    public List<OrcaToolProvider> getAdditionalProviders() {
        return additionalProviders;
    }

    /**
     * Returns the enrichers that contribute entries to every tool's {@code ToolContext}.
     *
     * @return an immutable list, possibly empty
     */
    public List<ToolContextEnricher> getContextEnrichers() {
        return contextEnrichers;
    }

    /**
     * Returns the MCP client factory.
     *
     * @return the factory, or empty when MCP is not configured
     */
    public Optional<McpClientFactory> getMcpClientFactory() {
        return Optional.ofNullable(mcpClientFactory);
    }

    /**
     * Returns the MCP server configuration provider.
     *
     * @return the provider, or empty when MCP is not configured
     */
    public Optional<McpServerConfigProvider> getMcpServerConfigProvider() {
        return Optional.ofNullable(mcpServerConfigProvider);
    }

    /**
     * Returns whether both halves of the MCP pair are present.
     *
     * @return {@code true} when the runtime should be created with MCP support
     */
    public boolean isMcpEnabled() {
        return mcpClientFactory != null;
    }

    @Override
    public String toString() {
        return "ToolSpec[bash=" + bashEnabled + ", workflowTool=" + workflowToolEnabled + ", workflowRunner="
                + workflowRunnerEnabled + ", additional=" + additionalProviders.size() + ", enrichers="
                + contextEnrichers.size() + ", mcp=" + isMcpEnabled() + "]";
    }

    /** Builder for {@link ToolSpec}. */
    public static final class Builder {

        private boolean bashEnabled = true;
        private boolean workflowToolEnabled;
        private Boolean workflowRunnerEnabled;
        private final List<OrcaToolProvider> additionalProviders = new ArrayList<>();
        private final List<ToolContextEnricher> contextEnrichers = new ArrayList<>();
        private McpClientFactory mcpClientFactory;
        private McpServerConfigProvider mcpServerConfigProvider;

        private Builder() {
        }

        /**
         * Keeps or removes the Bash provider from the default set.
         *
         * @param bashEnabled
         *            whether to register Bash
         * @return this builder
         */
        public Builder bashEnabled(boolean bashEnabled) {
            this.bashEnabled = bashEnabled;
            return this;
        }

        /**
         * Enables the opt-in {@code Workflow} tool on the subagent provider.
         *
         * @param workflowToolEnabled
         *            whether to register the Workflow tool
         * @return this builder
         */
        public Builder workflowToolEnabled(boolean workflowToolEnabled) {
            this.workflowToolEnabled = workflowToolEnabled;
            return this;
        }

        /**
         * Creates the agent-scoped {@code WorkflowRunner} independently of the built-in Workflow tool.
         *
         * <p>
         * Unset, this follows {@link #workflowToolEnabled(boolean)}. Set it explicitly only when a
         * caller-supplied provider contributes the workflow tool — see the class javadoc.
         *
         * @param workflowRunnerEnabled
         *            whether the runtime owns a workflow runner
         * @return this builder
         */
        public Builder workflowRunnerEnabled(boolean workflowRunnerEnabled) {
            this.workflowRunnerEnabled = workflowRunnerEnabled;
            return this;
        }

        /**
         * Appends a caller-supplied provider after the defaults.
         *
         * @param provider
         *            the provider (must not be null)
         * @return this builder
         */
        public Builder addProvider(OrcaToolProvider provider) {
            this.additionalProviders.add(Objects.requireNonNull(provider, "provider must not be null"));
            return this;
        }

        /**
         * Appends caller-supplied providers after the defaults.
         *
         * @param providers
         *            the providers (must not be null)
         * @return this builder
         */
        public Builder addProviders(List<? extends OrcaToolProvider> providers) {
            Objects.requireNonNull(providers, "providers must not be null").forEach(this::addProvider);
            return this;
        }

        /**
         * Appends an enricher that adds entries to the {@code ToolContext} every tool receives.
         *
         * @param enricher
         *            the enricher (must not be null)
         * @return this builder
         */
        public Builder addContextEnricher(ToolContextEnricher enricher) {
            this.contextEnrichers.add(Objects.requireNonNull(enricher, "enricher must not be null"));
            return this;
        }

        /**
         * Appends enrichers that add entries to the {@code ToolContext} every tool receives.
         *
         * @param enrichers
         *            the enrichers (must not be null)
         * @return this builder
         */
        public Builder addContextEnrichers(List<? extends ToolContextEnricher> enrichers) {
            Objects.requireNonNull(enrichers, "enrichers must not be null").forEach(this::addContextEnricher);
            return this;
        }

        /**
         * Configures MCP: the runtime is created with an MCP tool provider over these servers.
         *
         * <p>
         * Both arguments are required together — see the class javadoc. Passing {@code null} for both clears any
         * previously configured pair.
         *
         * @param clientFactory
         *            the MCP client factory
         * @param serverConfigProvider
         *            the MCP server configuration provider
         * @return this builder
         */
        public Builder mcp(McpClientFactory clientFactory, McpServerConfigProvider serverConfigProvider) {
            this.mcpClientFactory = clientFactory;
            this.mcpServerConfigProvider = serverConfigProvider;
            return this;
        }

        /**
         * Builds the spec.
         *
         * @return the immutable spec
         */
        public ToolSpec build() {
            return new ToolSpec(this);
        }
    }
}
