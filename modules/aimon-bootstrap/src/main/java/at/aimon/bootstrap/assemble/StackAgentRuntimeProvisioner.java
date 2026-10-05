package at.aimon.bootstrap.assemble;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import at.aimon.bootstrap.spec.ExecutionEnvironmentSpec;
import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory;
import at.aimon.core.agent.impl.orca.command.OrcaCommandProvider;
import at.aimon.core.agent.impl.orca.tool.OrcaBashToolProvider;
import at.aimon.core.agent.impl.orca.tool.OrcaKnowledgeToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.RuntimeBinding;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.environment.impl.PerRuntimeLocalEnvironmentProvider;
import at.aimon.core.filesystem.PathRule;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.ScopedVirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.parser.SkillParser;
import at.aimon.core.tools.bash.BackgroundBashManager;

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
 * <h2>What is per runtime and what is not</h2>
 *
 * <p>
 * The execution environment provider and the list of background commands are the stack's: one of each, alive until
 * the stack closes, so that evicting a tenant neither cuts off the commands it left running nor forgets them
 * (execution-environment design §4.3). A runtime owns its control store and its <i>binding</i> to the provider —
 * closing the binding is the eviction notice, and the provider then releases that runtime's share only.
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
    private final ExecutionEnvironmentSpec executionEnvironmentSpec;
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
    private final ExecutionEnvironmentProvider environmentProvider;
    /** The same object as {@link #environmentProvider} when the stack built the local default, else null. */
    private final PerRuntimeLocalEnvironmentProvider localWorkspaces;
    private final OrcaBashToolProvider bashToolProvider;

    private StackAgentRuntimeProvisioner(Builder builder) {
        this.templates = Map.copyOf(builder.templates);
        this.fileSystemSpec = Objects.requireNonNull(builder.fileSystemSpec, "fileSystemSpec must not be null");
        this.executionEnvironmentSpec = builder.executionEnvironmentSpec != null
                ? builder.executionEnvironmentSpec
                : ExecutionEnvironmentSpec.defaults();
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
        this.bashToolProvider = builder.backgroundBashManager != null
                ? new OrcaBashToolProvider(builder.backgroundBashManager)
                : null;

        // Decided once, last, so that nothing above can fail with a provider already built and nobody to close it.
        if (executionEnvironmentSpec.getSharedProvider().isPresent()) {
            this.localWorkspaces = null;
            this.environmentProvider = executionEnvironmentSpec.getSharedProvider().get();
        } else if (executionEnvironmentSpec.getProviderSupplier().isPresent()) {
            this.localWorkspaces = null;
            this.environmentProvider = Objects.requireNonNull(
                    executionEnvironmentSpec.getProviderSupplier().get().get(),
                    "The execution environment provider supplier returned null");
        } else {
            this.localWorkspaces = new PerRuntimeLocalEnvironmentProvider(this::createLocalWorkspace);
            this.environmentProvider = localWorkspaces;
        }
        // Every runtime gets the same provider, so the factory is told once instead of once per runtime.
        this.runtimeFactory.withExecutionEnvironmentProvider(environmentProvider);
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
     * Resources this creates — the runtime's binding to the execution environment provider, and its control store or
     * file system when that is not shared — are handed to {@code sink} rather than closed over, so the caller decides
     * whether they belong to the process or to the runtime. Nothing shared reaches the sink: the provider itself and a
     * supplied file system are used by every runtime, and closing either with any one of them would leave the rest
     * using a closed handle.
     *
     * <p>
     * The sink hears of them only once the runtime is complete. If building fails part-way, this closes the runtime
     * (when it got that far) and every resource it created, and rethrows; the sink is never called.
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

        // Held back from the sink until the runtime is complete (EE-23). A failure part-way leaves no runtime for the
        // caller to close — a tenant's resources would travel in a ProvisionedAgentRuntime that is never built — so
        // what was created is closed here instead, and the sink never hears of it, which is also what keeps the
        // startup path from closing the same resource twice.
        final List<OwnedResource> created = new ArrayList<>();
        OrcaAgentRuntime runtime = null;
        try {
            final Stores stores = createStores(agentRuntimeId,
                    (label, resource) -> created.add(new OwnedResource(label, resource)));
            final VirtualFileSystem controlFileSystem = stores.controlFileSystem;
            final SkillRegistry skillRegistry = OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(
                    template.getBundle(), controlFileSystem, StackPaths.USER_SKILLS_DIRECTORY,
                    StackPaths.BUNDLED_SKILLS_DIRECTORY,
                    StackPaths.AGENT_BUNDLE_BASE_PATH + "/" + template.getBundleName() + "/skills",
                    Thread.currentThread().getContextClassLoader(), skillParser);

            runtime = instantiate(agentRuntimeId, template.getBundle(), controlFileSystem, skillRegistry, descriptor,
                    applicable);

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
            final Assembly assembly = new Assembly(runtime, stores.fileSystem);
            created.forEach(owned -> sink.own(owned.label, owned.resource));
            return assembly;
        } catch (RuntimeException | Error e) {
            rollBack(agentRuntimeId, runtime, created, e);
            throw e;
        }
    }

    /**
     * Closes what a failed {@link #createRuntime} built, runtime first and then its resources newest-first — the same
     * order the teardown plan would have used. Close failures are attached to {@code cause} rather than thrown: the
     * build failure is what the caller needs to see.
     */
    private static void rollBack(AgentRuntimeId agentRuntimeId, OrcaAgentRuntime runtime, List<OwnedResource> created,
            Throwable cause) {
        log.warn("Building {} failed; closing the runtime and {} resource(s) created for it", agentRuntimeId,
                created.size());
        if (runtime != null) {
            closeInto(runtime, cause);
        }
        for (int i = created.size() - 1; i >= 0; i--) {
            closeInto(created.get(i).resource, cause);
        }
    }

    private static void closeInto(AutoCloseable resource, Throwable cause) {
        try {
            resource.close();
        } catch (Exception closeFailure) {
            cause.addSuppressed(closeFailure);
        }
    }

    /** A resource {@link #createRuntime} made, held until the runtime is complete. */
    private static final class OwnedResource {
        private final String label;
        private final AutoCloseable resource;

        OwnedResource(String label, AutoCloseable resource) {
            this.label = label;
            this.resource = resource;
        }
    }

    private OrcaAgentRuntime instantiate(AgentRuntimeId agentRuntimeId, AgentBundle bundle,
            VirtualFileSystem controlFileSystem, SkillRegistry skillRegistry, AgentDescriptor descriptor,
            List<AimonAgentCustomizer> applicable) {
        // The base list is the one every runtime gets; a customizer appends to it and cannot take anything out of
        // it. That asymmetry is the point — "agent A also has the ticketing tools" is a contribution, while
        // "agent A does not have Bash" is a stack-wide decision (ToolSpec) that one agent must not make for the
        // others by side effect.
        final List<OrcaToolProvider> toolProviders = new ArrayList<>(toolSpec.resolveProviders());
        // The default Bash provider makes a task list per tool registry, which dies with the runtime. Swapped for one
        // over the stack's list, so the runtime rebuilt after an eviction finds the tasks its predecessor started.
        if (bashToolProvider != null) {
            toolProviders
                    .replaceAll(provider -> provider instanceof OrcaBashToolProvider ? bashToolProvider : provider);
        }
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
                    ? runtimeFactory.create(agentRuntimeId, agentExecutor, taskManager, bundle, controlFileSystem,
                            credentials, toolProviders, commandProviders, toolSpec.getMcpClientFactory().orElseThrow(),
                            toolSpec.getMcpServerConfigProvider().orElseThrow())
                    : runtimeFactory.create(agentRuntimeId, agentExecutor, taskManager, bundle, controlFileSystem,
                            credentials, toolProviders, commandProviders);
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

    /**
     * Creates what a runtime reads and writes — its workspace file system and the control store beside it — and binds
     * the runtime to the stack's execution environment provider (execution-environment design §4.3, §9.2). Everything
     * created here goes to {@code sink}; the provider and a caller-supplied shared file system do not.
     *
     * <p>
     * The binding is made first and therefore closed last: after the runtime, after its control store. Closing it
     * tells the provider this runtime is gone.
     *
     * <p>
     * With the local default the workspace is the provider's, one slot per runtime id. Local shape: the slot owns the
     * workspace at the runtime's directory, and the control store is a separate local file system at its
     * {@code .aimon/} — the same physical paths as before the split, hidden from the file tools. Supplied or factory
     * shape: one file system serves both, the control store as its {@code .aimon/} subtree and the workspace behind
     * the slot's path rules, so {@code .aimon/} stays hidden from the tools there too. A factory-made file system
     * belongs to the <i>slot</i>, not to the runtime: two runtimes of one id can overlap (an invalidated one closes
     * when its last holder lets go), and the old one's close must not take the file system out from under the new.
     */
    private Stores createStores(AgentRuntimeId agentRuntimeId, ResourceSink sink) {
        sink.own("environmentBinding(" + agentRuntimeId + ")", bind(agentRuntimeId));

        final boolean shared = fileSystemSpec.getInstance().isPresent();
        final boolean factoryMade = !shared && fileSystemSpec.getFactory().isPresent();
        if (!shared && !factoryMade) {
            return createLocalStores(agentRuntimeId, sink);
        }
        final VirtualFileSystem fileSystem;
        if (shared) {
            // Caller-owned and shared by every runtime: used, never closed, and never given to the sink — doing
            // so would close it when the first tenant is evicted, for everyone. AimonStack records a degradation
            // when more than one runtime ends up here.
            fileSystem = fileSystemSpec.getInstance().get();
        } else if (localWorkspaces != null) {
            // Made by the slot when it was bound above, and closed with it.
            fileSystem = localWorkspaces.workspace(agentRuntimeId).rawFileSystem();
        } else {
            // A caller's provider has no slot to own it, so it stays this runtime's: made per runtime built, closed
            // with it. Two overlapping runtimes of one id each hold their own instance, so neither closes the
            // other's. AgentRuntime.close() has no fan-out to it, hence the sink.
            fileSystem = Objects.requireNonNull(fileSystemSpec.getFactory().get().create(agentRuntimeId),
                    "The file system factory returned null for " + agentRuntimeId);
            sink.own("fileSystem(" + agentRuntimeId + ")", fileSystem);
        }
        return new Stores(fileSystem, new ScopedVirtualFileSystem(fileSystem, StackPaths.CONTROL_DIRECTORY));
    }

    private Stores createLocalStores(AgentRuntimeId agentRuntimeId, ResourceSink sink) {
        // Per runtime, under the workspace root — see AgentWorkspaceLayout for why the discriminator is in the path
        // and why an unusable segment throws instead of being rewritten.
        final Path workspace = localWorkspaceRoot(agentRuntimeId);
        final LocalFileSystem control = new LocalFileSystem(
                new LocalFileSystemConfig(workspace.resolve(StackPaths.CONTROL_DIRECTORY).toString()));
        control.initialize();
        sink.own("controlFileSystem(" + agentRuntimeId + ")", control);
        final VirtualFileSystem workspaceFileSystem = localWorkspaces != null
                ? localWorkspaces.workspace(agentRuntimeId).fileSystem()
                : control;
        return new Stores(workspaceFileSystem, control);
    }

    private Path localWorkspaceRoot(AgentRuntimeId agentRuntimeId) {
        return Path.of(AgentWorkspaceLayout.resolve(fileSystemSpec.getWorkspaceRoot().orElseThrow(), agentRuntimeId));
    }

    /**
     * Tells the provider a runtime is being built. A provider that answers {@code null} is treated as keeping nothing
     * per runtime — the contract says never null, and failing every runtime over it helps nobody.
     */
    private RuntimeBinding bind(AgentRuntimeId agentRuntimeId) {
        final RuntimeBinding binding = environmentProvider.bindRuntime(agentRuntimeId);
        if (binding == null) {
            log.warn("{} returned a null RuntimeBinding for {}; treating it as RuntimeBinding.NONE",
                    environmentProvider.getClass().getName(), agentRuntimeId);
            return RuntimeBinding.NONE;
        }
        return binding;
    }

    /**
     * Builds the local workspace of one runtime id — the function the stack's default provider keeps a slot per id
     * with. Mirrors the three file system shapes of {@link FileSystemSpec}.
     */
    private LocalExecutionEnvironmentProvider createLocalWorkspace(AgentRuntimeId agentRuntimeId) {
        final LocalExecutionEnvironmentProvider.Builder builder = LocalExecutionEnvironmentProvider.builder()
                .maxStagedBytes(executionEnvironmentSpec.getMaxStagedBytes())
                .contentSearch(executionEnvironmentSpec.isContentSearch())
                .backgroundCommandTimeout(executionEnvironmentSpec.getBackgroundCommandTimeout().orElse(null));
        if (executionEnvironmentSpec.isControlWritable()) {
            builder.pathRules(List.of(PathRule.readOnly(LocalExecutionEnvironmentProvider.DEFAULT_STAGING_ROOT)));
        }
        if (fileSystemSpec.getInstance().isPresent()) {
            builder.fileSystem(fileSystemSpec.getInstance().get());
        } else if (fileSystemSpec.getFactory().isPresent()) {
            builder.ownedFileSystem(Objects.requireNonNull(fileSystemSpec.getFactory().get().create(agentRuntimeId),
                    "The file system factory returned null for " + agentRuntimeId));
        } else {
            builder.workspaceRoot(localWorkspaceRoot(agentRuntimeId));
        }
        return builder.build();
    }

    /**
     * Returns the execution environment provider when the stack built it and must close it — the local default, or
     * one from {@code ExecutionEnvironmentSpec.provider(...)}. Empty for a caller-owned ({@code shared}) provider and
     * for one that is not closeable.
     *
     * @return the provider to close when the stack closes
     */
    public Optional<AutoCloseable> ownedEnvironmentProvider() {
        return !executionEnvironmentSpec.isCallerOwned() && environmentProvider instanceof AutoCloseable closeable
                ? Optional.of(closeable)
                : Optional.empty();
    }

    /** What {@link #createStores} produced for one runtime. */
    private static final class Stores {
        private final VirtualFileSystem fileSystem;
        private final VirtualFileSystem controlFileSystem;

        Stores(VirtualFileSystem fileSystem, VirtualFileSystem controlFileSystem) {
            this.fileSystem = fileSystem;
            this.controlFileSystem = controlFileSystem;
        }
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
         * Returns the file system {@code AimonStack.fileSystem(id)} answers with for this runtime — shared with every
         * other runtime when the caller supplied one, private to this runtime otherwise. The control store is
         * {@link OrcaAgentRuntime#getControlFileSystem()}. Which file system that is depends on the shape (EE-22):
         *
         * <ul>
         * <li>{@code FileSystemSpec.localAt} with the stack's own provider: the runtime's workspace, as its file
         * tools see it ({@code .aimon/} hidden).</li>
         * <li>A supplied or factory-made file system: that file system itself — the workspace with the control store
         * as its {@code .aimon/} subtree, nothing hidden.</li>
         * <li>{@code FileSystemSpec.localAt} with a caller's provider ({@code ExecutionEnvironmentSpec.provider} or
         * {@code shared}): the stack does not know that provider's workspace, so this is the <b>control store</b>,
         * the same store as {@link OrcaAgentRuntime#getControlFileSystem()}. Read the workspace from the provider.</li>
         * </ul>
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
        private ExecutionEnvironmentSpec executionEnvironmentSpec;
        private ToolSpec toolSpec;
        private OrcaAgentRuntimeFactory runtimeFactory;
        private OrcaAgentExecutor agentExecutor;
        private ScheduledTaskManager taskManager;
        private SkillParser skillParser;
        private CredentialStore credentialStore;
        private CredentialStoreFactory credentialStoreFactory;
        private boolean knowledgeToolsEnabled;
        private OrcaToolProvider memoryToolProvider;
        private BackgroundBashManager backgroundBashManager;
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
         * Sets where the runtimes' executions run (default: one local provider with a workspace per runtime).
         *
         * @param executionEnvironmentSpec
         *            the spec, or null for the default
         * @return this builder
         */
        public Builder executionEnvironmentSpec(ExecutionEnvironmentSpec executionEnvironmentSpec) {
            this.executionEnvironmentSpec = executionEnvironmentSpec;
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
         * Sets the stack's list of background commands, which every runtime's {@code Bash}, {@code BashOutput} and
         * {@code KillShell} then share.
         *
         * @param backgroundBashManager
         *            the manager, or null to leave each runtime the task list its own tool registry makes
         * @return this builder
         */
        public Builder backgroundBashManager(BackgroundBashManager backgroundBashManager) {
            this.backgroundBashManager = backgroundBashManager;
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
         * Builds the provisioner. This is where the stack's execution environment provider comes into being; the
         * caller enrolls {@link StackAgentRuntimeProvisioner#ownedEnvironmentProvider()} for teardown.
         *
         * @return an immutable provisioner
         */
        public StackAgentRuntimeProvisioner build() {
            return new StackAgentRuntimeProvisioner(this);
        }
    }
}
