package at.aimon.spring.boot.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import at.aimon.bootstrap.runtime.AgentRuntimeEviction;
import at.aimon.bootstrap.spec.AgentRuntimeSpec;
import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.core.knowledge.SimpleDocumentChunker;
import at.aimon.core.memory.MemoryInjectionMode;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
import at.aimon.session.routing.DeploymentMode;

/**
 * Binding target for the {@code aimon.*} configuration tree.
 *
 * <p>
 * <b>Why this class has setters.</b> Every other value type in AIMON is immutable with a builder — see
 * {@code .claude/rules/immutability-pattern.md}. This one is not, and the exception is deliberate: a
 * {@code @ConfigurationProperties} class is a binding target owned by the framework, not a domain object.
 * JavaBean binding is the shape Spring Boot's configuration processor understands best (relaxed binding,
 * partial overrides across profiles, {@code @ConfigurationProperties} scanning, IDE metadata), and a
 * constructor-bound immutable variant would trade all of that for an immutability that nothing in the
 * application ever observes — the bean is populated once at startup and read-only from then on.
 *
 * <p>
 * <b>Why validation lives in {@link #afterPropertiesSet()}.</b> The failure a user is most likely to hit is an
 * empty configuration, and the message they get has to name the property that would fix it. Validating inside
 * whichever slice happens to need the value first would make that message depend on bean creation order, which
 * is not something this starter controls. This bean is a dependency of every slice, so its initialization
 * always runs first — putting the checks here makes the message deterministic. The one check that is
 * <em>not</em> here is the workspace write probe: it is I/O, it belongs with the filesystem concern, and it is
 * the filesystem slice that knows whether the workspace is even going to be used.
 *
 * <p>
 * <b>Why the checks are hand-written rather than JSR-380 annotations.</b> {@code @Validated} on a
 * {@code @ConfigurationProperties} class is not a validation guarantee — it is a request that Boot honours only
 * when a JSR-303 provider is on the classpath. {@code ConfigurationPropertiesBinder} looks up its JSR-303
 * validator behind an {@code isJsr303Present} check and installs nothing when the answer is no, so annotating
 * this class without forcing {@code hibernate-validator} onto every application that embeds AIMON would produce
 * constraints that are silently unenforced — the failure mode these checks exist to remove, reintroduced one
 * layer up. Forcing the dependency is the other half of a choice with no good side: a starter should not add a
 * validation engine to someone's deployment for rules that fit in the methods below. The one thing annotations
 * would have given for free — the property path in the message — is written out by hand here instead.
 *
 * <p>
 * <b>Declared and rejected beats undeclared.</b> Several selectors below accept values this version cannot yet
 * honour, and they are bound anyway so that {@link #afterPropertiesSet()} can refuse them by name. Leaving them
 * undeclared is not the safer option: Boot ignores unknown properties in silence, so the configuration would
 * read as if it had taken effect. A property that binds and does nothing is worse than one that does not exist;
 * a property that binds and says why it cannot work yet is better than either.
 *
 * <p>
 * Only the checks that hold regardless of which beans exist live here. Whether {@code aimon.llm.api-key} is
 * required, for instance, depends on whether the application defined its own {@code LlmClient} — so that check
 * belongs in the LLM slice, after {@code @ConditionalOnMissingBean} has answered the question. This bean cannot
 * see beans, and guessing from here would reject a configuration that is in fact complete.
 */
@ConfigurationProperties(prefix = AimonProperties.PREFIX)
public class AimonProperties implements InitializingBean {

    /** Root prefix of every property this starter binds. */
    public static final String PREFIX = "aimon";

    /** Kill switch — {@code false} publishes a disabled facade and creates nothing else. */
    public static final String ENABLED = PREFIX + ".enabled";

    /** Refuse to start when the assembled stack reports any degraded capability. */
    public static final String FAIL_FAST = PREFIX + ".fail-fast";

    /** Directory the agent's file tools are rooted at. */
    public static final String WORKSPACE_ROOT = PREFIX + ".workspace.root";

    /** Whether startup verifies the workspace is writable. */
    public static final String WORKSPACE_ENSURE_WRITABLE = PREFIX + ".workspace.ensure-writable";

    /** Map of agent ref to agent settings. */
    public static final String AGENTS = PREFIX + ".agents";

    /**
     * Map of credential profile to its fields — {@code aimon.credentials.<profile>.<field>}.
     *
     * <p>
     * The plural is load-bearing rather than grammatical. The masking function this starter publishes
     * ({@code AimonObservabilityAutoConfiguration.ActuatorSanitizingConfiguration}) matches Boot's own word list,
     * whose one substring is {@code credentials}, and it tests the whole key rather than its last segment — so
     * every leaf under this prefix is masked no matter what the leaf is called, which is the only way an
     * arbitrarily-named tree can be covered by a name-based rule at all. Spelled {@code aimon.credential.*} the
     * same tree would print in full at {@code show-values=ALWAYS}. {@code AimonPropertyMaskingTest} pins this.
     */
    public static final String CREDENTIALS = PREFIX + ".credentials";

    /** Agent used by submits that do not name one. */
    public static final String DEFAULT_AGENT = PREFIX + ".agent-defaults.default-agent";

    /** What happens to a tenant runtime nobody is using. */
    public static final String AGENT_RUNTIME_EVICTION = PREFIX + ".agent-runtime.eviction";

    /** How long an unused tenant runtime is kept. */
    public static final String AGENT_RUNTIME_IDLE_TTL = PREFIX + ".agent-runtime.idle-ttl";

    /** How often idle tenant runtimes are looked for. */
    public static final String AGENT_RUNTIME_SWEEP_INTERVAL = PREFIX + ".agent-runtime.sweep-interval";

    /** Ceiling on tenant runtimes held at once. */
    public static final String AGENT_RUNTIME_MAX_ENTRIES = PREFIX + ".agent-runtime.max-entries";

    /** LLM vendor selector. */
    public static final String LLM_PROVIDER = PREFIX + ".llm.provider";

    /** Credential for the selected LLM vendor. */
    public static final String LLM_API_KEY = PREFIX + ".llm.api-key";

    /** Backing store for session records. */
    public static final String SESSION_STORE = PREFIX + ".session.store";

    /** Deployment topology of the session layer. */
    public static final String SESSION_MODE = PREFIX + ".session.mode";

    /** Identity this node claims session leases under. */
    public static final String SESSION_NODE_ID = PREFIX + ".session.node-id";

    /** Default answer to "may this skill run?" when no prior approval covers it. */
    public static final String SKILL_APPROVAL_MODE = PREFIX + ".skill.approval.mode";

    /** Skill names auto-approved under the allow-list mode. */
    public static final String SKILL_APPROVAL_ALLOW = PREFIX + ".skill.approval.allow";

    /** How long a suspended turn waits for an out-of-band approval. */
    public static final String SKILL_APPROVAL_PENDING_TURN_TTL = PREFIX + ".skill.approval.pending-turn-ttl";

    /** Engine that runs scheduled tasks. */
    public static final String SCHEDULING_BACKEND = PREFIX + ".scheduling.backend";

    /** Whether the scheduling engine starts with the application context. */
    public static final String SCHEDULING_AUTO_STARTUP = PREFIX + ".scheduling.auto-startup";

    /** Whether AIMON borrows the application's Quartz scheduler instead of building its own. */
    public static final String SCHEDULING_QUARTZ_USE_APPLICATION_SCHEDULER = PREFIX
            + ".scheduling.quartz.use-application-scheduler";

    /** Name the Quartz scheduler AIMON builds registers itself under. */
    public static final String SCHEDULING_QUARTZ_INSTANCE_NAME = PREFIX + ".scheduling.quartz.instance-name";

    /** Worker threads of the Quartz scheduler AIMON builds. */
    public static final String SCHEDULING_QUARTZ_THREAD_COUNT = PREFIX + ".scheduling.quartz.thread-count";

    /** Whether the threads of the Quartz scheduler AIMON builds are daemons. */
    public static final String SCHEDULING_QUARTZ_DAEMON_THREADS = PREFIX + ".scheduling.quartz.daemon-threads";

    /** Whether shutting the Quartz scheduler AIMON built down waits for running jobs. */
    public static final String SCHEDULING_QUARTZ_WAIT_FOR_JOBS_ON_SHUTDOWN = PREFIX
            + ".scheduling.quartz.wait-for-jobs-on-shutdown";

    /** Whether the agent records spans. */
    public static final String TRACING_ENABLED = PREFIX + ".tracing.enabled";

    /** How much of a span's content is recorded. */
    public static final String TRACING_PAYLOAD_CAPTURE = PREFIX + ".tracing.payload-capture";

    /** Ceiling on the characters kept per captured payload. */
    public static final String TRACING_MAX_CHARS = PREFIX + ".tracing.max-chars";

    /** Ceiling on the spans the in-memory span store keeps. */
    public static final String TRACING_MAX_SPANS = PREFIX + ".tracing.max-spans";

    /** Store backing knowledge search. */
    public static final String KNOWLEDGE_BACKEND = PREFIX + ".knowledge.backend";

    /** Characters per chunk the built-in keyword store indexes. */
    public static final String KNOWLEDGE_CHUNK_SIZE = PREFIX + ".knowledge.chunk-size";

    /** Characters two neighbouring chunks share. */
    public static final String KNOWLEDGE_CHUNK_OVERLAP = PREFIX + ".knowledge.chunk-overlap";

    /** Stores backing peer memory. */
    public static final String MEMORY_BACKEND = PREFIX + ".memory.backend";

    /** Workspace every representation and observation is stored under. */
    public static final String MEMORY_WORKSPACE_ID = PREFIX + ".memory.workspace-id";

    /** Whether memory belongs to one named peer or to whoever the execution runs for. */
    public static final String MEMORY_PEER_MODE = PREFIX + ".memory.peer-mode";

    /** Identity of the one peer, under {@code peer-mode=fixed}. */
    public static final String MEMORY_PEER_ID = PREFIX + ".memory.peer-id";

    /** How much of the memory snapshot reaches the prompt. */
    public static final String MEMORY_INJECTION_MODE = PREFIX + ".memory.injection-mode";

    /** Ceiling on the injected memory part's estimated tokens. */
    public static final String MEMORY_MAX_TOKENS = PREFIX + ".memory.max-tokens";

    /** What is stripped out of an observation before it is stored. */
    public static final String MEMORY_REDACTION = PREFIX + ".memory.redaction";

    /**
     * Backend value selecting the built-in heap-backed memory stores.
     *
     * <p>
     * A literal for the same reason as {@link #SCHEDULING_BACKEND_QUARTZ}: the store beans are conditional on it,
     * and {@code @ConditionalOnProperty} needs a compile-time constant.
     */
    public static final String MEMORY_BACKEND_IN_MEMORY = "in-memory";

    /** Provider value selecting the Anthropic client. Also the value assumed when the property is absent. */
    public static final String PROVIDER_ANTHROPIC = "anthropic";

    /** Provider value selecting the OpenAI client. */
    public static final String PROVIDER_OPENAI = "openai";

    /** Provider value that builds no client at all — the application defines its own {@code LlmClient} bean. */
    public static final String PROVIDER_NONE = "none";

    /**
     * Backend value selecting the Quartz scheduler.
     *
     * <p>
     * Spelt out as a literal rather than derived from {@link SchedulingBackend#QUARTZ}, because
     * {@code @ConditionalOnProperty(havingValue = ...)} needs a compile-time constant and an enum name is not one.
     * The relaxed binder accepts this exact spelling for that enum, so the two stay in step.
     */
    public static final String SCHEDULING_BACKEND_QUARTZ = "quartz";

    private boolean enabled = true;

    /**
     * Defaults to {@code false}, which reverses the original design intent, because three of the documented
     * server defaults each register a degradation on purpose: the in-memory session store, the deny-all skill
     * approval channel and disabled scheduling. Defaulting this to {@code true} would mean the minimal
     * five-property configuration this starter advertises fails to start. Degradations are logged at WARN
     * either way; this knob only decides whether they are also fatal.
     */
    private boolean failFast;

    private final Workspace workspace = new Workspace();

    private Map<String, AgentEntry> agents = new LinkedHashMap<>();

    /**
     * Profile to field to value, the shape {@code CredentialStore} is already keyed by.
     *
     * <p>
     * The one day-1 extension point that is data rather than code, which is why it has a property tree while the
     * customizer, the file backend and the approval channel do not. A deployment's set of credential profiles
     * changes with the environment it runs in, and expressing it as a {@code @Bean} makes that set a compile-time
     * decision — the deployment that adds a profile has to change Java to do it.
     */
    private Map<String, Map<String, String>> credentials = new LinkedHashMap<>();

    private final AgentDefaults agentDefaults = new AgentDefaults();

    private final AgentRuntimeProperties agentRuntime = new AgentRuntimeProperties();

    private final Llm llm = new Llm();

    private final Budget budget = new Budget();

    private final SessionProperties session = new SessionProperties();

    private final Tools tools = new Tools();

    private final Skill skill = new Skill();

    private final Scheduling scheduling = new Scheduling();

    private final Tracing tracing = new Tracing();

    private final Knowledge knowledge = new Knowledge();

    private final Memory memory = new Memory();

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        validateWorkspace();
        validateAgents();
        validateCredentials();
        validateBounds();
        validateSessionTopology();
        validateScheduling();
        validateTracing();
        validateKnowledge();
        validateMemory();
    }

    /**
     * Refuses tracing settings the selected mode cannot act on.
     *
     * <p>
     * This method replaced a blanket refusal of {@code aimon.tracing.enabled=true}, which was correct while the
     * starter built no {@code Tracer} and became a lie the moment it did. What survives from it is the shape: a
     * setting that binds and reaches nothing is refused by name rather than ignored.
     *
     * <p>
     * {@code max-chars} is refused under {@code payload-capture=none} for the same reason. It is not a
     * contradiction the way an unreachable tracer was — a cap on content nobody records is merely inert — but
     * inert is exactly what nobody notices, and the deployment that set it believed it had bounded something.
     */
    private void validateTracing() {
        if (!tracing.isEnabled()) {
            final List<String> ineffective = new ArrayList<>();
            if (tracing.getPayloadCapture() != PayloadCapture.NONE) {
                ineffective.add(TRACING_PAYLOAD_CAPTURE);
            }
            if (tracing.getMaxChars() != null) {
                ineffective.add(TRACING_MAX_CHARS);
            }
            if (tracing.getMaxSpans() != null) {
                ineffective.add(TRACING_MAX_SPANS);
            }
            refuseIneffective(ineffective, TRACING_ENABLED + "=false records no span at all",
                    "Set " + TRACING_ENABLED + "=true");
            return;
        }
        if (tracing.getPayloadCapture() == PayloadCapture.NONE && tracing.getMaxChars() != null) {
            refuseIneffective(List.of(TRACING_MAX_CHARS),
                    TRACING_PAYLOAD_CAPTURE + "=none keeps no payload for a character cap to apply to",
                    "Set " + TRACING_PAYLOAD_CAPTURE + "=full");
        }
        requireAtLeastOne(tracing.getMaxChars(), TRACING_MAX_CHARS);
        requireAtLeastOne(tracing.getMaxSpans(), TRACING_MAX_SPANS);
    }

    /**
     * Refuses knowledge settings the selected backend cannot act on.
     *
     * <p>
     * The chunk settings belong to the built-in keyword store and to nothing else. Under {@code backend=supplied}
     * the application's own store decides how it splits a document, so a chunk size bound here is a number this
     * starter reads and then has nowhere to put — the case {@link #refuseIneffective} exists for.
     */
    private void validateKnowledge() {
        final List<String> chunkSettingsInUse = new ArrayList<>();
        if (knowledge.getChunkSize() != null) {
            chunkSettingsInUse.add(KNOWLEDGE_CHUNK_SIZE);
        }
        if (knowledge.getChunkOverlap() != null) {
            chunkSettingsInUse.add(KNOWLEDGE_CHUNK_OVERLAP);
        }
        if (knowledge.getBackend() == KnowledgeBackend.NONE) {
            refuseIneffective(chunkSettingsInUse, KNOWLEDGE_BACKEND + "=none builds no knowledge store",
                    "Set " + KNOWLEDGE_BACKEND + "=keyword");
            return;
        }
        if (knowledge.getBackend() == KnowledgeBackend.SUPPLIED) {
            refuseIneffective(
                    chunkSettingsInUse, KNOWLEDGE_BACKEND
                            + "=supplied takes the store from the application, which does its own" + " chunking",
                    "Set " + KNOWLEDGE_BACKEND + "=keyword to have AIMON build the store");
            return;
        }
        requireAtLeastOne(knowledge.getChunkSize(), KNOWLEDGE_CHUNK_SIZE);
        requireNonNegative(knowledge.getChunkOverlap(), KNOWLEDGE_CHUNK_OVERLAP);
        final int chunkSize = knowledge.chunkSizeOrDefault();
        final int overlap = knowledge.chunkOverlapOrDefault();
        if (overlap >= chunkSize) {
            throw new IllegalStateException(KNOWLEDGE_CHUNK_OVERLAP + "=" + overlap + " must be smaller than "
                    + KNOWLEDGE_CHUNK_SIZE + "=" + chunkSize + ". An overlap at or above the chunk size means each"
                    + " chunk starts at or before the previous one, so indexing a document of any length would not"
                    + " terminate.");
        }
    }

    /**
     * Refuses memory settings that would bind and reach nothing, and requires the two that have no safe default.
     *
     * <p>
     * The two requirements are here rather than in the slice because both are questions about what memory
     * <em>is</em>, and neither has an answer this starter could pick. A workspace id is the partition every
     * representation and observation is filed under, so a default would be one shared namespace for every
     * deployment that forgot; and a peer id under {@code peer-mode=fixed} names whose memory every agent reads,
     * so a default would be a privacy decision made by omission. Both fail with the property name in hand.
     *
     * <p>
     * The remaining checks are the usual {@link #refuseIneffective} shape, with one entry worth naming: a peer id
     * left behind under {@code peer-mode=caller} reads as "this deployment has a peer" and is not consulted at
     * all. It is what a properties file looks like a minute after someone switched modes.
     */
    private void validateMemory() {
        if (memory.getBackend() == MemoryBackend.NONE) {
            refuseIneffective(memorySettingsInUse(), MEMORY_BACKEND + "=none wires no memory at all",
                    "Set " + MEMORY_BACKEND + " to in-memory or supplied");
            return;
        }
        if (isBlank(memory.getWorkspaceId())) {
            throw new IllegalStateException(MEMORY_BACKEND + "=" + asPropertyValue(memory.getBackend()) + " needs "
                    + MEMORY_WORKSPACE_ID + ". Every representation and observation is filed under a workspace, and"
                    + " a default would silently put unrelated deployments in the same one.");
        }
        if (memory.peerModeOrDefault() == MemoryPeerMode.FIXED && isBlank(memory.getPeerId())) {
            throw new IllegalStateException(MEMORY_PEER_MODE + "=fixed needs " + MEMORY_PEER_ID + ", which names the"
                    + " one peer whose memory every agent in this application reads and writes. Set it, or set "
                    + MEMORY_PEER_MODE + "=caller to follow whoever the execution runs for — that mode registers no"
                    + " memory tools, and the stack says so as a degradation.");
        }
        if (memory.peerModeOrDefault() == MemoryPeerMode.CALLER && !isBlank(memory.getPeerId())) {
            refuseIneffective(List.of(MEMORY_PEER_ID),
                    MEMORY_PEER_MODE + "=caller resolves the peer per execution and never reads this one",
                    "Set " + MEMORY_PEER_MODE + "=fixed");
        }
        requireNonNegative(memory.getMaxTokens(), MEMORY_MAX_TOKENS);
    }

    private List<String> memorySettingsInUse() {
        final List<String> set = new ArrayList<>();
        if (!isBlank(memory.getWorkspaceId())) {
            set.add(MEMORY_WORKSPACE_ID);
        }
        if (memory.getPeerMode() != null) {
            set.add(MEMORY_PEER_MODE);
        }
        if (!isBlank(memory.getPeerId())) {
            set.add(MEMORY_PEER_ID);
        }
        if (memory.getInjectionMode() != null) {
            set.add(MEMORY_INJECTION_MODE);
        }
        if (memory.getMaxTokens() != null) {
            set.add(MEMORY_MAX_TOKENS);
        }
        if (memory.getRedaction() != null) {
            set.add(MEMORY_REDACTION);
        }
        return set;
    }

    /**
     * Refuses scheduling settings the selected backend cannot act on.
     *
     * <p>
     * Everything under {@code aimon.scheduling} except the backend itself is nullable, so "left alone" and "set to
     * the value that happens to be the default" are distinguishable here — and the second one is what this method
     * exists to catch. A {@code thread-count} under a borrowed scheduler is the clearest case: it binds, it reads
     * as applied, and the pool it names belongs to somebody else. Refusing costs a startup failure with the
     * property named; ignoring costs a production incident with no evidence in it.
     */
    private void validateScheduling() {
        if (scheduling.getBackend() == SchedulingBackend.NONE) {
            final List<String> ineffective = new ArrayList<>(quartzSettingsInUse());
            if (scheduling.getAutoStartup() != null) {
                ineffective.add(SCHEDULING_AUTO_STARTUP);
            }
            refuseIneffective(ineffective, SCHEDULING_BACKEND + "=none builds no scheduling engine",
                    "Set " + SCHEDULING_BACKEND + " to in-memory or quartz");
            return;
        }
        if (scheduling.getBackend() == SchedulingBackend.IN_MEMORY) {
            refuseIneffective(quartzSettingsInUse(),
                    SCHEDULING_BACKEND + "=in-memory runs the engine's own scheduler, not Quartz",
                    "Set " + SCHEDULING_BACKEND + "=quartz");
            return;
        }
        if (scheduling.getQuartz().usesApplicationScheduler()) {
            final List<String> ineffective = new ArrayList<>(quartzSettingsInUse());
            ineffective.remove(SCHEDULING_QUARTZ_USE_APPLICATION_SCHEDULER);
            refuseIneffective(ineffective,
                    SCHEDULING_QUARTZ_USE_APPLICATION_SCHEDULER + "=true borrows the application's scheduler, which"
                            + " AIMON neither configures nor shuts down",
                    "Configure it through spring.quartz.* instead, or set "
                            + SCHEDULING_QUARTZ_USE_APPLICATION_SCHEDULER + "=false to have AIMON build its own");
            return;
        }
        requireAtLeastOne(scheduling.getQuartz().getThreadCount(), SCHEDULING_QUARTZ_THREAD_COUNT);
    }

    /** Returns the {@code aimon.scheduling.quartz.*} properties that were actually set. */
    private List<String> quartzSettingsInUse() {
        final Quartz quartz = scheduling.getQuartz();
        final List<String> set = new ArrayList<>();
        if (quartz.getUseApplicationScheduler() != null) {
            set.add(SCHEDULING_QUARTZ_USE_APPLICATION_SCHEDULER);
        }
        if (quartz.getInstanceName() != null) {
            set.add(SCHEDULING_QUARTZ_INSTANCE_NAME);
        }
        if (quartz.getThreadCount() != null) {
            set.add(SCHEDULING_QUARTZ_THREAD_COUNT);
        }
        if (quartz.getDaemonThreads() != null) {
            set.add(SCHEDULING_QUARTZ_DAEMON_THREADS);
        }
        if (quartz.getWaitForJobsOnShutdown() != null) {
            set.add(SCHEDULING_QUARTZ_WAIT_FOR_JOBS_ON_SHUTDOWN);
        }
        return set;
    }

    private static void refuseIneffective(List<String> properties, String because, String remedy) {
        if (properties.isEmpty()) {
            return;
        }
        throw new IllegalStateException(because + ", so the following are bound and act on nothing:" + "\n  - "
                + String.join("\n  - ", properties) + "\nRemove them, or " + remedy + ".");
    }

    /**
     * Checks the settings that only mean something in relation to each other.
     *
     * <p>
     * Distributed mode is the one selector whose correctness is not a property of its own value. Every check here
     * is a combination this starter can evaluate without seeing a single bean — which is the whole reason they are
     * in this class rather than in the session slice, where the beans are visible but the failure would come
     * after some other slice's message.
     */
    private void validateSessionTopology() {
        if (!isBlank(session.getNodeId())) {
            requireNodeId(session.getNodeId());
        }
        if (session.getMode() != DeploymentMode.DISTRIBUTED) {
            return;
        }
        if (isBlank(session.getNodeId())) {
            throw new IllegalStateException(SESSION_MODE + "=distributed requires " + SESSION_NODE_ID + " — it is the"
                    + " identity this node claims session leases under, and it must differ between nodes and survive"
                    + " a reconnect on the same one. A generated per-process id would satisfy the first and fail the"
                    + " second: after a restart the node would be unable to reclaim the sessions it had held. Use the"
                    + " pod or host name (${HOSTNAME} in most container images).");
        }
        if (session.getStore() == SessionStoreType.IN_MEMORY) {
            throw new IllegalStateException(SESSION_MODE + "=distributed cannot run with " + SESSION_STORE
                    + "=in-memory. Leases, signals and inboxes would be shared while every node kept its transcripts"
                    + " on its own heap, so routing a session to its holder would send it to a node that has never"
                    + " seen the conversation — worse than single-node rather than a step towards multi-node. Set "
                    + SESSION_STORE + " to postgres, mongodb or redis.");
        }
    }

    private void validateWorkspace() {
        if (isBlank(workspace.getRoot())) {
            throw new IllegalStateException(WORKSPACE_ROOT + " must be set — it is the directory the agent's file"
                    + " tools are rooted at, and there is no safe default for it. Point it at a directory the"
                    + " application may write to, or define your own VirtualFileSystem bean to take the workspace"
                    + " out of this starter's hands entirely.");
        }
    }

    /**
     * Checks the agent map and the ref that submits fall back to.
     *
     * <p>
     * The one rule worth explaining is the last: with more than one agent declared, an unnamed default is
     * rejected rather than resolved. A map has an iteration order — this one is a {@link LinkedHashMap}, so it
     * is declaration order — and taking its first entry would work, deterministically, right up until someone
     * reorders the YAML or a profile contributes an agent that lands first. Traffic would then move to a
     * different agent with no property changed and nothing logged. One agent is different: there is no choice
     * to get wrong, so it stays implicit.
     */
    private void validateAgents() {
        if (agents.isEmpty() && isBlank(agentDefaults.getDefaultAgent())) {
            throw new IllegalStateException("No agent is configured. Set " + DEFAULT_AGENT + " to the name of an"
                    + " agent bundle on the classpath (agents/<name>/agent.md), or declare it under " + AGENTS
                    + ".<name>.");
        }
        agents.forEach((ref, entry) -> {
            requireIdSegment(ref, AGENTS + ".<ref>", "agent ref");
            if (!isBlank(entry.getBundle())) {
                requireBundleName(entry.getBundle(), AGENTS + "." + ref + ".bundle");
            }
        });
        final String defaultAgent = agentDefaults.getDefaultAgent();
        if (!agents.isEmpty() && !isBlank(defaultAgent) && !agents.containsKey(defaultAgent)) {
            throw new IllegalStateException(DEFAULT_AGENT + "=" + defaultAgent + " names an agent that is not"
                    + " declared under " + AGENTS + " (declared: " + agents.keySet() + ").");
        }
        if (agents.size() > 1 && isBlank(defaultAgent)) {
            throw new IllegalStateException(AGENTS + " declares " + agents.size() + " agents (" + agents.keySet()
                    + "), so " + DEFAULT_AGENT + " must name the one that serves submits which do not choose."
                    + " Picking the first declared entry would move traffic to another agent the day the"
                    + " declaration order changes.");
        }
    }

    /**
     * Refuses credential entries no reference could reach.
     *
     * <p>
     * The dot rule is not stylistic. A tool names a credential as {@code profile.field} and splits on a dot it
     * requires to appear exactly once ({@code TypeActionHandler.resolveCredentialRef}), so a profile or field
     * spelled with a dot of its own binds perfectly and is then unreachable — the same shape as the tracing
     * settings refused above, and the reason it is caught here rather than at the point of use is that the point
     * of use is a tool call in production.
     *
     * <p>
     * This makes the property surface narrower than the bean it builds: {@code InMemoryCredentialStore} will hold
     * a dotted profile quite happily, and an application that has some other way of resolving one may still
     * define the store itself. What is refused is declaring one <em>here</em>, where the only consumer is the
     * reference syntax that cannot express it.
     */
    private void validateCredentials() {
        credentials.forEach((profile, fields) -> {
            requireReferenceable(profile, CREDENTIALS + ".<profile>", "credential profile");
            if (fields == null || fields.isEmpty()) {
                throw new IllegalStateException(CREDENTIALS + "." + profile + " declares no fields. A profile is"
                        + " referenced as '" + profile + ".<field>', so an empty one can only ever resolve to a"
                        + " missing credential at the moment a tool asks for it.");
            }
            fields.keySet().forEach(
                    field -> requireReferenceable(field, CREDENTIALS + "." + profile + ".<field>", "credential field"));
        });
    }

    /**
     * Requires a name the {@code profile.field} reference syntax can express.
     *
     * @param value
     *            the profile or field name as it was bound
     * @param key
     *            the property key to name in the message
     * @param what
     *            what the name is, for the message
     */
    private static void requireReferenceable(String value, String key, String what) {
        if (isBlank(value)) {
            throw new IllegalStateException(key + " must not be blank.");
        }
        if (value.indexOf('.') >= 0) {
            throw new IllegalStateException(key + "=" + value + " contains a dot, and a " + what + " cannot: a tool"
                    + " names a credential as 'profile.field' and requires exactly one dot, so this entry would"
                    + " bind and then be unreachable. Use a name without dots.");
        }
    }

    /**
     * Rejects values that are the right type and the wrong number.
     *
     * <p>
     * The framework rejects most of these too, from constructors several layers down — {@code ExecutionBudget}
     * refuses a ceiling below one, {@code SessionRouterConfig} refuses an unusable cache bound. What it cannot
     * do is say which property produced the number, because by then the number has been copied out of the
     * property tree and into a builder argument. Checking here costs a few lines and buys the name.
     *
     * <p>
     * A {@code null} passes every check, but not as a way for a user to remove a ceiling. Writing
     * {@code aimon.budget.max-tokens=} does not produce one: Boot converts the empty value to {@code null} and
     * the JavaBean binder then declines to call the setter, so the field keeps its initialiser and the default
     * comes back. Nothing in the property tree can null these fields out, and that matches the intent — see the
     * {@link Budget} javadoc for why an unbounded loop is not something a server configuration should be able to
     * ask for. The tolerance here is for values set programmatically, and it keeps this check and the spec
     * assembler agreeing about what {@code null} means.
     */
    private void validateBounds() {
        requireAtLeastOne(budget.getMaxIterations(), PREFIX + ".budget.max-iterations");
        requireAtLeastOne(budget.getMaxTokens(), PREFIX + ".budget.max-tokens");
        requireAtLeastOne(session.getCache().getMaxEntries(), PREFIX + ".session.cache.max-entries");
        requirePositive(budget.getMaxWallClock(), PREFIX + ".budget.max-wall-clock");
        requirePositive(llm.getTimeout(), PREFIX + ".llm.timeout");
        requirePositive(session.getCache().getIdleTtl(), PREFIX + ".session.cache.idle-ttl");
        requireNonNegative(session.getShutdownDrainTimeout(), PREFIX + ".session.shutdown-drain-timeout");
        requireAtLeastOne(agentRuntime.getMaxEntries(), AGENT_RUNTIME_MAX_ENTRIES);
        requirePositive(agentRuntime.getIdleTtl(), AGENT_RUNTIME_IDLE_TTL);
        requirePositive(agentRuntime.getSweepInterval(), AGENT_RUNTIME_SWEEP_INTERVAL);
        requirePositive(skill.getApproval().getPendingTurnTtl(), SKILL_APPROVAL_PENDING_TURN_TTL);
        requireNoBlankEntry(skill.getApproval().getAllow(), SKILL_APPROVAL_ALLOW);
    }

    private static void requireAtLeastOne(Integer value, String property) {
        if (value != null && value < 1) {
            throw new IllegalStateException(property + "=" + value + " must be at least 1. Zero and negative"
                    + " values are not a way to say \"unlimited\", and neither is clearing the property — an"
                    + " empty value restores the default. Raise the number instead.");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalStateException(property + "=" + value + " must be a positive duration.");
        }
    }

    private static void requireNonNegative(Duration value, String property) {
        if (value != null && value.isNegative()) {
            throw new IllegalStateException(property + "=" + value + " must not be negative.");
        }
    }

    private static void requireNonNegative(Integer value, String property) {
        if (value != null && value < 0) {
            throw new IllegalStateException(property + "=" + value + " must not be negative.");
        }
    }

    /**
     * Rejects a blank entry in a list of names.
     *
     * <p>
     * A blank entry is never what was meant, and it is silent everywhere downstream — an allow-list holding one
     * matches a skill whose name is blank, which is no skill. It usually arrives from an indexed property with a
     * gap in it, or from a trailing comma.
     */
    private static void requireNoBlankEntry(List<String> values, String property) {
        for (int i = 0; i < values.size(); i++) {
            if (isBlank(values.get(i))) {
                throw new IllegalStateException(property + "[" + i + "] is blank. Remove the entry — a blank name"
                        + " matches no skill, so it is either a gap in an indexed property or a stray comma.");
            }
        }
    }

    /**
     * Rejects a value that cannot be a segment of an agent runtime id.
     *
     * <p>
     * {@code AgentRuntimeId} refuses these too, but from a static factory two layers into the stack builder,
     * where the message can only quote the value. The colon is the id's own separator: {@code agent:ops:acme}
     * parses back into a name and a discriminator, so a ref containing one would produce an id that reads as an
     * agent this deployment never declared.
     */
    private static void requireIdSegment(String value, String property, String what) {
        if (isBlank(value)) {
            throw new IllegalStateException(property + " must not be blank — it is the " + what + ".");
        }
        if (value.indexOf(':') >= 0) {
            throw new IllegalStateException(property + "=" + value + " must not contain ':' — it is the separator"
                    + " between the agent name and the tenant in a runtime id (agent:<ref>:<tenant>).");
        }
    }

    /**
     * Rejects a node id one of the three lease stores would refuse.
     *
     * <p>
     * The colon is a Redis constraint, not a general one: {@code RedisSessionLeaseStore} stamps the lock value as
     * {@code holderId + ':' + token} and reads it back with {@code lastIndexOf(':')}, so it rejects a holder id
     * containing one. Mongo and Postgres do not care. Checking it for all three is the deliberate answer to a
     * choice with no clean side — this class cannot see beans, so it cannot know which lease store the deployment
     * will supply, and the alternative to the intersection is a value that binds cleanly and then throws on the
     * first {@code tryAcquire}, i.e. on the first request rather than at startup. The cost is a rejected
     * {@code host:port}; the replacement is the host name alone, or the pod name.
     *
     * <p>
     * The id is not normalized. Rewriting {@code a:b} into {@code a-b} would put an id in the lease rows and the
     * logs that nobody configured, and reading a stuck lease is hard enough without the holder having a name that
     * appears in no file.
     */
    private static void requireNodeId(String value) {
        if (value.indexOf(':') >= 0) {
            throw new IllegalStateException(SESSION_NODE_ID + "=" + value + " must not contain ':'. It becomes the"
                    + " lease holder identity, and the Redis lease store stores it in a colon-delimited stamp, so"
                    + " it refuses one. Use the host or pod name without the port.");
        }
    }

    /**
     * Rejects a bundle name that would resolve to something other than one directory under {@code agents/}.
     *
     * <p>
     * The name is pasted into the classpath location {@code agents/<bundle>/agent.md}, so a separator or a
     * {@code ..} segment in it selects a resource elsewhere on the classpath. That was unreachable while the
     * bundle name had to equal the ref, since a ref is a map key a deployment writes deliberately; it becomes
     * reachable the moment the two are allowed to differ, and this is the check that keeps the property meaning
     * what it says.
     */
    private static void requireBundleName(String value, String property) {
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || "..".equals(value)) {
            throw new IllegalStateException(property + "=" + value + " must be a single directory name, not a"
                    + " path. The bundle is read from the classpath location agents/<bundle>/agent.md.");
        }
    }

    /**
     * Renders an enum constant the way it is written in a properties file.
     *
     * <p>
     * A message that quotes {@code IN_MEMORY} sends the reader looking for a string they never typed. Relaxed
     * binding accepts the kebab-case spelling, the YAML examples use it, and so should anything that repeats a
     * value back.
     *
     * @param value
     *            the bound constant
     * @return its kebab-case spelling
     */
    public static String asPropertyValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Resolves the agent ref that submits without an explicit agent should use.
     *
     * <p>
     * Falling back to the sole map entry is safe precisely because {@link #validateAgents()} has already refused
     * a configuration where the fallback would be a choice: past one agent, the default must be named.
     *
     * @return the configured default agent, or the single declared agent when no default is named
     */
    public String resolveDefaultAgentRef() {
        if (!isBlank(agentDefaults.getDefaultAgent())) {
            return agentDefaults.getDefaultAgent();
        }
        return agents.keySet().iterator().next();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public Map<String, AgentEntry> getAgents() {
        return agents;
    }

    public void setAgents(Map<String, AgentEntry> agents) {
        this.agents = agents == null ? new LinkedHashMap<>() : agents;
    }

    public Map<String, Map<String, String>> getCredentials() {
        return credentials;
    }

    public void setCredentials(Map<String, Map<String, String>> credentials) {
        this.credentials = credentials == null ? new LinkedHashMap<>() : credentials;
    }

    public AgentDefaults getAgentDefaults() {
        return agentDefaults;
    }

    public AgentRuntimeProperties getAgentRuntime() {
        return agentRuntime;
    }

    public Llm getLlm() {
        return llm;
    }

    public Budget getBudget() {
        return budget;
    }

    public SessionProperties getSession() {
        return session;
    }

    public Tools getTools() {
        return tools;
    }

    public Skill getSkill() {
        return skill;
    }

    public Scheduling getScheduling() {
        return scheduling;
    }

    public Tracing getTracing() {
        return tracing;
    }

    public Knowledge getKnowledge() {
        return knowledge;
    }

    public Memory getMemory() {
        return memory;
    }

    /** Where the agent's file tools are rooted. */
    public static class Workspace {

        /**
         * Directory the agent's file tools are rooted at. Required — including when the application supplies
         * its own {@code VirtualFileSystem} bean, which makes the value unused. Relaxing that would mean
         * deciding whether the property is required from a bean that cannot see beans, and the alternative
         * (moving the check into the filesystem slice) costs the one thing this check exists for: with an empty
         * configuration, the first error a user sees names the property that unblocks them.
         */
        private String root;

        /**
         * Whether startup creates the workspace if missing and verifies it is writable. Leaving this on turns
         * a read-only mount into a startup failure instead of a tool error on the agent's first write.
         */
        private boolean ensureWritable = true;

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }

        public boolean isEnsureWritable() {
            return ensureWritable;
        }

        public void setEnsureWritable(boolean ensureWritable) {
            this.ensureWritable = ensureWritable;
        }
    }

    /**
     * One entry of the {@code aimon.agents} map. The map key is the agent ref.
     *
     * <p>
     * A map rather than a list, because the key is the ref a submit routes by — the name segment of
     * {@code agent:<ref>} — and that is the identity everything else joins on.
     */
    public static class AgentEntry {

        /**
         * Classpath bundle directory ({@code agents/<bundle>/agent.md}). Defaults to the map key.
         *
         * <p>
         * The two are separable because a deployment can want the same bundle twice under different refs — an
         * {@code ops} and an {@code ops-readonly} built from one definition, told apart by their customizers and
         * their {@link #getProperties() properties}. What routes is the ref; what is read from the classpath is
         * the bundle.
         */
        private String bundle;

        /**
         * Host-supplied facts about this agent, handed to every {@code AimonAgentCustomizer} through the
         * descriptor.
         *
         * <p>
         * The starter reads none of these. They exist so a customizer can branch on deployment data — a region,
         * a team, an escalation channel — without the application inventing a second configuration tree keyed by
         * agent ref and keeping it in step with this one by hand.
         */
        private Map<String, String> properties = new LinkedHashMap<>();

        public String getBundle() {
            return bundle;
        }

        public void setBundle(String bundle) {
            this.bundle = bundle;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties == null ? new LinkedHashMap<>() : properties;
        }
    }

    /** Settings that apply to agents without being per-agent. */
    public static class AgentDefaults {

        /** Agent ref used by submits that do not name one. */
        private String defaultAgent;

        public String getDefaultAgent() {
            return defaultAgent;
        }

        public void setDefaultAgent(String defaultAgent) {
            this.defaultAgent = defaultAgent;
        }
    }

    /**
     * How long tenant runtimes are kept and how many of them there may be.
     *
     * <p>
     * These bound the <em>tenant</em> runtimes only — {@code agent:<ref>:<tenant>}, created on first use because
     * the tenant set cannot be enumerated. The runtimes for the declared agents are built at startup, are the
     * basis of the stack's fail-fast check, and are never evicted: closing one would mean the next request
     * rebuilds it on a path that never ran that check.
     *
     * <p>
     * The defaults come from {@code AgentRuntimeSpec} rather than being repeated here, so a deployment that sets
     * nothing gets whatever the framework considers current, and this class does not become a second place where
     * "30 minutes" is written down.
     */
    public static class AgentRuntimeProperties {

        /** What happens to a tenant runtime nobody is using. */
        private AgentRuntimeEviction eviction = AgentRuntimeEviction.IDLE;

        /**
         * How long a tenant runtime with no holders is kept. Null uses {@link AgentRuntimeSpec#DEFAULT_IDLE_TTL}.
         *
         * <p>
         * The clock starts when the last holder releases and is reset by the next acquire, so a runtime serving
         * traffic is never a candidate however old it is.
         */
        private Duration idleTtl;

        /**
         * How often idle runtimes are looked for. Null sweeps at the idle TTL.
         *
         * <p>
         * Worth setting only to make eviction less eager than the TTL implies; a value below the TTL cannot
         * evict anything sooner, since a runtime is only a candidate once the TTL has passed.
         */
        private Duration sweepInterval;

        /**
         * Ceiling on tenant runtimes held at once. Null uses {@link AgentRuntimeSpec#DEFAULT_MAX_ENTRIES}.
         *
         * <p>
         * Exceeding it with nothing idle to reclaim fails the request rather than growing past the bound —
         * every held runtime owns a file system, a tool registry and any MCP connections the agent opened, and
         * quietly holding more of those than configured is how a process runs out of file handles.
         */
        private Integer maxEntries;

        public AgentRuntimeEviction getEviction() {
            return eviction;
        }

        public void setEviction(AgentRuntimeEviction eviction) {
            this.eviction = eviction;
        }

        public Duration getIdleTtl() {
            return idleTtl;
        }

        public void setIdleTtl(Duration idleTtl) {
            this.idleTtl = idleTtl;
        }

        public Duration getSweepInterval() {
            return sweepInterval;
        }

        public void setSweepInterval(Duration sweepInterval) {
            this.sweepInterval = sweepInterval;
        }

        public Integer getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(Integer maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    /** LLM vendor selection and credentials. */
    public static class Llm {

        /**
         * Vendor selector. A {@code String} rather than an enum so a third-party module can contribute its own
         * client under a value this starter has never heard of; the built-in values are documented as hints in
         * {@code additional-spring-configuration-metadata.json}, which is the price of not being an enum — the
         * other five selectors carry their values in the metadata's {@code type} for free.
         */
        private String provider = PROVIDER_ANTHROPIC;

        /** Vendor credential. Required for the built-in providers. */
        private String apiKey;

        /** Model id. Null leaves the vendor client's own default in place. */
        private String model;

        /** Override for the vendor API endpoint — a gateway, a proxy, a compatible third-party service. */
        private String baseUrl;

        /** Per-request timeout for the vendor client. */
        private Duration timeout = Duration.ofSeconds(60);

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * Ceiling applied to a single turn.
     *
     * <p>
     * Finite by default, unlike the framework's own {@code ExecutionBudget.unlimited()}. An unbounded ReAct
     * loop is a defensible default for an interactive CLI where a human watches it and can press Ctrl-C; it is
     * not one for a server process where the same loop is a request handler nobody is watching.
     */
    public static class Budget {

        /** Maximum ReAct iterations in one turn. */
        private Integer maxIterations = 20;

        /** Maximum tokens across one turn. */
        private Integer maxTokens = 100_000;

        /** Wall-clock ceiling for one turn. */
        private Duration maxWallClock = Duration.ofSeconds(120);

        public Integer getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(Integer maxIterations) {
            this.maxIterations = maxIterations;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Duration getMaxWallClock() {
            return maxWallClock;
        }

        public void setMaxWallClock(Duration maxWallClock) {
            this.maxWallClock = maxWallClock;
        }
    }

    /**
     * Session storage and cache tuning.
     *
     * <p>
     * Not named {@code Session}: that simple name is banned project-wide because it does not say which of the
     * two session lifetimes it means, and an ArchUnit rule enforces it.
     */
    public static class SessionProperties {

        /**
         * Backing store. Anything other than {@code in-memory} requires the application to define the matching
         * {@code SessionRecordStore} bean — this starter selects, it does not construct.
         */
        private SessionStoreType store = SessionStoreType.IN_MEMORY;

        /**
         * Deployment topology. The framework's own enum rather than a starter-local copy: this value decides
         * whether missing session SPIs may default to in-memory implementations, and a second enum that had to
         * be translated into the first would be a place for the two meanings to drift apart.
         */
        private DeploymentMode mode = DeploymentMode.SINGLE_NODE;

        /**
         * Identity this node claims session leases under. Required under {@code mode=distributed}; ignored
         * otherwise, where the router generates one per process.
         *
         * <p>
         * It has to be stable across restarts of the same node, not merely unique — a node that comes back under a
         * new identity cannot reclaim the leases it left behind, so its sessions wait out the lease TTL before any
         * node can serve them. A pod or host name satisfies both; a generated UUID satisfies only the first.
         */
        private String nodeId;

        /** How long shutdown waits for in-flight turns to finish before abandoning them. */
        private Duration shutdownDrainTimeout = SessionSpec.DEFAULT_DRAIN_TIMEOUT;

        private final Cache cache = new Cache();

        public SessionStoreType getStore() {
            return store;
        }

        public void setStore(SessionStoreType store) {
            this.store = store;
        }

        public DeploymentMode getMode() {
            return mode;
        }

        public void setMode(DeploymentMode mode) {
            this.mode = mode;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public Duration getShutdownDrainTimeout() {
            return shutdownDrainTimeout;
        }

        public void setShutdownDrainTimeout(Duration shutdownDrainTimeout) {
            this.shutdownDrainTimeout = shutdownDrainTimeout;
        }

        public Cache getCache() {
            return cache;
        }

        /** Bounds on the node-local live-session cache. */
        public static class Cache {

            /** Maximum live sessions held on this node before the least recently used one is evicted. */
            private Integer maxEntries = 1000;

            /** How long a live session may sit idle before it is evicted. Its stored history survives. */
            private Duration idleTtl = Duration.ofMinutes(30);

            public Integer getMaxEntries() {
                return maxEntries;
            }

            public void setMaxEntries(Integer maxEntries) {
                this.maxEntries = maxEntries;
            }

            public Duration getIdleTtl() {
                return idleTtl;
            }

            public void setIdleTtl(Duration idleTtl) {
                this.idleTtl = idleTtl;
            }
        }
    }

    /** Which built-in tools the agent is given. */
    public static class Tools {

        private final Bash bash = new Bash();

        public Bash getBash() {
            return bash;
        }

        /** The shell tool. */
        public static class Bash {

            /**
             * Off by default. The CLI hands the agent a shell because a human approves each command; a server
             * process has nobody to ask, so arbitrary command execution has to be opted into explicitly.
             */
            private boolean enabled;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }

    /** Skill invocation policy. */
    public static class Skill {

        private final Approval approval = new Approval();

        public Approval getApproval() {
            return approval;
        }

        /**
         * What happens when a skill runs that no stored approval covers.
         *
         * <p>
         * Two of the three settings here only mean something under a particular {@link #getMode() mode}, and
         * neither is validated against it. Setting {@code allow} under {@code mode=deny} is not an error, it is
         * a list nothing reads — the same way {@code pending-turn-ttl} under any mode but {@code suspend} is a
         * TTL on turns that are never suspended. Rejecting those combinations would turn every temporary
         * mode change into an edit of two properties.
         */
        public static class Approval {

            /** Default decision for an unapproved skill invocation. */
            private ApprovalMode mode = ApprovalMode.DENY;

            /**
             * Skill names auto-approved under {@link ApprovalMode#ALLOW_LIST}, matched literally.
             *
             * <p>
             * There is no wildcard: an entry of {@code *} allows a skill actually named {@code *}. An empty
             * list under that mode denies everything, which is deny-all reached by a longer route — usually
             * the sign of a list bound from a key that does not exist.
             */
            private List<String> allow = new ArrayList<>();

            /**
             * How long a turn suspended under {@link ApprovalMode#SUSPEND} waits before it is dropped.
             *
             * <p>
             * Unset leaves the framework default of thirty minutes, which was chosen for a terminal — the
             * person who was asked is the person sitting there. A server's approvals come from somewhere else,
             * so the figure that matters is how long that somewhere takes to answer: too short and an approval
             * on its way finds nothing waiting; too long and unanswered turns accumulate in the pending-turn
             * registry with nothing to release them.
             */
            private Duration pendingTurnTtl;

            public ApprovalMode getMode() {
                return mode;
            }

            public void setMode(ApprovalMode mode) {
                this.mode = mode;
            }

            public List<String> getAllow() {
                return allow;
            }

            public void setAllow(List<String> allow) {
                this.allow = (allow == null) ? new ArrayList<>() : allow;
            }

            public Duration getPendingTurnTtl() {
                return pendingTurnTtl;
            }

            public void setPendingTurnTtl(Duration pendingTurnTtl) {
                this.pendingTurnTtl = pendingTurnTtl;
            }
        }
    }

    /** Whether and how the agent's scheduled tasks run. */
    public static class Scheduling {

        /** Engine that runs scheduled tasks. Off by default — a stack with no cron task should pay nothing. */
        private SchedulingBackend backend = SchedulingBackend.NONE;

        /**
         * Whether the engine starts with the context. Null means the default, {@code true}.
         *
         * <p>
         * Nullable rather than a primitive so that setting it while no engine exists can be refused instead of
         * silently doing nothing — see {@code validateScheduling()}. The same is true of every field below.
         */
        private Boolean autoStartup;

        private final Quartz quartz = new Quartz();

        public SchedulingBackend getBackend() {
            return backend;
        }

        public void setBackend(SchedulingBackend backend) {
            this.backend = backend;
        }

        public Boolean getAutoStartup() {
            return autoStartup;
        }

        public void setAutoStartup(Boolean autoStartup) {
            this.autoStartup = autoStartup;
        }

        /**
         * Returns whether the engine starts with the context.
         *
         * @return {@code true} unless explicitly turned off
         */
        public boolean startsAutomatically() {
            return autoStartup == null || autoStartup;
        }

        public Quartz getQuartz() {
            return quartz;
        }
    }

    /**
     * Settings of the Quartz backend.
     *
     * <p>
     * All but the first apply to a scheduler AIMON builds. With {@link #usesApplicationScheduler()} left on, the
     * scheduler belongs to the application, which configures and shuts it down through {@code spring.quartz.*} —
     * setting any of them here is refused rather than ignored.
     */
    public static class Quartz {

        /**
         * Whether to borrow the application's {@code Scheduler} bean. Null means the default, {@code true}.
         *
         * <p>
         * Borrowing is the default because the alternative is two Quartz schedulers in one process, each with its
         * own thread pool, and because an application that has set up a JDBC job store has done that work once
         * already. AIMON neither starts nor stops a borrowed scheduler.
         */
        private Boolean useApplicationScheduler;

        /**
         * Name the scheduler registers under. Null derives a per-instance one.
         *
         * <p>
         * The nodes of a Quartz cluster are the schedulers sharing a name, so a clustered deployment must set this
         * to the same value on every node — and a single-node one must not set it at all, since a fixed name makes
         * two contexts in one JVM resolve to one scheduler.
         */
        private String instanceName;

        /** Worker threads. Null uses the adapter's default, one per available processor. */
        private Integer threadCount;

        /** Whether the scheduler's threads are daemons. Null uses the adapter's default, {@code true}. */
        private Boolean daemonThreads;

        /**
         * Whether shutdown waits for running jobs. Null means {@code false} here.
         *
         * <p>
         * This inverts the adapter's own default, and deliberately. The wait runs at the head of the shutdown
         * sequence, before anything that drains inbound requests, so inside a server one long job holds the whole
         * process open. A standalone process, where the scheduler is the reason it is running, is the case the
         * adapter defaults for.
         */
        private Boolean waitForJobsOnShutdown;

        public Boolean getUseApplicationScheduler() {
            return useApplicationScheduler;
        }

        public void setUseApplicationScheduler(Boolean useApplicationScheduler) {
            this.useApplicationScheduler = useApplicationScheduler;
        }

        /**
         * Returns whether the application's scheduler is borrowed.
         *
         * @return {@code true} unless explicitly turned off
         */
        public boolean usesApplicationScheduler() {
            return useApplicationScheduler == null || useApplicationScheduler;
        }

        public String getInstanceName() {
            return instanceName;
        }

        public void setInstanceName(String instanceName) {
            this.instanceName = instanceName;
        }

        public Integer getThreadCount() {
            return threadCount;
        }

        public void setThreadCount(Integer threadCount) {
            this.threadCount = threadCount;
        }

        public Boolean getDaemonThreads() {
            return daemonThreads;
        }

        public void setDaemonThreads(Boolean daemonThreads) {
            this.daemonThreads = daemonThreads;
        }

        public Boolean getWaitForJobsOnShutdown() {
            return waitForJobsOnShutdown;
        }

        public void setWaitForJobsOnShutdown(Boolean waitForJobsOnShutdown) {
            this.waitForJobsOnShutdown = waitForJobsOnShutdown;
        }

        /**
         * Returns whether shutdown waits for running jobs.
         *
         * @return {@code false} unless explicitly turned on
         */
        public boolean waitsForJobsOnShutdown() {
            return waitForJobsOnShutdown != null && waitForJobsOnShutdown;
        }
    }

    /** Span recording. */
    public static class Tracing {

        /** Whether spans are recorded at all. */
        private boolean enabled;

        /**
         * How much of a span's content is recorded. Separate from {@link #enabled} because turning tracing on
         * and deciding what goes into it are different decisions with different reviewers — the second one is
         * where message text and tool output leave the process.
         */
        private PayloadCapture payloadCapture = PayloadCapture.NONE;

        /**
         * Characters kept per captured payload. Boxed and null by default so that "left alone" stays
         * distinguishable from "set to the value that happens to be the default", which is what lets a cap set
         * under {@code payload-capture=none} be refused rather than silently ignored. Unset means
         * {@code TracePayloadPolicy.DEFAULT_MAX_CHARS}.
         */
        private Integer maxChars;

        /**
         * Spans the in-memory store keeps before evicting the oldest. Boxed for the same reason as
         * {@link #maxChars}; unset means {@code InMemoryTraceSpanStore.DEFAULT_MAX_SPANS}.
         */
        private Integer maxSpans;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public PayloadCapture getPayloadCapture() {
            return payloadCapture;
        }

        public void setPayloadCapture(PayloadCapture payloadCapture) {
            this.payloadCapture = payloadCapture;
        }

        public Integer getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(Integer maxChars) {
            this.maxChars = maxChars;
        }

        public Integer getMaxSpans() {
            return maxSpans;
        }

        public void setMaxSpans(Integer maxSpans) {
            this.maxSpans = maxSpans;
        }

        /**
         * Returns the payload policy this configuration selects.
         *
         * @return the policy, never null
         */
        public TracePayloadPolicy toPayloadPolicy() {
            if (payloadCapture == PayloadCapture.NONE) {
                return TracePayloadPolicy.summaryOnly();
            }
            return maxChars == null ? TracePayloadPolicy.full() : TracePayloadPolicy.full(maxChars);
        }

        /**
         * Returns the span-store bound this configuration selects.
         *
         * @return the ceiling, defaulted when unset
         */
        public int maxSpansOrDefault() {
            return maxSpans == null ? InMemoryTraceSpanStore.DEFAULT_MAX_SPANS : maxSpans;
        }
    }

    /**
     * Knowledge search.
     *
     * <p>
     * A selector rather than a boolean, for the reason every other backend here is: {@code enabled=true} would
     * have to mean "build one" and "take the application's" at once, and the two have opposite failure modes. The
     * value names which, and a bean that contradicts it is refused by name rather than quietly ignored.
     */
    public static class Knowledge {

        /** Which store backs search. Defaults to none — a store nobody asked for indexes nothing. */
        private KnowledgeBackend backend = KnowledgeBackend.NONE;

        /** Boxed so a value set under a backend that cannot use it is refusable. */
        private Integer chunkSize;

        /** Boxed for the same reason as {@link #chunkSize}. */
        private Integer chunkOverlap;

        public KnowledgeBackend getBackend() {
            return backend;
        }

        public void setBackend(KnowledgeBackend backend) {
            this.backend = backend;
        }

        public Integer getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(Integer chunkSize) {
            this.chunkSize = chunkSize;
        }

        public Integer getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(Integer chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        /**
         * Returns the chunk size the built-in store is built with.
         *
         * @return the configured size, or the chunker's own default
         */
        public int chunkSizeOrDefault() {
            return chunkSize == null ? SimpleDocumentChunker.DEFAULT_CHUNK_SIZE : chunkSize;
        }

        /**
         * Returns the chunk overlap the built-in store is built with.
         *
         * @return the configured overlap, or the chunker's own default
         */
        public int chunkOverlapOrDefault() {
            return chunkOverlap == null ? SimpleDocumentChunker.DEFAULT_OVERLAP : chunkOverlap;
        }
    }

    /**
     * What the agents remember between sessions, and whose memory it is.
     *
     * <p>
     * Everything except {@link #backend} is boxed or nullable, and that carries more weight here than in the
     * other groups. Memory has no output an operator can check at startup — a mis-wired one produces an empty
     * memory part, which is exactly what a correctly wired one produces for a peer nobody has observed yet. So
     * the only place a mistake can still be caught is while the properties are being read, and a setting that
     * cannot be told apart from its own default cannot be caught there at all.
     */
    public static class Memory {

        /** Which stores are wired. Defaults to none — memory that is on and empty reads as memory that is broken. */
        private MemoryBackend backend = MemoryBackend.NONE;

        /** Required once a backend is named; see {@code validateMemory()} for why there is no default. */
        private String workspaceId;

        /** Boxed so that leaving it alone is distinguishable from choosing {@code fixed}. */
        private MemoryPeerMode peerMode;

        /** Required under {@code peer-mode=fixed}, refused under {@code caller}. */
        private String peerId;

        /** Boxed for the same reason as {@link #peerMode}. */
        private MemoryInjectionMode injectionMode;

        /** Boxed; {@code 0} is a legal value meaning no cap, so it cannot double as "unset". */
        private Integer maxTokens;

        /** Boxed; the resolved default is {@link MemoryRedaction#DEFAULT}, which is deliberate — see that enum. */
        private MemoryRedaction redaction;

        public MemoryBackend getBackend() {
            return backend;
        }

        public void setBackend(MemoryBackend backend) {
            this.backend = backend;
        }

        public String getWorkspaceId() {
            return workspaceId;
        }

        public void setWorkspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
        }

        public MemoryPeerMode getPeerMode() {
            return peerMode;
        }

        public void setPeerMode(MemoryPeerMode peerMode) {
            this.peerMode = peerMode;
        }

        public String getPeerId() {
            return peerId;
        }

        public void setPeerId(String peerId) {
            this.peerId = peerId;
        }

        public MemoryInjectionMode getInjectionMode() {
            return injectionMode;
        }

        public void setInjectionMode(MemoryInjectionMode injectionMode) {
            this.injectionMode = injectionMode;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public MemoryRedaction getRedaction() {
            return redaction;
        }

        public void setRedaction(MemoryRedaction redaction) {
            this.redaction = redaction;
        }

        /**
         * Returns whose memory is read.
         *
         * @return the configured mode, or {@link MemoryPeerMode#FIXED} — the mode that wires the whole feature
         */
        public MemoryPeerMode peerModeOrDefault() {
            return peerMode == null ? MemoryPeerMode.FIXED : peerMode;
        }

        /**
         * Returns the redaction applied to observations.
         *
         * @return the configured policy selector, or {@link MemoryRedaction#DEFAULT}
         */
        public MemoryRedaction redactionOrDefault() {
            return redaction == null ? MemoryRedaction.DEFAULT : redaction;
        }

        /**
         * Returns the cap on the injected memory part.
         *
         * @return the configured cap, or {@code 0} for no cap
         */
        public int maxTokensOrDefault() {
            return maxTokens == null ? 0 : maxTokens;
        }
    }
}
