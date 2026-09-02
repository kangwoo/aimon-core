package at.aimon.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.bootstrap.spec.AgentRuntimeSpec;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.AimonAgentCustomizer;
import at.aimon.bootstrap.spec.CredentialStoreFactory;
import at.aimon.bootstrap.spec.ExecutorSpec;
import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.KnowledgeStoreFactory;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.bootstrap.spec.SchedulingSpec;
import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.bootstrap.spec.SkillApprovalSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.queue.MessageQueueRepository;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.skill.parser.SkillParser;

/**
 * A complete, declarative description of an AIMON stack — the input to {@link AimonStackBuilder}.
 *
 * <p>
 * This type knows nothing about properties files, YAML, environment variables, HTTP, or any framework's
 * annotations. It is a plain immutable value: whoever holds the configuration turns it into one of these, and
 * the builder turns one of these into a running object graph. That split is what lets the same assembly serve a
 * Spring autoconfiguration, a CLI, and a plain JUnit test without any of them knowing about the others.
 *
 * <h2>Minimum viable spec</h2>
 *
 * <pre>
 * {@code
 * AimonStackSpec spec = AimonStackSpec.builder()
 *         .workspaceRoot("/var/lib/aimon")
 *         .llm(LlmSpec.of(myLlmClient))
 *         .agent(AgentSpec.named("ops"))
 *         .build();
 * }
 * </pre>
 *
 * <p>
 * Everything else has a defensible default: a local file system at the workspace root, an in-memory session
 * record store, fail-closed skill approval, the core tool set minus nothing, and scheduling off.
 *
 * <h2>Validation</h2>
 *
 * <p>
 * {@link Builder#build()} rejects a spec that cannot produce a working stack — no LLM client, no agents, a
 * duplicate runtime id. It deliberately validates <i>the spec</i> and not the world: whether the workspace
 * directory exists or the LLM credentials are valid is not knowable here, and failing on it would make the
 * spec untestable.
 */
public final class AimonStackSpec {

    private final String workspaceRoot;
    private final LlmSpec llm;
    private final List<AgentSpec> agents;
    private final FileSystemSpec fileSystem;
    private final CredentialStore credentialStore;
    private final CredentialStoreFactory credentialStoreFactory;
    private final List<AimonAgentCustomizer> agentCustomizers;
    private final ExecutionBudget defaultBudget;
    private final SessionSpec session;
    private final SkillApprovalSpec skillApproval;
    private final ToolSpec tools;
    private final SchedulingSpec scheduling;
    private final AgentRuntimeSpec agentRuntimes;
    private final ExecutorSpec executor;
    private final KnowledgeStore knowledgeStore;
    private final KnowledgeStoreFactory knowledgeStoreFactory;
    private final MemorySpec memory;
    private final SkillParser skillParser;
    private final MessageQueueRepository messageQueueRepository;

    private AimonStackSpec(Builder builder) {
        this.workspaceRoot = builder.workspaceRoot;
        this.llm = Objects.requireNonNull(builder.llm,
                "An LLM spec is required — build an LlmClient in the configuration layer and pass LlmSpec.of(client)");
        this.agents = List.copyOf(builder.agents);
        this.credentialStore = builder.credentialStore;
        this.credentialStoreFactory = builder.credentialStoreFactory;
        this.agentCustomizers = List.copyOf(builder.agentCustomizers);
        this.defaultBudget = Objects.requireNonNullElseGet(builder.defaultBudget, ExecutionBudget::unlimited);
        this.session = Objects.requireNonNullElseGet(builder.session, SessionSpec::defaults);
        this.skillApproval = Objects.requireNonNullElseGet(builder.skillApproval, SkillApprovalSpec::denyAll);
        this.tools = Objects.requireNonNullElseGet(builder.tools, ToolSpec::defaults);
        this.scheduling = Objects.requireNonNullElseGet(builder.scheduling, SchedulingSpec::disabled);
        this.agentRuntimes = Objects.requireNonNullElseGet(builder.agentRuntimes, AgentRuntimeSpec::defaults);
        this.executor = Objects.requireNonNullElseGet(builder.executor, ExecutorSpec::defaults);
        this.knowledgeStore = builder.knowledgeStore;
        this.knowledgeStoreFactory = builder.knowledgeStoreFactory;
        this.memory = builder.memory;
        this.skillParser = builder.skillParser;
        this.messageQueueRepository = builder.messageQueueRepository;

        if (this.agents.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one AgentSpec is required — a stack with no agent has nothing to route sessions to");
        }
        if (this.knowledgeStore != null && this.knowledgeStoreFactory != null) {
            // Silently preferring one would give the caller a knowledge base they did not configure, and the
            // symptom — lookups missing — is indistinguishable from an empty one.
            throw new IllegalArgumentException(
                    "Set either knowledgeStore or knowledgeStoreFactory, not both — they build the same collaborator");
        }
        if (this.credentialStore != null && this.credentialStoreFactory != null) {
            // The factory would win for every runtime, so the fixed store would resolve nothing while looking
            // configured — and a credential that silently fails to resolve reads as a missing secret, which is
            // the one failure a caller will go looking for in the wrong place.
            throw new IllegalArgumentException(
                    "Set either credentialStore or credentialStoreFactory, not both — the factory answers for the"
                            + " no-tenant runtimes too, by being passed a null discriminator");
        }
        if (this.memory != null && this.memory.getRepresentationStore().isPresent()
                && this.executor.getMemoryContextProvider().isPresent()) {
            // The executor takes exactly one provider, so one of these two would be dropped — and the symptom is a
            // memory part that is present, plausible, and read from the store the caller did not mean. Either the
            // stack builds the provider from this spec or the caller supplies its own; not both.
            throw new IllegalArgumentException(
                    "Set either a MemorySpec with a representation store or ExecutorSpec.memoryContextProvider, not"
                            + " both — they install the same collaborator and only one of them would survive");
        }
        if (builder.fileSystem != null) {
            this.fileSystem = builder.fileSystem;
        } else {
            if (this.workspaceRoot == null || this.workspaceRoot.isBlank()) {
                throw new IllegalArgumentException("Either workspaceRoot or an explicit FileSystemSpec is required");
            }
            this.fileSystem = FileSystemSpec.localAt(this.workspaceRoot);
        }
        rejectDuplicateRuntimeIdentities(this.agents);
    }

    /**
     * Rejects two agent specs that would resolve to the same runtime id.
     *
     * <p>
     * The runtime manager would quietly hand the second spec the first one's runtime — same id, cache hit —
     * so the duplicate's bundle, file system and tool set would be silently discarded. Catching it here turns
     * a mystery at request time into a message at build time.
     */
    private static void rejectDuplicateRuntimeIdentities(List<AgentSpec> agents) {
        final List<String> seen = new ArrayList<>();
        for (AgentSpec agent : agents) {
            final String identity = agent.getName() + agent.getDiscriminator().map(d -> ":" + d).orElse("");
            if (seen.contains(identity)) {
                throw new IllegalArgumentException("Duplicate agent runtime identity 'agent:" + identity
                        + "'. Two agents with the same name need different discriminators.");
            }
            seen.add(identity);
        }
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
     * Returns the workspace root, when one was set.
     *
     * @return the root directory, or empty when an explicit file system spec was supplied instead
     */
    public Optional<String> getWorkspaceRoot() {
        return Optional.ofNullable(workspaceRoot);
    }

    /**
     * Returns the LLM spec.
     *
     * @return the spec, never null
     */
    public LlmSpec getLlm() {
        return llm;
    }

    /**
     * Returns the agents to stand runtimes up for, in declaration order. The first is the primary.
     *
     * @return an immutable, non-empty list
     */
    public List<AgentSpec> getAgents() {
        return agents;
    }

    /**
     * Returns the file system spec — defaulted to a local file system at the workspace root.
     *
     * @return the spec, never null
     */
    public FileSystemSpec getFileSystem() {
        return fileSystem;
    }

    /**
     * Returns the credential store used for reference-based credential resolution in tools.
     *
     * @return the store, or empty when references are not resolvable
     */
    public Optional<CredentialStore> getCredentialStore() {
        return Optional.ofNullable(credentialStore);
    }

    /**
     * Returns the factory that builds one credential store per tenant.
     *
     * @return the factory, or empty when every runtime shares {@link #getCredentialStore()}
     */
    public Optional<CredentialStoreFactory> getCredentialStoreFactory() {
        return Optional.ofNullable(credentialStoreFactory);
    }

    /**
     * Returns the customizers consulted for every runtime the stack builds, in the order they were supplied.
     *
     * <p>
     * The provisioner sorts them by {@link AimonAgentCustomizer#getOrder()} before use; this list is what the
     * caller gave.
     *
     * @return an immutable list, possibly empty
     */
    public List<AimonAgentCustomizer> getAgentCustomizers() {
        return agentCustomizers;
    }

    /**
     * Returns the per-execution budget applied to turns opened through the router.
     *
     * @return the budget, never null
     */
    public ExecutionBudget getDefaultBudget() {
        return defaultBudget;
    }

    /**
     * Returns the session routing spec.
     *
     * @return the spec, never null
     */
    public SessionSpec getSession() {
        return session;
    }

    /**
     * Returns the skill approval spec.
     *
     * @return the spec, never null
     */
    public SkillApprovalSpec getSkillApproval() {
        return skillApproval;
    }

    /**
     * Returns the executor spec.
     *
     * @return the spec, never null
     */
    public ExecutorSpec getExecutor() {
        return executor;
    }

    /**
     * Returns the application-scoped knowledge store injected into every runtime.
     *
     * @return the store, or empty when no knowledge base is wired or when a factory supplies it instead
     */
    public Optional<KnowledgeStore> getKnowledgeStore() {
        return Optional.ofNullable(knowledgeStore);
    }

    /**
     * Returns the factory that builds the knowledge store over the stack's own runtime registry.
     *
     * @return the factory, or empty when the store is supplied directly or not at all
     */
    public Optional<KnowledgeStoreFactory> getKnowledgeStoreFactory() {
        return Optional.ofNullable(knowledgeStoreFactory);
    }

    /**
     * Returns the peer memory the stack reads from.
     *
     * @return the spec, or empty when the stack has no memory
     */
    public Optional<MemorySpec> getMemory() {
        return Optional.ofNullable(memory);
    }

    /**
     * Returns the caller-supplied skill parser.
     *
     * @return the parser, or empty when the stack builds its own
     */
    public Optional<SkillParser> getSkillParser() {
        return Optional.ofNullable(skillParser);
    }

    /**
     * Returns the store the mid-turn input queue is kept in.
     *
     * <p>
     * The queue holds input that arrived while a turn was already running — the thing {@code offerAsync}
     * reports as {@code QUEUED}. It is not part of {@link #getSession()} because it is not keyed by session:
     * entries carry an {@code AgentRuntimeId}, and three collaborators read them (the ReAct loop drains it
     * mid-turn, the live session enqueues into it, the subagent manager reads it for forks).
     *
     * <p>
     * Left empty the stack uses an in-memory repository, so queued input belongs to the node that took it. No
     * distributed implementation ships; this seam exists so that one an application wrote can reach an
     * assembled stack instead of only a hand-built object graph.
     *
     * @return the repository, or empty to use the stack default (in-memory, this node only)
     */
    public Optional<MessageQueueRepository> getMessageQueueRepository() {
        return Optional.ofNullable(messageQueueRepository);
    }

    /**
     * Returns the tool spec.
     *
     * @return the tool spec, never null
     */
    public ToolSpec getTools() {
        return tools;
    }

    /**
     * Returns the scheduling spec.
     *
     * @return the spec, never null
     */
    public SchedulingSpec getScheduling() {
        return scheduling;
    }

    /**
     * Returns how runtimes created after startup — one per discriminator — are held and reclaimed.
     *
     * @return the spec, never null
     */
    public AgentRuntimeSpec getAgentRuntimes() {
        return agentRuntimes;
    }

    @Override
    public String toString() {
        return "AimonStackSpec[agents=" + agents + ", " + fileSystem + ", " + tools + ", " + skillApproval + ", "
                + scheduling + "]";
    }

    /** Builder for {@link AimonStackSpec}. */
    public static final class Builder {

        private String workspaceRoot;
        private LlmSpec llm;
        private final List<AgentSpec> agents = new ArrayList<>();
        private FileSystemSpec fileSystem;
        private CredentialStore credentialStore;
        private CredentialStoreFactory credentialStoreFactory;
        private final List<AimonAgentCustomizer> agentCustomizers = new ArrayList<>();
        private ExecutionBudget defaultBudget;
        private SessionSpec session;
        private SkillApprovalSpec skillApproval;
        private ToolSpec tools;
        private SchedulingSpec scheduling;
        private AgentRuntimeSpec agentRuntimes;
        private ExecutorSpec executor;
        private KnowledgeStore knowledgeStore;
        private KnowledgeStoreFactory knowledgeStoreFactory;
        private MemorySpec memory;
        private SkillParser skillParser;
        private MessageQueueRepository messageQueueRepository;

        private Builder() {
        }

        /**
         * Sets the workspace root directory. Used as the local file system's base path unless
         * {@link #fileSystem(FileSystemSpec)} overrides it.
         *
         * @param workspaceRoot
         *            the directory
         * @return this builder
         */
        public Builder workspaceRoot(String workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
            return this;
        }

        /**
         * Sets the LLM spec. Required.
         *
         * @param llm
         *            the spec
         * @return this builder
         */
        public Builder llm(LlmSpec llm) {
            this.llm = llm;
            return this;
        }

        /**
         * Adds one agent. At least one is required; the first added is the primary.
         *
         * @param agent
         *            the spec (must not be null)
         * @return this builder
         */
        public Builder agent(AgentSpec agent) {
            this.agents.add(Objects.requireNonNull(agent, "agent must not be null"));
            return this;
        }

        /**
         * Adds several agents.
         *
         * @param agents
         *            the specs (must not be null)
         * @return this builder
         */
        public Builder agents(List<AgentSpec> agents) {
            Objects.requireNonNull(agents, "agents must not be null").forEach(this::agent);
            return this;
        }

        /**
         * Sets the executor spec — streaming, tracing, cost accounting, memory context.
         *
         * @param executor
         *            the spec, or {@code null} for {@link ExecutorSpec#defaults()}
         * @return this builder
         */
        public Builder executor(ExecutorSpec executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Sets the application-scoped knowledge store handed to every runtime.
         *
         * @param knowledgeStore
         *            the store, or {@code null} for none
         * @return this builder
         */
        public Builder knowledgeStore(KnowledgeStore knowledgeStore) {
            this.knowledgeStore = knowledgeStore;
            return this;
        }

        /**
         * Sets a factory that builds the knowledge store over the stack's own {@code AgentRuntimeRegistry}.
         *
         * <p>
         * Mutually exclusive with {@link #knowledgeStore(KnowledgeStore)}. Use this one only when the store has
         * to resolve runtimes — see {@link KnowledgeStoreFactory}.
         *
         * @param knowledgeStoreFactory
         *            the factory, or {@code null} for none
         * @return this builder
         */
        public Builder knowledgeStoreFactory(KnowledgeStoreFactory knowledgeStoreFactory) {
            this.knowledgeStoreFactory = knowledgeStoreFactory;
            return this;
        }

        /**
         * Sets the store the mid-turn input queue is kept in.
         *
         * <p>
         * Supply one only to share the queue outside this process. The default is in-memory, which is correct
         * for a single node: input queued behind a running turn is drained by the same turn that is running.
         *
         * @param messageQueueRepository
         *            the repository, or {@code null} for the in-memory default
         * @return this builder
         */
        public Builder messageQueueRepository(MessageQueueRepository messageQueueRepository) {
            this.messageQueueRepository = messageQueueRepository;
            return this;
        }

        /**
         * Sets the peer memory the stack reads from — the injected memory prompt part, and the memory tools when
         * the spec can give them an observer.
         *
         * <p>
         * Mutually exclusive with {@code ExecutorSpec.memoryContextProvider} once the spec carries a representation
         * store: both install the executor's one provider.
         *
         * @param memory
         *            the spec, or {@code null} for no memory
         * @return this builder
         */
        public Builder memory(MemorySpec memory) {
            this.memory = memory;
            return this;
        }

        /**
         * Supplies the parser used for agent-bundle and materialized skill files.
         *
         * <p>
         * The stack otherwise builds its own, over a {@code LocalShell} it creates and closes last. Supply one
         * here when the same parser must also be used <i>outside</i> the stack — a front end that loads the
         * agent bundle itself has already had to build one, and two parsers mean two shells, of which only one
         * is on the teardown plan.
         *
         * <p>
         * A supplied parser is caller-owned: whatever it holds, the caller closes ("만든 쪽이 닫는다").
         *
         * @param skillParser
         *            the parser, or {@code null} to let the stack build its own
         * @return this builder
         */
        public Builder skillParser(SkillParser skillParser) {
            this.skillParser = skillParser;
            return this;
        }

        /**
         * Overrides the file system spec.
         *
         * @param fileSystem
         *            the spec
         * @return this builder
         */
        public Builder fileSystem(FileSystemSpec fileSystem) {
            this.fileSystem = fileSystem;
            return this;
        }

        /**
         * Sets the credential store handed to every agent runtime.
         *
         * @param credentialStore
         *            the store, or null
         * @return this builder
         */
        public Builder credentialStore(CredentialStore credentialStore) {
            this.credentialStore = credentialStore;
            return this;
        }

        /**
         * Sets the factory that builds one credential store per tenant, instead of sharing one across the stack.
         *
         * <p>
         * It is asked for the runtimes created at startup as well, with a {@code null} discriminator — see
         * {@link CredentialStoreFactory}.
         *
         * @param credentialStoreFactory
         *            the factory, or null
         * @return this builder
         */
        public Builder credentialStoreFactory(CredentialStoreFactory credentialStoreFactory) {
            this.credentialStoreFactory = credentialStoreFactory;
            return this;
        }

        /**
         * Adds customizers consulted for every runtime the stack builds — at startup and on first use of a
         * tenant alike.
         *
         * <p>
         * Additive, so several sources can contribute independently. Execution order is decided by
         * {@link AimonAgentCustomizer#getOrder()}, not by the order of these calls.
         *
         * @param agentCustomizers
         *            the customizers (must not be null)
         * @return this builder
         */
        public Builder agentCustomizers(List<AimonAgentCustomizer> agentCustomizers) {
            this.agentCustomizers.addAll(Objects.requireNonNull(agentCustomizers, "agentCustomizers must not be null"));
            return this;
        }

        /**
         * Adds one customizer.
         *
         * @param agentCustomizer
         *            the customizer (must not be null)
         * @return this builder
         */
        public Builder agentCustomizer(AimonAgentCustomizer agentCustomizer) {
            this.agentCustomizers.add(Objects.requireNonNull(agentCustomizer, "agentCustomizer must not be null"));
            return this;
        }

        /**
         * Sets the default per-execution budget.
         *
         * @param defaultBudget
         *            the budget
         * @return this builder
         */
        public Builder defaultBudget(ExecutionBudget defaultBudget) {
            this.defaultBudget = defaultBudget;
            return this;
        }

        /**
         * Sets the session routing spec.
         *
         * @param session
         *            the spec
         * @return this builder
         */
        public Builder session(SessionSpec session) {
            this.session = session;
            return this;
        }

        /**
         * Sets the skill approval spec.
         *
         * @param skillApproval
         *            the spec
         * @return this builder
         */
        public Builder skillApproval(SkillApprovalSpec skillApproval) {
            this.skillApproval = skillApproval;
            return this;
        }

        /**
         * Sets the tool spec.
         *
         * @param tools
         *            the spec
         * @return this builder
         */
        public Builder tools(ToolSpec tools) {
            this.tools = tools;
            return this;
        }

        /**
         * Sets the scheduling spec.
         *
         * @param scheduling
         *            the spec
         * @return this builder
         */
        public Builder scheduling(SchedulingSpec scheduling) {
            this.scheduling = scheduling;
            return this;
        }

        /**
         * Sets how runtimes for ids carrying a discriminator are held and reclaimed. Defaults to
         * {@link AgentRuntimeSpec#defaults()}.
         *
         * <p>
         * Applies only to runtimes created on first use. The agents listed with {@link #agent(AgentSpec)} are
         * built at startup and held for the life of the stack whatever is set here.
         *
         * @param agentRuntimes
         *            the spec
         * @return this builder
         */
        public Builder agentRuntimes(AgentRuntimeSpec agentRuntimes) {
            this.agentRuntimes = agentRuntimes;
            return this;
        }

        /**
         * Validates and builds the spec.
         *
         * @return the immutable spec
         * @throws IllegalArgumentException
         *             if the spec could not produce a working stack
         */
        public AimonStackSpec build() {
            return new AimonStackSpec(this);
        }
    }
}
