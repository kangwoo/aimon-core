package at.aimon.core.agent.compact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookFeedback;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * Default {@link CompactionEngine} performing the L3 full-conversation compaction.
 *
 * <p>
 * Sequence (per design §4.1):
 *
 * <ol>
 * <li>Reentrancy guard via a {@link ThreadLocal} flag — prevents nested compaction triggered from inside a hook or
 * subagent. A reentrant call returns {@link CompactionResult#failure} carrying a {@link CompactionReentrancyException}.
 * <li>Snapshot the existing messages and system prompt; capture pre-compaction token count.
 * <li>Invoke {@code PreCompactHook}s. AUTO trigger blocks abort with {@link CompactionBlockedByHookException}; MANUAL
 * trigger blocks are downgraded to a warning.
 * <li>Strip non-text content blocks (images, documents) via {@link MessageStripper}.
 * <li>Send a tool-less LLM request with feature {@code COMPACTION}; merge the <em>advisory</em> feedback of the
 * PreCompact hooks into the summary prompt as custom instructions (a blocked result's feedback is its deny reason, not
 * an instruction, and is reported by the block paths above instead).
 * <li>Atomically replace the transcript buffer with the design-§4.5 marker pair: a boundary {@code USER} message
 * carrying JSON metadata wrapped in {@link CompactBoundary#boundaryOpen}/{@link CompactBoundary#boundaryClose},
 * followed
 * by a summary {@code USER} message wrapped in {@link CompactBoundary#summaryOpen}/{@link CompactBoundary#summaryClose}
 * with a "Continue from here." trailer.
 * <li>Invoke {@code PostCompactHook}s with the new memory and metadata.
 * </ol>
 *
 * <p>
 * Thread-safe; multiple compactions of independent sessions may run concurrently. Concurrent compaction of the
 * same session must be serialized externally (typically by {@link CompactionGuard}).
 */
public class DefaultCompactionEngine implements CompactionEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultCompactionEngine.class);

    /** Reentrancy guard to prevent nested compaction (e.g. a hook triggering another compaction). */
    private static final ThreadLocal<Boolean> COMPACTION_IN_PROGRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Tool name to scan for when collecting recently-read file paths. Hard-coded as a string to avoid the layering
     * violation that would occur if {@code at.aimon.core.agent.compact} imported {@code at.aimon.core.tools.file}.
     */
    private static final String READ_TOOL_NAME_FOR_FILE_TRACKING = "Read";

    /**
     * Tool name to scan for when collecting Skill invocations. Hard-coded for the same layering reason as
     * {@link #READ_TOOL_NAME_FOR_FILE_TRACKING} — {@code at.aimon.core.agent.compact} must not import
     * {@code at.aimon.core.tools.skill}.
     */
    private static final String SKILL_TOOL_NAME_FOR_INVOCATION_TRACKING = "Skill";

    /** Input keys on the {@code Skill} tool — duplicated as constants to avoid the layering import. */
    private static final String SKILL_TOOL_INPUT_KEY_NAME = "skill";
    private static final String SKILL_TOOL_INPUT_KEY_ARGS = "args";

    private final LlmClient llmClient;
    private final TokenEstimator tokenEstimator;
    private final HookExecutionManager hookExecutionManager;
    private final SummaryPromptTemplate summaryPromptTemplate;
    private final MessageStripper messageStripper;

    /**
     * Creates an engine wired with the framework default {@link SummaryPromptTemplate} and {@link MessageStripper}.
     * Tests or callers needing custom prompt structure / redaction must use {@link #withCollaborators}.
     */
    public DefaultCompactionEngine(LlmClient llmClient, TokenEstimator tokenEstimator,
            HookExecutionManager hookExecutionManager) {
        this(llmClient, tokenEstimator, hookExecutionManager, new SummaryPromptTemplate(), new MessageStripper());
    }

    /**
     * Full-injection constructor. Prefer this in tests so the prompt template and stripper can be substituted without
     * subclassing.
     */
    public DefaultCompactionEngine(LlmClient llmClient, TokenEstimator tokenEstimator,
            HookExecutionManager hookExecutionManager, SummaryPromptTemplate summaryPromptTemplate,
            MessageStripper messageStripper) {
        this.llmClient = Objects.requireNonNull(llmClient, "LlmClient cannot be null");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "TokenEstimator cannot be null");
        this.hookExecutionManager = Objects.requireNonNull(hookExecutionManager, "HookExecutionManager cannot be null");
        this.summaryPromptTemplate = Objects.requireNonNull(summaryPromptTemplate,
                "SummaryPromptTemplate cannot be null");
        this.messageStripper = Objects.requireNonNull(messageStripper, "MessageStripper cannot be null");
    }

    /**
     * Static factory mirror of the convenience constructor — clearer at call sites that the helpers are framework
     * defaults rather than required arguments.
     */
    public static DefaultCompactionEngine withDefaults(LlmClient llmClient, TokenEstimator tokenEstimator,
            HookExecutionManager hookExecutionManager) {
        return new DefaultCompactionEngine(llmClient, tokenEstimator, hookExecutionManager);
    }

    /** Static factory mirror of the full-injection constructor. */
    public static DefaultCompactionEngine withCollaborators(LlmClient llmClient, TokenEstimator tokenEstimator,
            HookExecutionManager hookExecutionManager, SummaryPromptTemplate summaryPromptTemplate,
            MessageStripper messageStripper) {
        return new DefaultCompactionEngine(llmClient, tokenEstimator, hookExecutionManager, summaryPromptTemplate,
                messageStripper);
    }

    @Override
    public CompactionResult compact(CompactionRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");

        if (COMPACTION_IN_PROGRESS.get()) {
            log.warn("Reentrant compaction attempt suppressed for {}", describeSubject(request));
            final CompactionMetadata metadata = baseMetadataBuilder(request).startedAt(Instant.now())
                    .completedAt(Instant.now()).build();
            return CompactionResult.failure(
                    new CompactionReentrancyException("Compaction already in progress on this thread"), metadata);
        }

        COMPACTION_IN_PROGRESS.set(Boolean.TRUE);
        try {
            final Instant startedAt = Instant.now();
            final TranscriptBuffer memory = request.getTranscriptBuffer();
            final List<Message> originalMessages = new ArrayList<>(memory.getMessages());
            final String originalSystemPrompt = memory.getSystemPrompt();
            final int preTokenCount = tokenEstimator.estimate(originalSystemPrompt, originalMessages);

            // Resolve effective range. Absent → full conversation (current behavior). Present → validate against
            // actual message size and tool_use/tool_result coherency at the cut points.
            final int fromIndex;
            final int toIndex;
            try {
                final CompactionRange requestedRange = request.getCompactRange().orElse(null);
                if (requestedRange == null) {
                    fromIndex = 0;
                    toIndex = originalMessages.size();
                } else {
                    final CompactionRange validated = validateRequestedRange(requestedRange, originalMessages);
                    fromIndex = validated.getFromIndex();
                    toIndex = validated.getToIndex();
                }
            } catch (IllegalArgumentException e) {
                log.warn("Compaction range invalid: {}", e.getMessage());
                final CompactionMetadata metadata = baseMetadataBuilder(request).preCompactTokenCount(preTokenCount)
                        .startedAt(startedAt).completedAt(Instant.now()).build();
                return CompactionResult.failure(e, metadata);
            }

            final List<Message> inRangeMessages = List.copyOf(originalMessages.subList(fromIndex, toIndex));
            final List<Message> prefix = List.copyOf(originalMessages.subList(0, fromIndex));
            final List<Message> tail = List.copyOf(originalMessages.subList(toIndex, originalMessages.size()));
            final int rangePreTokenCount = tokenEstimator.estimate(originalSystemPrompt, inRangeMessages);
            final List<String> discoveredToolNames = collectDiscoveredToolNames(inRangeMessages);
            final List<String> recentReadFilePaths = collectRecentReadFilePaths(inRangeMessages);
            final List<InvokedSkillRecord> invokedSkills = collectInvokedSkills(inRangeMessages);

            // 1) PreCompact hooks
            final HookRegistry registry = request.getHookRegistry();
            final PreCompactContext preContext = applyIdentity(
                    PreCompactContext.builder().invokerType(InvokerType.mainAgent()).invokerName("compaction-engine")
                            .hookRegistry(registry).environment(request.getEnvironment()).trigger(request.getTrigger())
                            .messageCount(originalMessages.size()).estimatedTokens(preTokenCount).timestamp(startedAt),
                    request).build();
            final List<HookResult> preResults = hookExecutionManager.executePreCompact(preContext);
            // Advisory only: a blocked result's feedback IS its deny reason, and the block paths below already report
            // it (AUTO through CompactionBlockedByHookException, MANUAL through the downgrade warning). Collecting it
            // here as well would splice a veto message into the summarization prompt as if it were a summarization
            // instruction.
            final List<String> preFeedback = HookFeedback.collectAdvisory(preResults);

            if (request.getTrigger() == CompactionTrigger.AUTO && hookExecutionManager.hasBlockedResult(preResults)) {
                final List<String> reasons = hookExecutionManager.collectBlockedReasons(preResults);
                final CompactionBlockedByHookException blocked = new CompactionBlockedByHookException(reasons);
                log.warn("Compaction (AUTO) blocked by PreCompactHook(s): {}", reasons);
                final CompactionMetadata metadata = baseMetadataBuilder(request).preCompactTokenCount(preTokenCount)
                        .messagesSummarized(inRangeMessages.size()).startedAt(startedAt).completedAt(Instant.now())
                        .discoveredToolNames(discoveredToolNames).build();
                return CompactionResult.failure(blocked, metadata);
            }
            if (request.getTrigger() == CompactionTrigger.MANUAL && hookExecutionManager.hasBlockedResult(preResults)) {
                log.warn("Compaction (MANUAL) PreCompactHook block(s) downgraded to warning: {}",
                        hookExecutionManager.collectBlockedReasons(preResults));
            }

            // 2) Strip non-text blocks for the summary call (in-range only)
            final List<Message> strippedMessages = messageStripper.stripNonTextBlocks(inRangeMessages);

            // 3) Build summary prompt — merge custom instructions from hooks + request
            final String mergedInstructions = mergeCustomInstructions(request.getCustomInstructions().orElse(null),
                    preFeedback);
            final String systemPrompt = summaryPromptTemplate.buildSystemPrompt(mergedInstructions);

            // 4) Summary LLM call (no tools, attribute as feature=COMPACTION). Caller-supplied metadata (e.g. the
            // invoking principal for a /compact command) wins on overlap; engine defaults fill the rest.
            final LlmCallMetadata engineDefaults = LlmCallMetadata.builder().component("compaction-engine")
                    .feature(LlmCallMetadata.Feature.COMPACTION).traceId(memory.getSessionId().toString()).build();
            final LlmCallMetadata callMetadata = request.getCallMetadata().map(c -> c.withDefaults(engineDefaults))
                    .orElse(engineDefaults);
            final LlmResponse response;
            try {
                response = llmClient.sendMessage(systemPrompt, strippedMessages, List.of(), request.getModel(),
                        callMetadata);
            } catch (RuntimeException e) {
                log.error("Compaction summary LLM call failed: {}", e.getMessage(), e);
                final CompactionMetadata metadata = baseMetadataBuilder(request).preCompactTokenCount(preTokenCount)
                        .messagesSummarized(inRangeMessages.size()).startedAt(startedAt).completedAt(Instant.now())
                        .discoveredToolNames(discoveredToolNames).build();
                return CompactionResult.failure(e, metadata);
            }

            final String summaryText = Objects.requireNonNullElse(response.getTextContent(), "");
            if (summaryText.isBlank()) {
                final CompactionMetadata metadata = baseMetadataBuilder(request).preCompactTokenCount(preTokenCount)
                        .messagesSummarized(inRangeMessages.size()).startedAt(startedAt).completedAt(Instant.now())
                        .discoveredToolNames(discoveredToolNames).build();
                return CompactionResult.failure(new IllegalStateException("Compaction summary was empty"), metadata);
            }

            // 5) Build the post-compaction message pair (boundary marker + summary body) and splice into the surviving
            // prefix/tail. For full compaction (no range) prefix and tail are empty, which yields the original
            // [boundary, summary] replacement behavior.
            final String sessionUuid = UUID.randomUUID().toString();
            final Message boundaryMessage = CompactBoundary.boundaryMessage(sessionUuid, request.getTrigger(),
                    rangePreTokenCount, inRangeMessages.size(), discoveredToolNames);
            final Message summaryMessage = CompactBoundary.summaryMessage(sessionUuid, summaryText);
            final List<Message> rebuilt = new ArrayList<>(prefix.size() + 2 + tail.size());
            rebuilt.addAll(prefix);
            rebuilt.add(boundaryMessage);
            rebuilt.add(summaryMessage);
            rebuilt.addAll(tail);
            memory.replaceWith(rebuilt);

            final int postTokenCount = tokenEstimator.estimate(originalSystemPrompt, memory.getMessages());

            final CompactionMetadata metadata = baseMetadataBuilder(request).preCompactTokenCount(preTokenCount)
                    .postCompactTokenCount(postTokenCount).messagesSummarized(inRangeMessages.size())
                    .startedAt(startedAt).completedAt(Instant.now()).discoveredToolNames(discoveredToolNames).build();

            // 6) PostCompact hooks (non-blocking; failures are logged only)
            try {
                final PostCompactContext postContext = PostCompactContext.builder().invokerType(InvokerType.mainAgent())
                        .invokerName("compaction-engine").hookRegistry(registry).environment(request.getEnvironment())
                        .trigger(request.getTrigger()).compactionMetadata(metadata).compactSummary(summaryText)
                        .transcriptBuffer(memory).recentReadFilePaths(recentReadFilePaths).invokedSkills(invokedSkills)
                        .timestamp(Instant.now()).build();
                hookExecutionManager.executePostCompact(postContext);
            } catch (RuntimeException e) {
                log.warn("PostCompactHook execution failed: {}", e.getMessage(), e);
            }

            log.info("Compaction completed: {} messages, {} -> {} tokens (trigger={})", originalMessages.size(),
                    preTokenCount, postTokenCount, request.getTrigger());
            return CompactionResult.success(summaryText, metadata);
        } finally {
            COMPACTION_IN_PROGRESS.remove();
        }
    }

    private CompactionMetadata.Builder baseMetadataBuilder(CompactionRequest request) {
        return CompactionMetadata.builder().trigger(request.getTrigger());
    }

    /**
     * Stamps the compaction's identity onto a {@link PreCompactContext.Builder} — exactly one of session id or
     * execution id, never both.
     *
     * <p>
     * Which one it is has to be told to the engine, not guessed from the transcript label: a session-less run labels
     * its buffer with its own run id, so the label alone is indistinguishable from a genuine session id, and reading
     * the shape of that label here would tie this class to whatever prefix the caller happens to mint. So the request
     * carries the answer, and its absence means "session". A session-less run therefore exports an <em>empty</em>
     * {@code AIMON_SESSION_ID} — the honest value, matching what the rewake replay path does.
     *
     * @param builder
     *            the context builder to stamp (must not be null)
     * @param request
     *            the compaction request carrying the optional run identity (must not be null)
     * @return the same builder, for chaining (never null)
     */
    private static PreCompactContext.Builder applyIdentity(PreCompactContext.Builder builder,
            CompactionRequest request) {
        final ExecutionId executionId = request.getExecutionId().orElse(null);
        if (executionId != null) {
            return builder.executionId(executionId);
        }
        return builder.sessionIdValue(request.getTranscriptBuffer().getSessionId().toString());
    }

    /**
     * Renders the compaction subject for a log line, naming it the way the request identifies it.
     *
     * @param request
     *            the compaction request (must not be null)
     * @return a short human-readable subject (never null)
     */
    private static String describeSubject(CompactionRequest request) {
        return request.getExecutionId().map(id -> "execution " + id.value())
                .orElseGet(() -> "session " + request.getTranscriptBuffer().getSessionId());
    }

    /**
     * Validates a caller-supplied {@link CompactionRange} against the actual message size and against
     * tool_use/tool_result coherency at the cut points, throwing {@link IllegalArgumentException} on violation.
     *
     * <p>
     * Cut-point coherency rules (a {@link Role#TOOL TOOL}-role message carries tool results that must immediately
     * follow the assistant tool_use that produced them):
     * <ul>
     * <li>If {@code fromIndex > 0}, {@code messages[fromIndex]} must not be {@link Role#TOOL} — otherwise the
     * preserved prefix would end with an assistant tool_use whose tool_result is summarized away.
     * <li>If {@code toIndex < messages.size()}, {@code messages[toIndex]} must not be {@link Role#TOOL} — otherwise
     * the preserved tail would start with an orphaned tool_result whose tool_use is summarized away.
     * </ul>
     */
    private static CompactionRange validateRequestedRange(CompactionRange requested, List<Message> messages) {
        final int size = messages.size();
        if (requested.getFromIndex() > size) {
            throw new IllegalArgumentException(
                    "fromIndex (" + requested.getFromIndex() + ") exceeds message count (" + size + ")");
        }
        if (requested.getToIndex() > size) {
            throw new IllegalArgumentException(
                    "toIndex (" + requested.getToIndex() + ") exceeds message count (" + size + ")");
        }
        if (requested.getFromIndex() > 0 && messages.get(requested.getFromIndex()).getRole() == Role.TOOL) {
            throw new IllegalArgumentException(
                    "fromIndex (" + requested.getFromIndex() + ") splits a tool_use/tool_result pair: "
                            + "preserved prefix would retain an unresolved assistant tool_use");
        }
        if (requested.getToIndex() < size && messages.get(requested.getToIndex()).getRole() == Role.TOOL) {
            throw new IllegalArgumentException(
                    "toIndex (" + requested.getToIndex() + ") splits a tool_use/tool_result pair: "
                            + "preserved tail would begin with an orphaned tool_result");
        }
        return requested;
    }

    private List<String> collectDiscoveredToolNames(List<Message> messages) {
        final Set<String> names = new LinkedHashSet<>();
        for (Message message : messages) {
            if (message.hasToolUses()) {
                for (ToolUse toolUse : message.getToolUses()) {
                    names.add(toolUse.getName());
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * Scans the conversation for {@code Read} tool invocations and returns the {@code file_path} arguments in
     * insertion order with duplicates collapsed to their most recent position. Snapshot taken before
     * {@link TranscriptBuffer#replaceWith(List)} so {@code PostCompactHook}s can use it to re-attach files lost in
     * the L3 summary.
     */
    private List<String> collectRecentReadFilePaths(List<Message> messages) {
        final LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Message message : messages) {
            if (!message.hasToolUses()) {
                continue;
            }
            for (ToolUse toolUse : message.getToolUses()) {
                if (!READ_TOOL_NAME_FOR_FILE_TRACKING.equals(toolUse.getName())) {
                    continue;
                }
                final Object filePathArg = toolUse.getInput().get("file_path");
                if (filePathArg instanceof String filePath && !filePath.isBlank()) {
                    paths.remove(filePath);
                    paths.add(filePath);
                }
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Scans the conversation for {@code Skill} tool invocations and returns one {@link InvokedSkillRecord} per
     * (skill-name, args) pair in occurrence order, with duplicates collapsed to their most recent position. Mirrors
     * {@link #collectRecentReadFilePaths(List)} so {@code PostCompactHook}s can use the snapshot to remind the agent
     * which skills it had activated before the L3 summary collapsed the {@code tool_use}/{@code tool_result} pairs.
     *
     * <p>
     * Invocations missing or having a blank {@code skill} input are skipped. {@code args} is normalised: missing or
     * non-string values become the empty string so equality and presentation are predictable.
     */
    private List<InvokedSkillRecord> collectInvokedSkills(List<Message> messages) {
        final LinkedHashSet<InvokedSkillRecord> records = new LinkedHashSet<>();
        for (Message message : messages) {
            if (!message.hasToolUses()) {
                continue;
            }
            for (ToolUse toolUse : message.getToolUses()) {
                if (!SKILL_TOOL_NAME_FOR_INVOCATION_TRACKING.equals(toolUse.getName())) {
                    continue;
                }
                final Object nameArg = toolUse.getInput().get(SKILL_TOOL_INPUT_KEY_NAME);
                if (!(nameArg instanceof String name) || name.isBlank()) {
                    continue;
                }
                final Object argsArg = toolUse.getInput().get(SKILL_TOOL_INPUT_KEY_ARGS);
                final String args = (argsArg instanceof String s) ? s : "";
                final InvokedSkillRecord record = InvokedSkillRecord.of(name, args);
                records.remove(record);
                records.add(record);
            }
        }
        return List.copyOf(records);
    }

    private String mergeCustomInstructions(String fromRequest, List<String> hookFeedback) {
        final StringBuilder sb = new StringBuilder();
        if (fromRequest != null && !fromRequest.isBlank()) {
            sb.append(fromRequest.trim());
        }
        for (String feedback : hookFeedback) {
            if (feedback == null || feedback.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(feedback.trim());
        }
        if (sb.isEmpty()) {
            return null;
        }
        return SummaryPromptTemplate.truncate(sb.toString());
    }
}
