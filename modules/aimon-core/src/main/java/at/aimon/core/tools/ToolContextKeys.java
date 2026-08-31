package at.aimon.core.tools;

import java.util.Map;
import java.util.function.Consumer;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.agent.tool.search.ToolSearchRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBase;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBaseAdmin;
import at.aimon.core.knowledge.wiki.WikiScope;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.skill.execution.SkillToolDispatcher;
import at.aimon.core.skill.fork.SkillForkExecutor;

/**
 * Constants for tool context keys used in agent extension tools.
 *
 * <p>
 * This class provides standardized key names for accessing contextual information in tool execution. These keys are
 * used to store and retrieve data from {@link ToolContext} during tool execution.
 *
 * <p>
 * Use typed {@link ToolContextKey} constants for compile-time type safety:
 *
 * <pre>
 * {
 *     &#64;code
 *     Optional<Environment> env = context.get(ToolContextKeys.ENVIRONMENT_KEY);
 *     Optional<Principal> principal = context.get(ToolContextKeys.PRINCIPAL);
 * }
 * </pre>
 *
 * @see ToolContext
 * @see ToolContextKey
 * @see Environment
 */
public final class ToolContextKeys {

    // ── Typed keys (preferred) ──────────────────────────────────────────────────

    /**
     * Typed key for {@link Environment} information.
     *
     * <p>
     * The environment provides access to the working directory and environment variables for the current execution
     * context.
     */
    public static final ToolContextKey<Environment> ENVIRONMENT_KEY = ToolContextKey.of("environment",
            Environment.class);

    /**
     * Typed key for {@link Principal} identity.
     */
    public static final ToolContextKey<Principal> PRINCIPAL = ToolContextKey.of("principal", Principal.class);

    /**
     * Typed key for the current agent's {@link AgentRuntimeId}.
     *
     * <p>
     * The agent runtime ID uniquely identifies the agent-scoped runtime. Because the id is derived
     * deterministically from the {@code (Agent, discriminator)} pair (e.g., {@code agent:<name>}), it remains stable
     * across sessions and across cron re-fires &mdash; a {@code ScheduledTask} that captures this value via the
     * tool context can resolve the same context from {@code AgentRuntimeRegistry} long after the originating
     * session has ended.
     */
    public static final ToolContextKey<AgentRuntimeId> AGENT_RUNTIME_ID = ToolContextKey.of("agentRuntimeId",
            AgentRuntimeId.class);

    /**
     * Typed key for the {@link SessionId} of the session the current turn belongs to.
     *
     * <p>
     * Deliberately narrower than {@link #AGENT_RUNTIME_ID}: a session ends, an agent runtime does not. Tools that
     * remember a per-user decision (skill approvals, for one) should key it on this rather than on the runtime id, so
     * the decision does not silently leak into every later session of the same agent.
     *
     * <p>
     * Always the reader's <em>own</em> session, and present only when there is one. A subagent fork used to mint a
     * {@link SessionId} for its transcript and publish it here, which left the key populated with an id no user had
     * ever seen; a fork now publishes an {@link #EXECUTION_ID} instead and leaves this one empty. The session that
     * spawned the fork travels separately under {@link #INVOKING_SESSION_ID}. Absent for every run with no session of
     * its own &mdash; forks, scheduled tasks, rewake replays &mdash; so tools must treat it as optional.
     */
    public static final ToolContextKey<SessionId> SESSION_ID = ToolContextKey.of("conversationId", SessionId.class);

    /**
     * Typed key for the {@link SessionId} of the session whose turn spawned the current run.
     *
     * <p>
     * Set only on a run some session started on the user's behalf: a subagent fork, a skill fork, a foreground
     * workflow. Absent when the reader <em>is</em> the session (the main agent, which is the invoker rather
     * than an invokee) and when nobody asked at all (scheduled tasks, background workflows that outlive the turn).
     *
     * <p>
     * Read it to answer "which session is this run acting for?" &mdash; the question a user-granted decision is
     * scoped by. It is deliberately <em>not</em> a state-partitioning key: per-run state keys on the run's own
     * identifier &mdash; {@link #SESSION_ID} when the run is a session's turn, {@link #EXECUTION_ID} when it is not
     * &mdash; and the invoking id is shared with the invoker by construction, so partitioning on it would merge a
     * fork's state into its parent's. Use {@link InvokingSessionAccess} rather than reading the key directly;
     * spawning code in particular must propagate the inherited id, not its own.
     */
    public static final ToolContextKey<SessionId> INVOKING_SESSION_ID = ToolContextKey.of("invokingConversationId",
            SessionId.class);

    /**
     * Typed key for the {@link ExecutionId} of the current run, set on runs that have no session of their own.
     *
     * <p>
     * The third id in this family, and the one that keeps the other two honest. {@link #SESSION_ID} and
     * {@link #INVOKING_SESSION_ID} both carry a {@link SessionId}, so nothing but a naming convention stops a run
     * without a session from minting one and publishing it as though a user were on the other end. This key is where
     * such a run puts its identifier instead: a subagent or skill fork, a rewake hook replay, a scheduled routine.
     *
     * <p>
     * Read it for correlation &mdash; logging, tracing, keeping two concurrent forks' per-run state in separate
     * buckets. Do <em>not</em> read it to decide what a run is allowed to do. Authorization scoped to a session must
     * come from {@link #INVOKING_SESSION_ID} (via {@link InvokingSessionAccess#invokerOf}), which names the session
     * that asked for the work; an execution id names nobody and so grants nothing.
     *
     * <p>
     * Never forwarded. A nested run publishes its own value, unlike {@link #INVOKING_SESSION_ID}, which is passed
     * down unchanged so the user's session stays identifiable however deep the nesting goes.
     */
    public static final ToolContextKey<ExecutionId> EXECUTION_ID = ToolContextKey.of("executionId", ExecutionId.class);

    /**
     * Typed key for the request identifier.
     *
     * <p>
     * The request ID is a unique identifier for the current execution request, useful for logging and tracing.
     */
    public static final ToolContextKey<String> REQUEST_ID_KEY = ToolContextKey.of("requestId", String.class);

    /**
     * Typed key for the timeout duration in milliseconds.
     *
     * <p>
     * The timeout value specifies the maximum duration for operations before they should be cancelled or terminated.
     */
    public static final ToolContextKey<Long> TIMEOUT_MS_KEY = ToolContextKey.of("timeoutMs", Long.class);

    /**
     * Typed key for execution attributes.
     *
     * <p>
     * Execution attributes are arbitrary key-value data passed by the caller at agent execution request time. They are
     * available in both {@link ToolContext} and hook contexts throughout the execution lifecycle, including subagent
     * propagation.
     */
    @SuppressWarnings("unchecked")
    public static final ToolContextKey<Map<String, Object>> EXECUTION_ATTRIBUTES_KEY = ToolContextKey
            .of("executionAttributes", (Class<Map<String, Object>>) (Class<?>) Map.class);

    /**
     * Typed key for the current tool use ID.
     *
     * <p>
     * The tool use ID uniquely identifies a single tool invocation within a ReAct loop iteration. It is injected into
     * {@link ToolContext} by the executor before each tool call, allowing tools to correlate their output (e.g., file
     * artifacts) with the specific invocation that produced them.
     */
    public static final ToolContextKey<String> CURRENT_TOOL_USE_ID_KEY = ToolContextKey.of("currentToolUseId",
            String.class);

    /**
     * Typed key for the {@link ArtifactCollector}.
     *
     * <p>
     * The artifact collector gathers file artifacts generated during a single agent execution request. Created
     * per-request so that artifacts from different requests do not mix.
     */
    public static final ToolContextKey<ArtifactCollector> ARTIFACT_COLLECTOR = ToolContextKey.of("artifactCollector",
            ArtifactCollector.class);

    /**
     * Typed key for the per-session {@link ToolSearchRegistry}.
     *
     * <p>
     * Injected into {@link ToolContext} when the tool registry is a
     * {@link at.aimon.core.agent.tool.search.ToolSearchCatalog},
     * enabling the {@code ToolSearchTool} to search and activate deferred tools within the current session.
     */
    public static final ToolContextKey<ToolSearchRegistry> TOOL_SEARCH_REGISTRY = ToolContextKey
            .of("toolSearchRegistry", ToolSearchRegistry.class);

    /**
     * Typed key for the {@link KnowledgeStore}.
     *
     * <p>
     * Injected into {@link ToolContext} when the agent has a configured knowledge directory, enabling the
     * {@code KnowledgeSearchTool} to search the agent's knowledge base.
     */
    public static final ToolContextKey<KnowledgeStore> KNOWLEDGE_STORE = ToolContextKey.of("knowledgeStore",
            KnowledgeStore.class);

    /**
     * Typed key for the {@link KnowledgeScope}.
     *
     * <p>
     * Identifies the current agent and runtime scope for multi-tenant knowledge isolation. Injected into
     * {@link ToolContext} alongside {@link #KNOWLEDGE_STORE} to enable scope-aware search.
     */
    public static final ToolContextKey<KnowledgeScope> KNOWLEDGE_SCOPE = ToolContextKey.of("knowledgeScope",
            KnowledgeScope.class);

    /**
     * Typed key for the {@link VirtualFileSystem}.
     *
     * <p>
     * Provides access to the agent's virtual file system, used by tools that need to read from or write to the VFS
     * (e.g., wiki ingestion).
     */
    public static final ToolContextKey<VirtualFileSystem> VIRTUAL_FILE_SYSTEM = ToolContextKey.of("virtualFileSystem",
            VirtualFileSystem.class);

    /**
     * Typed key for the {@link WikiKnowledgeBase}.
     *
     * <p>
     * Injected into {@link ToolContext} when the agent has a configured wiki knowledge base, enabling wiki tools to
     * search and ingest wiki pages.
     */
    public static final ToolContextKey<WikiKnowledgeBase> WIKI_KNOWLEDGE_BASE = ToolContextKey.of("wikiKnowledgeBase",
            WikiKnowledgeBase.class);

    /**
     * Typed key for the {@link WikiScope}.
     *
     * <p>
     * Identifies the current agent and wiki name for multi-tenant wiki isolation. Injected into {@link ToolContext}
     * alongside {@link #WIKI_KNOWLEDGE_BASE} to enable scope-aware wiki operations.
     */
    public static final ToolContextKey<WikiScope> WIKI_SCOPE = ToolContextKey.of("wikiScope", WikiScope.class);

    /**
     * Typed key for the {@link WikiKnowledgeBaseAdmin}.
     *
     * <p>
     * Provides administrative operations (lint, audit log) on the wiki knowledge base. Injected into
     * {@link ToolContext} when administrative wiki tools are enabled.
     */
    public static final ToolContextKey<WikiKnowledgeBaseAdmin> WIKI_KNOWLEDGE_BASE_ADMIN = ToolContextKey
            .of("wikiKnowledgeBaseAdmin", WikiKnowledgeBaseAdmin.class);

    /**
     * Typed key for the active {@link LlmCallMetadata}.
     *
     * <p>
     * Injected into {@link ToolContext} by the agent executor with the effective metadata of the current execution
     * (caller-supplied metadata merged with auto-derived component/feature/traceId). Sub-execution entry points (e.g.,
     * the TaskTool launching a subagent) read this value to propagate attribution downstream so usage from nested
     * agents shares the parent's traceId and principal.
     */
    public static final ToolContextKey<LlmCallMetadata> LLM_CALL_METADATA_KEY = ToolContextKey.of("llmCallMetadata",
            LlmCallMetadata.class);

    /**
     * Typed key for a per-execution {@link SkillForkExecutor}.
     *
     * <p>
     * Set by the agent executor on the user-slash command path so {@code LlmSkillExecutor} can route fork-mode skill
     * invocations through the same {@link at.aimon.core.skill.fork.SubagentBackedSkillForkExecutor} that the LLM
     * tool-call path ({@code SkillTool}) uses. When absent, {@code LlmSkillExecutor} falls back to the executor wired
     * at construction time (typically {@link at.aimon.core.skill.fork.NoOpSkillForkExecutor}).
     */
    public static final ToolContextKey<SkillForkExecutor> SKILL_FORK_EXECUTOR_KEY = ToolContextKey
            .of("skillForkExecutor", SkillForkExecutor.class);

    /**
     * Typed key for a per-execution {@link SkillToolDispatcher}.
     *
     * <p>
     * Set by the agent executor on the user-slash command path so {@code LlmSkillExecutor} runs the tool calls of an
     * inline skill through the same {@code SingleToolInvoker} pipeline the ReAct loop uses — PermissionRequest hooks,
     * the side-effect approval gate, PreTool / PostTool — instead of calling the execution manager directly. Without
     * it, typing {@code /my-skill} was a way to reach the agent's own tools while skipping every gate in front of them.
     *
     * <p>
     * Absent for embedders driving {@code LlmSkillExecutor} without an agent runtime; the executor then falls back to
     * the plain execution manager, which is what every caller did before this key existed.
     */
    public static final ToolContextKey<SkillToolDispatcher> SKILL_TOOL_DISPATCHER_KEY = ToolContextKey
            .of("skillToolDispatcher", SkillToolDispatcher.class);

    /**
     * Typed key for the session-scoped {@link MessageQueueManager}.
     *
     * <p>
     * Injected into {@link ToolContext} by the agent executor when a message queue is configured. The {@code Task} tool
     * forwards it onto the subagent execution environment so a <b>background</b> subagent completion can push a
     * guaranteed {@code <task-notification>} back to the launching agent, delivered no later than the parent's
     * next ReAct iteration.
     */
    public static final ToolContextKey<MessageQueueManager> MESSAGE_QUEUE_MANAGER = ToolContextKey
            .of("messageQueueManager", MessageQueueManager.class);

    /**
     * Typed key for the parent executor's {@code agent.stream} event sink.
     *
     * <p>
     * Injected into {@link ToolContext} by the agent executor as a bound reference to its {@code EventEmitter}. The
     * {@code Task} tool forwards it onto the subagent execution environment so a <b>background</b> subagent completion
     * can emit a {@code SubagentTaskCompleted} event for live display / observability. This is best-effort:
     * events raised while the parent has no attached listener are dropped (the queued notification remains the
     * guaranteed path).
     */
    @SuppressWarnings("unchecked")
    public static final ToolContextKey<Consumer<AgentExecutionEvent>> AGENT_EVENT_SINK = ToolContextKey
            .of("agentEventSink", (Class<Consumer<AgentExecutionEvent>>) (Class<?>) Consumer.class);

    private ToolContextKeys() {
        throw new AssertionError("This class should not be instantiated");
    }
}
