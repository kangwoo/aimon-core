package at.aimon.core.memory.deriver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.tagging.BoundMetadataLlmClient;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.deriver.tool.DeriverMemorySearchTool;
import at.aimon.core.memory.deriver.tool.DeriverMessageLinkTool;
import at.aimon.core.memory.deriver.tool.DeriverObservationCreateTool;
import at.aimon.core.memory.redaction.RedactionPolicy;

/**
 * ReAct-style {@link Deriver}. The model is given the
 * {@link DeriverMemorySearchTool}, {@link DeriverObservationCreateTool} and
 * {@link DeriverMessageLinkTool} (design doc §6.1.4) and decides for itself
 * when to look up existing observations, persist new ones, and link source
 * messages — rather than emitting a single JSON array as {@link LlmDeriver}
 * does.
 *
 * <p>
 * The loop terminates when the model returns no further tool calls, the
 * iteration cap is reached, or the cumulative token usage exceeds the budget
 * declared in {@link DerivationContext#getTokenBudget()}. {@link LlmClient}
 * exceptions are swallowed and the partial result is returned, mirroring
 * {@link LlmDeriver} so the queue manager keeps treating throws as failed
 * tasks rather than as silent data loss.
 *
 * <p>
 * Observations created during the loop are surfaced in
 * {@link DerivationResult#getCreated()} by reloading every id reported by
 * {@link DeriverObservationCreateTool} from the {@link ObservationStore}.
 * Reconciliation is intentionally <em>not</em> performed here: the deriver
 * tools call {@link ObservationStore#save} directly, and any merge/replace
 * logic the model decides on goes through the same tool calls (it can search,
 * see a duplicate, and choose not to create).
 *
 * <h2>Why this loop does not go through {@code SingleToolInvoker}</h2>
 *
 * <p>
 * Every other ReAct loop in the tree — the agent's turn, a subagent fork, a
 * user-invoked skill — dispatches through
 * {@code at.aimon.core.toolinvocation.SingleToolInvoker}, so that permission
 * hooks, the side-effect approval gate and {@code PreTool} / {@code PostTool}
 * see the call. This one calls {@link Tool#execute} directly, and that is
 * deliberate rather than an oversight left over from the invoker's
 * introduction.
 *
 * <p>
 * Two facts make it safe, and both must stay true for it to remain so.
 * <em>First, the tool set is closed.</em> It is the three tools this
 * constructor builds, held in a private {@code Map.copyOf} — the agent's
 * {@link at.aimon.core.agent.tool.ToolRegistry} is never consulted, so the
 * model cannot name its way to {@code Bash} or {@code Write} and an unknown
 * name is rejected outright. That is what the invoker's allow-list and ceiling
 * exist to guarantee elsewhere; here it is guaranteed by construction.
 * <em>Second, the reachable side effect is one store.</em> These tools write
 * observations to the {@link ObservationStore} they were handed and nothing
 * else — no filesystem, no sandbox, no network.
 *
 * <p>
 * And one fact makes it necessary: derivation runs <b>unattended</b>, off the
 * memory queue, with no user to answer a prompt. An {@code ASK} in that
 * position resolves through {@code AskPromptHandler}, whose fail-safe default
 * is {@link at.aimon.core.hook.execution.AskPromptHandler#denyAll()}. Wiring
 * the approval gate in front of {@code deriver.observation.create} — which
 * mutates and therefore asks — would not make derivation safer; it would make
 * it always fail, silently, one denied tool call at a time.
 *
 * <p>
 * So the rule for changing this class: <b>a new deriver tool may write to the
 * observation store and nothing else.</b> The moment one needs to touch the
 * filesystem, run a command, or reach the network, the closed-set argument is
 * gone, and this loop must move behind the invoker with a real
 * {@code AskPromptHandler} supplied by whoever schedules the derivation —
 * not merely be given a {@code SideEffectLevel} and left here.
 */
public final class ReActLlmDeriver implements Deriver {

    private static final Logger log = LoggerFactory.getLogger(ReActLlmDeriver.class);

    private static final String SYSTEM_PROMPT = """
            You analyze a conversation and extract atomic observations about the speakers using tools.

            Available tools:
            - deriver.memory.search(query, top_k?) — look up the subject's existing observations
              before creating a new one. Use this to avoid duplicates and to spot conflicts.
            - deriver.observation.create(content, type?, source_message_ids?) — persist one
              observation. Returns its observation_id.
            - deriver.message.link(observation_id, message_ids) — attach source message ids to an
              observation after the fact (only needed when the ids were not known at create time).

            Guidelines:
            - One fact per observation_create call. Split compound statements.
            - "type" is "EXPLICIT" if the speaker stated it directly, "DEDUCTIVE" if inferred.
              Classify the type only — do NOT score confidence; the system computes that from the type.
            - When you have nothing more to record, respond with a short text summary and stop
              calling tools — that ends the loop.
            """;

    static final int DEFAULT_MAX_ITERATIONS = 6;
    static final int MIN_TOKEN_BUDGET_PER_CALL = 256;

    private static final LlmCallMetadata SELF_METADATA = LlmCallMetadata.builder().component("memory")
            .feature("derivation-react").build();

    private final LlmClient llmClient;
    private final ObservationStore observationStore;
    private final String llmModelName;
    private final int maxIterations;
    private final List<Tool> tools;
    private final List<ToolDefinition> toolDefinitions;
    private final Map<String, Tool> toolsByName;

    public ReActLlmDeriver(LlmClient llmClient, ObservationStore observationStore, String llmModelName) {
        this(llmClient, observationStore, llmModelName, null, DEFAULT_MAX_ITERATIONS);
    }

    public ReActLlmDeriver(LlmClient llmClient, ObservationStore observationStore, String llmModelName,
            RedactionPolicy redactionPolicy) {
        this(llmClient, observationStore, llmModelName, redactionPolicy, DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Creates a deriver with custom iteration cap.
     *
     * @param redactionPolicy
     *            optional redaction policy applied to tool inputs (content/query); {@code null} disables redaction
     * @param maxIterations
     *            ceiling on ReAct iterations; must be {@code >= 1}
     */
    public ReActLlmDeriver(LlmClient llmClient, ObservationStore observationStore, String llmModelName,
            RedactionPolicy redactionPolicy, int maxIterations) {
        this.llmClient = new BoundMetadataLlmClient(Objects.requireNonNull(llmClient, "llmClient cannot be null"),
                SELF_METADATA);
        this.observationStore = Objects.requireNonNull(observationStore, "observationStore cannot be null");
        this.llmModelName = Objects.requireNonNull(llmModelName, "llmModelName cannot be null");
        if (llmModelName.isBlank()) {
            throw new IllegalArgumentException("llmModelName cannot be blank");
        }
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1, got " + maxIterations);
        }
        this.maxIterations = maxIterations;
        this.tools = List.of(new DeriverMemorySearchTool(observationStore, redactionPolicy),
                new DeriverObservationCreateTool(observationStore, redactionPolicy),
                new DeriverMessageLinkTool(observationStore));
        this.toolDefinitions = this.tools.stream().map(Tool::getDefinition).toList();
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : this.tools) {
            byName.put(tool.getDefinition().getName(), tool);
        }
        this.toolsByName = Map.copyOf(byName);
    }

    @Override
    public DerivationResult derive(DerivationContext ctx) {
        Objects.requireNonNull(ctx, "ctx cannot be null");

        log.info("ReActLlmDeriver starting: observer={}, messages={}, tokenBudget={}, maxIterations={}",
                ctx.getObserver().key(), ctx.getMessages().size(), ctx.getTokenBudget(), maxIterations);
        List<Message> conversation = new ArrayList<>(ctx.getMessages());
        ToolContext toolContext = buildToolContext(ctx);
        List<ObservationId> createdIds = new ArrayList<>();
        long totalTokens = 0L;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            int remainingBudget = (int) Math.max(MIN_TOKEN_BUDGET_PER_CALL,
                    ctx.getTokenBudget() - Math.min(ctx.getTokenBudget(), totalTokens));
            LlmModel modelConfig = LlmModel.builder().name(llmModelName).maxTokens(remainingBudget).build();

            LlmResponse response;
            try {
                response = llmClient.sendMessage(SYSTEM_PROMPT, conversation, toolDefinitions, modelConfig);
            } catch (RuntimeException e) {
                log.error("ReActLlmDeriver LLM call failed at iteration {} for {}: {}", iteration,
                        ctx.getObserver().key(), e.getMessage(), e);
                break;
            }

            totalTokens += response.getTokenUsage().getTotalTokens();
            log.debug("ReActLlmDeriver iteration {}: observer={}, toolUses={}, cumulativeTokens={}", iteration,
                    ctx.getObserver().key(), response.getToolUses().size(), totalTokens);

            if (!response.hasToolUses()) {
                log.debug("ReActLlmDeriver finished without tool calls at iteration {} for {}", iteration,
                        ctx.getObserver().key());
                break;
            }

            conversation.add(Message.assistant(response.getTextContent(), response.getToolUses()));
            List<ToolUseResult> results = new ArrayList<>(response.getToolUses().size());
            for (ToolUse toolUse : response.getToolUses()) {
                ToolUseResult result = invokeTool(toolUse, toolContext, createdIds);
                results.add(result);
            }
            conversation.add(Message.toolUseResults(results));

            if (totalTokens >= ctx.getTokenBudget()) {
                log.debug("ReActLlmDeriver token budget {} exhausted after iteration {} for {}", ctx.getTokenBudget(),
                        iteration, ctx.getObserver().key());
                break;
            }
        }

        List<Observation> created = loadCreated(createdIds);
        log.info("ReActLlmDeriver finished: observer={}, created={}, tokens={}, ids={}", ctx.getObserver().key(),
                created.size(), totalTokens, createdIds.size());
        return DerivationResult.of(created, List.of(), totalTokens);
    }

    private ToolContext buildToolContext(DerivationContext ctx) {
        return ToolContext.builder().put(DeriverObservationCreateTool.WORKSPACE_KEY, ctx.getWorkspace())
                .put(DeriverObservationCreateTool.OBSERVER_KEY, ctx.getObserver())
                .put(DeriverObservationCreateTool.SUBJECT_KEY, ctx.getObserver()).build();
    }

    /**
     * Executes one tool call directly, bypassing {@code SingleToolInvoker} — see the class javadoc for why that is
     * sound here and what would make it stop being sound. Never throws: a missing tool, a throwing tool and an error
     * result all come back as a {@link ToolUseResult}, so one bad call costs an observation rather than the derivation.
     */
    private ToolUseResult invokeTool(ToolUse toolUse, ToolContext toolContext, List<ObservationId> createdIds) {
        Tool tool = toolsByName.get(toolUse.getName());
        if (tool == null) {
            log.warn("ReActLlmDeriver model called unknown tool '{}'; rejecting", toolUse.getName());
            return ToolUseResult.error(toolUse.getId(), "Unknown tool: " + toolUse.getName());
        }
        ToolResult result;
        try {
            result = tool.execute(ToolInput.of(toolUse.getInput()), toolContext);
        } catch (RuntimeException e) {
            log.error("ReActLlmDeriver tool '{}' threw {}; converting to ToolUseResult.error", toolUse.getName(),
                    e.getMessage(), e);
            return ToolUseResult.error(toolUse.getId(), "Tool threw: " + e.getMessage());
        }
        if (result.isError()) {
            return ToolUseResult.error(toolUse.getId(), result.getContent());
        }
        if (DeriverObservationCreateTool.TOOL_NAME.equals(toolUse.getName())) {
            ObservationId id = parseCreatedId(result.getContent(),
                    (Workspace) toolContext.get(DeriverObservationCreateTool.WORKSPACE_KEY).orElse(null));
            if (id != null) {
                createdIds.add(id);
            }
        }
        return ToolUseResult.success(toolUse.getId(), result.getContent());
    }

    private static ObservationId parseCreatedId(String renderedContent, Workspace workspace) {
        if (renderedContent == null || workspace == null) {
            return null;
        }
        for (String line : renderedContent.split("\\R")) {
            String prefix = "observation_id: ";
            if (line.startsWith(prefix)) {
                String localId = line.substring(prefix.length()).trim();
                if (!localId.isEmpty()) {
                    try {
                        return ObservationId.of(workspace, localId);
                    } catch (IllegalArgumentException e) {
                        log.warn("ReActLlmDeriver could not parse observation_id '{}': {}", localId, e.getMessage());
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private List<Observation> loadCreated(List<ObservationId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Observation> result = new ArrayList<>(ids.size());
        for (ObservationId id : ids) {
            observationStore.findById(id).ifPresent(result::add);
        }
        return List.copyOf(result);
    }
}
