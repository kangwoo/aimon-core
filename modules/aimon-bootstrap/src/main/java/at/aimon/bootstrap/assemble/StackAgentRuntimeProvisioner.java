package at.aimon.bootstrap.assemble;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.bootstrap.exception.UnknownAgentRuntimeException;
import at.aimon.bootstrap.runtime.AgentRuntimeProvisioner;
import at.aimon.bootstrap.runtime.ProvisionedAgentRuntime;
import at.aimon.bootstrap.spec.AgentDescriptor;
import at.aimon.bootstrap.spec.AgentRuntimeCustomizer;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.AgentWorkspaceLayout;
import at.aimon.bootstrap.spec.AimonAgentCustomizer;
import at.aimon.bootstrap.spec.CredentialStoreFactory;
import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory;
import at.aimon.core.agent.impl.orca.command.OrcaCommandProvider;
import at.aimon.core.agent.impl.orca.tool.OrcaKnowledgeToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.parser.SkillParser;

/**
 * Builds one agent runtime — for the agents named in configuration at startup, and for tenants on first use.
 *
 * <h2>Why both callers go through here</h2>
 *
 * <p>
 * The two moments a runtime is created have nothing in common except what they produce. Startup iterates a known
 * list and records what it built on the stack's teardown plan; a tenant arrives once, unannounced, and what it
 * built has to be closed again when it goes idle. It is tempting to write those as two pieces of code, and the
 * result of doing so is not a crash. It is a deployment where the tools, hooks and skills a runtime gets depend
 * on <i>when</i> it was created — a provider added to configuration reaches the five agents in the list and none
 * of the tenants, and the only symptom is a customer for whom one feature silently does nothing.
 *
 * <p>
 * So both paths call {@link #createRuntime(AgentRuntimeId, ResourceSink)}, and the only thing they choose is
 * where the resources it creates are recorded: the stack's teardown plan for the former, the
 * {@link ProvisionedAgentRuntime} for the latter. The tool list is assembled exactly once, here.
 *
 * <h2>Templates</h2>
 *
 * <p>
 * A tenant id carries an agent name and a discriminator, and only the name says what to build. The bundle,
 * classpath location and customizers for each configured agent are therefore kept as a template keyed by agent
 * name, so {@code agent:ops:acme} is built from the same description as {@code agent:ops} — with its own file
 * system, its own skill registry and its own runtime. A name with no template is refused: it is either a typo or
 * an agent this stack was not configured with, and building something for it would make both look like success.
 */
public final class StackAgentRuntimeProvisioner implements AgentRuntimeProvisioner {

    private static final Logger log = LoggerFactory.getLogger(StackAgentRuntimeProvisioner.class);

    private final Map<String, AgentTemplate> templates;
    private final FileSystemSpec fileSystemSpec;
    private final ToolSpec toolSpec;
    private final OrcaAgentRuntimeFactory runtimeFactory;
    private final OrcaAgentExecutor agentExecutor;
    private final ScheduledTaskManager taskManager;
    private final SkillParser skillParser;
    private final CredentialStore credentialStore;
    private final CredentialStoreFactory credentialStoreFactory;
    private final boolean knowledgeToolsEnabled;
    private final OrcaToolProvider memoryToolProvider;
    private final List<AimonAgentCustomizer> agentCustomizers;

    private StackAgentRuntimeProvisioner(Builder builder) {
        this.templates = Map.copyOf(builder.templates);
        this.fileSystemSpec = Objects.requireNonNull(builder.fileSystemSpec, "fileSystemSpec must not be null");
        this.toolSpec = Objects.requireNonNull(builder.toolSpec, "toolSpec must not be null");
        this.runtimeFactory = Objects.requireNonNull(builder.runtimeFactory, "runtimeFactory must not be null");
        this.agentExecutor = Objects.requireNonNull(builder.agentExecutor, "agentExecutor must not be null");
        this.taskManager = builder.taskManager;
        this.skillParser = Objects.requireNonNull(builder.skillParser, "skillParser must not be null");
        this.credentialStore = builder.credentialStore;
        this.credentialStoreFactory = builder.credentialStoreFactory;
        this.knowledgeToolsEnabled = builder.knowledgeToolsEnabled;
        this.memoryToolProvider = builder.memoryToolProvider;
        // Sorted once, here, rather than at every runtime creation: a hook's position decides what it can see,
        // and re-sorting per tenant would be work done on a request thread to reach the same answer.
        this.agentCustomizers = builder.agentCustomizers.stream()
                .sorted(Comparator.comparingInt(AimonAgentCustomizer::getOrder)).toList();
    }

    /**
     * Creates a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a runtime and everything created for it.
     *
     * <p>
     * Resources this creates — the file system, when it is not a caller-supplied shared one — are handed to
     * {@code sink} rather than closed over, so the caller decides whether they belong to the process or to the
     * runtime. Nothing shared reaches the sink: a supplied file system is used by every runtime, and closing it
     * with any one of them would leave the rest writing to a closed handle.
     *
     * @param agentRuntimeId
     *            the id to build (must not be null)
     * @param sink
     *            receives each resource created for this runtime, with a label for the teardown plan (must not be
     *            null)
     * @return the runtime and the file system it was built with
     * @throws UnknownAgentRuntimeException
     *             if no configured agent has this id's name
     */
    public Assembly createRuntime(AgentRuntimeId agentRuntimeId, ResourceSink sink) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId must not be null");
        Objects.requireNonNull(sink, "sink must not be null");

        final AgentTemplate template = templates.get(agentRuntimeId.agentName());
        if (template == null) {
            throw new UnknownAgentRuntimeException(
                    "No agent named '" + agentRuntimeId.agentName() + "' is configured on this stack, so "
                            + agentRuntimeId + " cannot be built. Known agents: " + templates.keySet() + ".");
        }

        final AgentDescriptor descriptor = template.describe(agentRuntimeId);
        final List<AimonAgentCustomizer> applicable = agentCustomizers.stream().filter(c -> c.supports(descriptor))
                .toList();

        final VirtualFileSystem fileSystem = createFileSystem(agentRuntimeId, sink);
        final SkillRegistry skillRegistry = OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(template.getBundle(),
                fileSystem, StackPaths.USER_SKILLS_DIRECTORY, StackPaths.BUNDLED_SKILLS_DIRECTORY,
                StackPaths.AGENT_BUNDLE_BASE_PATH + "/" + template.getBundleName() + "/skills",
                Thread.currentThread().getContextClassLoader(), skillParser);

        final OrcaAgentRuntime runtime = instantiate(agentRuntimeId, template.getBundle(), fileSystem, skillRegistry,
                descriptor, applicable);

        // Agent-level contributions first, then the ones attached to this one spec. Both land here — after the
        // registries exist, before the runtime is reachable. Registering a tool after publication races a
        // scheduled task resolving the runtime and starting a turn against a half-configured registry.
        for (AimonAgentCustomizer customizer : applicable) {
            customizer.registerHooks(descriptor, runtime.getHookRegistry());
        }
        // Front-end contributions (terminal tools, display hooks).
        for (AgentRuntimeCustomizer customizer : template.getCustomizers()) {
            customizer.customize(runtime);
        }
        return new Assembly(runtime, fileSystem);
    }

    private OrcaAgentRuntime instantiate(AgentRuntimeId agentRuntimeId, AgentBundle bundle,
            VirtualFileSystem fileSystem, SkillRegistry skillRegistry, AgentDescriptor descriptor,
            List<AimonAgentCustomizer> applicable) {
        // The base list is the one every runtime gets; a customizer appends to it and cannot take anything out of
        // it. That asymmetry is the point — "agent A also has the ticketing tools" is a contribution, while
        // "agent A does not have Bash" is a stack-wide decision (ToolSpec) that one agent must not make for the
        // others by side effect.
        final List<OrcaToolProvider> toolProviders = new ArrayList<>(toolSpec.resolveProviders());
        // Not part of the core default set, and not a ToolSpec switch either: the store is what decides. A stack
        // with a knowledge store and no KnowledgeSearch tool has an index the model cannot reach — the store is
        // still put in the tool context, so a subagent's Task tool finds it, and the capability looks present
        // everywhere except where an agent would use it. A stack without a store and with the tool would offer a
        // search that answers "no knowledge store is configured" to every query the model tries.
        if (knowledgeToolsEnabled) {
            toolProviders.add(new OrcaKnowledgeToolProvider());
        }
        // Passed in already built, unlike the knowledge one, because the memory tools take their stores at
        // construction rather than reading them out of the tool context. It arrives null when memory is off and
        // also when memory is on in per-caller mode — there the tools would have no observer to act as, which
        // MemoryAssembly records as a degradation rather than registering three tools that fail every call.
        if (memoryToolProvider != null) {
            toolProviders.add(memoryToolProvider);
        }
        final List<OrcaCommandProvider> commandProviders = new ArrayList<>(
                OrcaAgentRuntimeFactory.defaultCommandProviders());
        for (AimonAgentCustomizer customizer : applicable) {
            toolProviders.addAll(Objects.requireNonNull(customizer.toolProviders(descriptor),
                    () -> customizer + " returned null tool providers for " + descriptor));
            commandProviders.addAll(Objects.requireNonNull(customizer.commandProviders(descriptor),
                    () -> customizer + " returned null command providers for " + descriptor));
        }
        final CredentialStore credentials = resolveCredentialStore(agentRuntimeId);
        // The factory carries the skill registry as builder state that create() reads, so the assignment and the
        // call have to be one step. They are on one thread at startup; they are not once tenants arrive, and two
        // interleaved first-requests would otherwise give each runtime the other's skills. The lock is held
        // across MCP connection setup, which serialises first-use of two new tenants — the alternative is a
        // second factory configured by a second piece of code, which is the duplication this class exists to
        // prevent.
        synchronized (runtimeFactory) {
            runtimeFactory.withSkillRegistry(skillRegistry);
            return toolSpec.isMcpEnabled()
                    ? runtimeFactory.create(agentRuntimeId, agentExecutor, taskManager, bundle, fileSystem, credentials,
                            toolProviders, commandProviders, toolSpec.getMcpClientFactory().orElseThrow(),
                            toolSpec.getMcpServerConfigProvider().orElseThrow())
                    : runtimeFactory.create(agentRuntimeId, agentExecutor, taskManager, bundle, fileSystem, credentials,
                            toolProviders, commandProviders);
        }
    }

    /**
     * Returns the credential store this runtime should resolve secrets through — the tenant's when a factory was
     * configured, the stack-wide one otherwise.
     *
     * <p>
     * The factory is keyed on the discriminator alone, and the startup runtimes are passed {@code null} because
     * they have none. See {@link CredentialStoreFactory} for why credentials are a tenant-axis concern.
     */
    private CredentialStore resolveCredentialStore(AgentRuntimeId agentRuntimeId) {
        if (credentialStoreFactory == null) {
            return credentialStore;
        }
        return credentialStoreFactory.create(agentRuntimeId.discriminator().orElse(null));
    }

    private VirtualFileSystem createFileSystem(AgentRuntimeId agentRuntimeId, ResourceSink sink) {
        if (fileSystemSpec.getInstance().isPresent()) {
            // Caller-owned and shared by every runtime: used, never closed, and never given to the sink — doing
            // so would close it when the first tenant is evicted, for everyone. AimonStack records a degradation
            // when more than one runtime ends up here.
            return fileSystemSpec.getInstance().get();
        }
        final VirtualFileSystem fileSystem;
        if (fileSystemSpec.getFactory().isPresent()) {
            fileSystem = Objects.requireNonNull(fileSystemSpec.getFactory().get().create(agentRuntimeId),
                    "The file system factory returned null for " + agentRuntimeId);
        } else {
            // Per runtime, under the workspace root — see AgentWorkspaceLayout for why the discriminator is in
            // the path and why an unusable segment throws instead of being rewritten.
            final LocalFileSystem local = new LocalFileSystem(new LocalFileSystemConfig(
                    AgentWorkspaceLayout.resolve(fileSystemSpec.getWorkspaceRoot().orElseThrow(), agentRuntimeId)));
            local.initialize();
            fileSystem = local;
        }
        // Recorded, not closed here: AgentRuntime.close() has no fan-out to the file system it was built with, so
        // an unrecorded instance is a handle held until the process exits — once per evicted tenant.
        sink.own("fileSystem(" + agentRuntimeId + ")", fileSystem);
        return fileSystem;
    }

    /**
     * Builds a tenant runtime that owns what was created for it.
     *
     * <p>
     * This is the lazy half of the class: it is what an {@code AgentRuntimeResolver} calls on the first request
     * for an id nobody has used. The resources go into the returned value rather than onto the stack's teardown
     * plan, because this runtime is expected to be closed again long before the process exits.
     */
    @Override
    public ProvisionedAgentRuntime provision(AgentRuntimeId agentRuntimeId) {
        final List<AutoCloseable> owned = new ArrayList<>();
        final Assembly assembly = createRuntime(agentRuntimeId, (label, resource) -> owned.add(resource));
        final ProvisionedAgentRuntime.Builder builder = ProvisionedAgentRuntime.builder(assembly.getRuntime());
        owned.forEach(builder::owns);
        log.debug("Provisioned {} with {} owned resource(s)", agentRuntimeId, owned.size());
        return builder.build();
    }

    /**
     * Returns the agent names this provisioner can build, which is exactly the set declared in configuration.
     *
     * @return an immutable set
     */
    public Set<String> knownAgentNames() {
        return templates.keySet();
    }

    @Override
    public String toString() {
        return "StackAgentRuntimeProvisioner[agents=" + templates.keySet() + "]";
    }

    /** Receives a resource created for one runtime, together with the label it should appear under. */
    @FunctionalInterface
    public interface ResourceSink {

        /**
         * Records a resource whose lifetime is the runtime's.
         *
         * @param label
         *            how the resource should be identified in a teardown plan
         * @param resource
         *            the resource
         */
        void own(String label, AutoCloseable resource);
    }

    /** What one call to {@link #createRuntime(AgentRuntimeId, ResourceSink)} produced. */
    public static final class Assembly {

        private final OrcaAgentRuntime runtime;
        private final VirtualFileSystem fileSystem;

        private Assembly(OrcaAgentRuntime runtime, VirtualFileSystem fileSystem) {
            this.runtime = runtime;
            this.fileSystem = fileSystem;
        }

        /**
         * Returns the runtime.
         *
         * @return the runtime, never null
         */
        public OrcaAgentRuntime getRuntime() {
            return runtime;
        }

        /**
         * Returns the file system the runtime was built with — shared with every other runtime when the caller
         * supplied one, private to this runtime otherwise.
         *
         * @return the file system, never null
         */
        public VirtualFileSystem getFileSystem() {
            return fileSystem;
        }
    }

    /** Everything needed to build any runtime for one configured agent. */
    public static final class AgentTemplate {

        private final String agentRef;
        private final String bundleName;
        private final AgentBundle bundle;
        private final List<AgentRuntimeCustomizer> customizers;
        private final Map<String, String> properties;

        private AgentTemplate(AgentSpec spec, AgentBundle bundle) {
            this.agentRef = Objects.requireNonNull(spec.getName(), "agentRef must not be null");
            this.bundleName = Objects.requireNonNull(spec.getBundleName(), "bundleName must not be null");
            this.bundle = Objects.requireNonNull(bundle, "bundle must not be null");
            this.customizers = List.copyOf(spec.getCustomizers());
            this.properties = Map.copyOf(spec.getProperties());
        }

        /**
         * Creates a template from the spec that declared an agent and the bundle that was loaded for it.
         *
         * @param spec
         *            the declaration (must not be null)
         * @param bundle
         *            the loaded bundle (must not be null)
         * @return a template usable for every runtime of this agent
         */
        public static AgentTemplate of(AgentSpec spec, AgentBundle bundle) {
            Objects.requireNonNull(spec, "spec must not be null");
            return new AgentTemplate(spec, bundle);
        }

        /**
         * Describes the runtime with the given id as built from this template.
         *
         * <p>
         * This is what customizers decide on, and it is derived rather than stored because one template
         * describes an unbounded number of runtimes — one per tenant that has ever appeared.
         *
         * @param agentRuntimeId
         *            the runtime being built (must not be null)
         * @return the descriptor
         */
        public AgentDescriptor describe(AgentRuntimeId agentRuntimeId) {
            Objects.requireNonNull(agentRuntimeId, "agentRuntimeId must not be null");
            return AgentDescriptor.builder(agentRef, bundle).bundleName(bundleName)
                    .discriminator(agentRuntimeId.discriminator().orElse(null)).properties(properties).build();
        }

        /**
         * Returns the ref this agent is routed to under, which is the name segment of every runtime id built
         * from this template.
         *
         * @return the agent ref, never null
         */
        public String getAgentRef() {
            return agentRef;
        }

        /**
         * Returns the bundle directory the definition came from, which is where bundled skills are read from on
         * the classpath. Not necessarily the ref, and not necessarily the agent name in the bundle.
         *
         * @return the bundle name, never null
         */
        public String getBundleName() {
            return bundleName;
        }

        /**
         * Returns the loaded bundle.
         *
         * @return the bundle, never null
         */
        public AgentBundle getBundle() {
            return bundle;
        }

        /**
         * Returns the customizers applied to every runtime of this agent.
         *
         * @return an immutable list, possibly empty
         */
        public List<AgentRuntimeCustomizer> getCustomizers() {
            return customizers;
        }

        /**
         * Returns the host-supplied properties describing this agent.
         *
         * @return an immutable map, possibly empty
         */
        public Map<String, String> getProperties() {
            return properties;
        }
    }

    /** Builder for {@link StackAgentRuntimeProvisioner}. */
    public static final class Builder {

        private final Map<String, AgentTemplate> templates = new LinkedHashMap<>();
        private FileSystemSpec fileSystemSpec;
        private ToolSpec toolSpec;
        private OrcaAgentRuntimeFactory runtimeFactory;
        private OrcaAgentExecutor agentExecutor;
        private ScheduledTaskManager taskManager;
        private SkillParser skillParser;
        private CredentialStore credentialStore;
        private CredentialStoreFactory credentialStoreFactory;
        private boolean knowledgeToolsEnabled;
        private OrcaToolProvider memoryToolProvider;
        private List<AimonAgentCustomizer> agentCustomizers = List.of();

        private Builder() {
        }

        /**
         * Registers what to build for one agent name. The first template for a name wins.
         *
         * @param agentName
         *            the agent name as it appears in a runtime id (must not be null)
         * @param template
         *            what to build (must not be null)
         * @return this builder
         */
        public Builder template(String agentName, AgentTemplate template) {
            Objects.requireNonNull(agentName, "agentName must not be null");
            Objects.requireNonNull(template, "template must not be null");
            // Two specs can name the same agent with different discriminators. They differ only in which tenant
            // they pin, which is not part of a template, so keeping the first is not a choice between rivals.
            templates.putIfAbsent(agentName, template);
            return this;
        }

        /**
         * Sets how file systems are obtained.
         *
         * @param fileSystemSpec
         *            the spec (must not be null)
         * @return this builder
         */
        public Builder fileSystemSpec(FileSystemSpec fileSystemSpec) {
            this.fileSystemSpec = fileSystemSpec;
            return this;
        }

        /**
         * Sets the tool configuration every runtime is built with.
         *
         * @param toolSpec
         *            the spec (must not be null)
         * @return this builder
         */
        public Builder toolSpec(ToolSpec toolSpec) {
            this.toolSpec = toolSpec;
            return this;
        }

        /**
         * Registers the knowledge search tool with every runtime.
         *
         * <p>
         * Passed rather than inferred from the runtime factory, which also holds the store: the factory takes it
         * as a nullable constructor argument that nothing here can read back, and duplicating the null check in
         * two places is how the two answers drift apart.
         *
         * @param knowledgeToolsEnabled
         *            {@code true} when the stack resolved a knowledge store
         * @return this builder
         */
        public Builder knowledgeToolsEnabled(boolean knowledgeToolsEnabled) {
            this.knowledgeToolsEnabled = knowledgeToolsEnabled;
            return this;
        }

        /**
         * Registers the peer-memory tools with every runtime.
         *
         * <p>
         * A built provider rather than a flag, because the memory tools are constructed with the stores they read
         * — the decision of <i>whether</i> the tools can be registered at all is made once, where the stores and
         * the peer mode are both visible. See {@link at.aimon.bootstrap.assemble.MemoryAssembly}.
         *
         * @param memoryToolProvider
         *            the provider, or null when the stack has no memory or cannot give the tools an observer
         * @return this builder
         */
        public Builder memoryToolProvider(OrcaToolProvider memoryToolProvider) {
            this.memoryToolProvider = memoryToolProvider;
            return this;
        }

        /**
         * Sets the configured runtime factory. Shared, and used under its own monitor.
         *
         * @param runtimeFactory
         *            the factory (must not be null)
         * @return this builder
         */
        public Builder runtimeFactory(OrcaAgentRuntimeFactory runtimeFactory) {
            this.runtimeFactory = runtimeFactory;
            return this;
        }

        /**
         * Sets the shared executor every runtime is bound to.
         *
         * @param agentExecutor
         *            the executor (must not be null)
         * @return this builder
         */
        public Builder agentExecutor(OrcaAgentExecutor agentExecutor) {
            this.agentExecutor = agentExecutor;
            return this;
        }

        /**
         * Sets the scheduled task manager, or null when scheduling is disabled.
         *
         * @param taskManager
         *            the manager, may be null
         * @return this builder
         */
        public Builder taskManager(ScheduledTaskManager taskManager) {
            this.taskManager = taskManager;
            return this;
        }

        /**
         * Sets the parser used to materialise skills.
         *
         * @param skillParser
         *            the parser (must not be null)
         * @return this builder
         */
        public Builder skillParser(SkillParser skillParser) {
            this.skillParser = skillParser;
            return this;
        }

        /**
         * Sets the credential store handed to every runtime, or null when there is none.
         *
         * @param credentialStore
         *            the store, may be null
         * @return this builder
         */
        public Builder credentialStore(CredentialStore credentialStore) {
            this.credentialStore = credentialStore;
            return this;
        }

        /**
         * Sets the per-tenant credential store factory. When present it is consulted for every runtime and the
         * fixed {@link #credentialStore(CredentialStore)} is not used.
         *
         * @param credentialStoreFactory
         *            the factory, may be null
         * @return this builder
         */
        public Builder credentialStoreFactory(CredentialStoreFactory credentialStoreFactory) {
            this.credentialStoreFactory = credentialStoreFactory;
            return this;
        }

        /**
         * Sets the customizers consulted for every runtime this provisioner builds.
         *
         * @param agentCustomizers
         *            the customizers (must not be null); order is decided by
         *            {@link AimonAgentCustomizer#getOrder()}, not by this list
         * @return this builder
         */
        public Builder agentCustomizers(List<AimonAgentCustomizer> agentCustomizers) {
            this.agentCustomizers = List
                    .copyOf(Objects.requireNonNull(agentCustomizers, "agentCustomizers must not be null"));
            return this;
        }

        /**
         * Builds the provisioner.
         *
         * @return an immutable provisioner
         */
        public StackAgentRuntimeProvisioner build() {
            return new StackAgentRuntimeProvisioner(this);
        }
    }
}
