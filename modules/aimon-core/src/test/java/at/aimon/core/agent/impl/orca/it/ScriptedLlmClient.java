package at.aimon.core.agent.impl.orca.it;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * A thread-safe scripted {@link LlmClient} for the runtime integration suite.
 *
 * <p>
 * <b>Why a second scripted client?</b> {@code OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient} is
 * package-private to {@code at.aimon.core.agent.impl.orca} and is shaped for the executor concurrency suite (it routes
 * off a session marker embedded in helper-built user input). The runtime IT suite drives the <b>real</b> runtime
 * through its public surface and needs to assert on what the assembled runtime offered the model — the tool
 * definitions and the system prompt — so it records those too. If the two ever converge, promote one and delete the
 * other.
 *
 * <h2>Routing</h2>
 *
 * <p>
 * Responses are keyed by <b>session id</b>: {@code OrcaAgentExecutor} stamps
 * {@link LlmCallMetadata#getTraceId() traceId} with {@code sessionId.value()} and forwards it to the 5-arg
 * {@code sendMessage} overload, so concurrent sessions never race over one shared queue. Calls that arrive without a
 * registered trace id land on {@link #UNROUTED} and consume the global fallback — deliberately, so a mis-wired test
 * shows up as an unrouted call rather than silently stealing another session's script.
 *
 * <p>
 * <b>Subagent forks share the parent's trace id.</b> {@code SubagentLlmDefaults.effectiveMetadata} builds the fork's
 * metadata with {@code component = <subagent name>} and {@code feature = "subagent"}, then fills the rest from the
 * parent via {@code withDefaults} — and {@code traceId} is one of the filled-in fields. Routing on the trace id alone
 * would therefore drop a fork's calls into the parent session's queue and interleave two scripts. So a call carrying
 * {@code feature=subagent} routes to {@link #forkRoute(String, String) traceId#component} instead, which keeps each
 * fork independently scriptable and independently assertable.
 *
 * <p>
 * <b>The compaction summariser shares it too.</b> {@code DefaultCompactionEngine} stamps
 * {@code feature=compaction, traceId=<the buffer's session id>}, so on the bare trace id its summary call would land in
 * the middle of the session's own ordered script and consume the response meant for the next ReAct iteration — the
 * session would then run one response short and finish with the wrong answer, or fall off the end of its script
 * entirely. It therefore gets its own key, {@link #compactionRoute(String)}.
 *
 * <h2>Scripting</h2>
 *
 * <ul>
 * <li>{@link #script(String, LlmResponse...)} — an ordered per-session script, one response per call.
 * <li>{@link #scriptDynamic(String, Responder...)} — same, but each entry sees the {@link Call} it is answering, so a
 * test can build the next tool call out of the previous observation (the only way to use a server-generated id such as
 * a background bash task id).
 * <li>{@link #fallback(String, LlmResponse)} — used for that session once its script is exhausted.
 * <li>{@link #globalFallback(LlmResponse)} — used when a route has <b>no script at all</b> (default: plain text
 * {@code "done"}).
 * </ul>
 *
 * <p>
 * <b>Running off the end of a script is an error, not a fallback.</b> A route that was scripted and then asked for one
 * more response than it holds throws {@link ScriptExhaustedException} unless the test registered a
 * {@link #fallback(String, LlmResponse) per-route fallback} to say it meant it. Falling through to the global fallback
 * instead would turn "the runtime made an LLM call this test never anticipated" into a green run whose final answer is
 * the innocuous {@code "done"} — the failure mode that hides an extra iteration, a mis-routed fork, and a summariser
 * call landing on the wrong key.
 */
final class ScriptedLlmClient implements LlmClient {

    /** Routing key for calls whose trace id matches no registered session. */
    static final String UNROUTED = "<unrouted>";

    /** {@code feature} value that {@code SubagentLlmDefaults} stamps on every fork call. */
    private static final String SUBAGENT_FEATURE = "subagent";

    /** {@code feature} value that {@code DefaultCompactionEngine} stamps on its summary call. */
    private static final String COMPACTION_FEATURE = "compaction";

    /** Separator between the inherited trace id and the fork's component in a fork route key. */
    private static final String FORK_SEPARATOR = "#";

    /** Token usage stamped on every helper-built response so cost/usage plumbing sees non-zero numbers. */
    private static final TokenUsage USAGE = TokenUsage.of(10, 5, 15);

    private final ConcurrentMap<String, List<Responder>> scripts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> cursors = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LlmResponse> fallbacks = new ConcurrentHashMap<>();

    private final AtomicInteger totalCalls = new AtomicInteger();
    private final ConcurrentMap<String, AtomicInteger> callsBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Call>> callsBySessionDetail = new ConcurrentHashMap<>();

    private volatile LlmResponse globalFallback = LlmResponse.text("done");

    // ---------- response helpers ----------

    /** A terminal text response (no tool uses) — ends the ReAct loop. */
    static LlmResponse text(String content) {
        return LlmResponse.of(content, List.of(), USAGE);
    }

    /** A single-tool-use response; the loop runs the tool and calls back for another response. */
    static LlmResponse callTool(String toolName, Map<String, Object> input) {
        return callTool("tu-" + toolName, toolName, input);
    }

    /** A single-tool-use response with an explicit {@code tool_use} id. */
    static LlmResponse callTool(String id, String toolName, Map<String, Object> input) {
        return LlmResponse.of("", List.of(ToolUse.of(id, toolName, input)), USAGE);
    }

    /** A response carrying several tool uses in one turn (the parallel-dispatch entry point). */
    static LlmResponse callTools(ToolUse... toolUses) {
        return LlmResponse.of("", List.of(toolUses), USAGE);
    }

    /**
     * The routing key a subagent fork's calls land on: the invoking session's trace id, then the subagent name.
     *
     * @param sessionId
     *            the invoking session's id — forks inherit it as their trace id
     * @param subagentName
     *            the fork's {@code component}
     */
    static String forkRoute(String sessionId, String subagentName) {
        return sessionId + FORK_SEPARATOR + subagentName;
    }

    /**
     * The routing key the compaction summariser's call lands on.
     *
     * <p>
     * {@code DefaultCompactionEngine} traces its summary call with the buffer's session id, so without a key of its own
     * it would consume the session's next scripted response mid-turn. Script this key to control the summary text;
     * leave
     * it unscripted and the global fallback answers, which is enough whenever the test only cares that compaction
     * happened.
     *
     * <p>
     * The key shape is shared with {@link #forkRoute(String, String)}, so a subagent literally named {@code compaction}
     * would collide with it. No production subagent has that name, and the IT fixtures here are named {@code it-*}.
     */
    static String compactionRoute(String sessionId) {
        return sessionId + FORK_SEPARATOR + COMPACTION_FEATURE;
    }

    // ---------- scripting ----------

    /**
     * Registers an ordered script for {@code sessionId}, consumed one response per call.
     *
     * <p>
     * Re-scripting a route <b>rewinds</b> it: the new script is read from its first entry. Keeping the old cursor would
     * silently skip as many entries as the previous script had already consumed, so a test that re-scripts mid-run
     * would
     * get responses from the middle of its new script with nothing to indicate it.
     */
    ScriptedLlmClient script(String sessionId, LlmResponse... responses) {
        final List<Responder> responders = new ArrayList<>(responses.length);
        for (LlmResponse response : responses) {
            final LlmResponse constant = Objects.requireNonNull(response, "scripted response must not be null");
            responders.add(call -> constant);
        }
        scripts.put(sessionId, List.copyOf(responders));
        cursors.put(sessionId, new AtomicInteger());
        return this;
    }

    /**
     * Registers an ordered script whose entries see the call they are answering.
     *
     * <p>
     * Use when the next tool call must be built from the previous observation — a background bash task id, for
     * instance, is generated by the tool and cannot be written into a static script.
     *
     * <p>
     * Rewinds the route's cursor, exactly as {@link #script(String, LlmResponse...)} does.
     */
    ScriptedLlmClient scriptDynamic(String sessionId, Responder... responders) {
        scripts.put(sessionId, List.of(responders));
        cursors.put(sessionId, new AtomicInteger());
        return this;
    }

    /** Per-session response used once its ordered script is exhausted (or when it has no script). */
    ScriptedLlmClient fallback(String sessionId, LlmResponse response) {
        fallbacks.put(sessionId, Objects.requireNonNull(response));
        return this;
    }

    /** Response used when the routed session has neither a live script nor a fallback. */
    ScriptedLlmClient globalFallback(LlmResponse response) {
        this.globalFallback = Objects.requireNonNull(response);
        return this;
    }

    // ---------- recorded calls ----------

    int totalCalls() {
        return totalCalls.get();
    }

    int callCount(String sessionId) {
        final AtomicInteger counter = callsBySession.get(sessionId);
        return counter == null ? 0 : counter.get();
    }

    List<Call> callsFor(String sessionId) {
        return List.copyOf(callsBySessionDetail.getOrDefault(sessionId, List.of()));
    }

    /** The most recent call routed to {@code sessionId}; fails the caller with an NPE if there was none. */
    Call lastCallFor(String sessionId) {
        final List<Call> calls = callsFor(sessionId);
        if (calls.isEmpty()) {
            throw new IllegalStateException("no LLM call was routed to session " + sessionId);
        }
        return calls.get(calls.size() - 1);
    }

    /** The first call routed to {@code sessionId} — the one carrying the runtime's initial tool offer. */
    Call firstCallFor(String sessionId) {
        final List<Call> calls = callsFor(sessionId);
        if (calls.isEmpty()) {
            throw new IllegalStateException("no LLM call was routed to session " + sessionId);
        }
        return calls.get(0);
    }

    // ---------- LlmClient ----------

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        return handle(systemPrompt, messages, tools, LlmCallMetadata.empty());
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig, LlmCallMetadata metadata) {
        return handle(systemPrompt, messages, tools, metadata);
    }

    @Override
    public String getProviderName() {
        return "ScriptedIT";
    }

    private LlmResponse handle(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmCallMetadata metadata) {
        final String sessionId = route(metadata);

        final Call call = new Call(systemPrompt, messages, tools, metadata);
        totalCalls.incrementAndGet();
        callsBySession.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();
        callsBySessionDetail.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(call);

        final List<Responder> script = scripts.get(sessionId);
        if (script != null) {
            final int index = cursors.get(sessionId).getAndIncrement();
            if (index < script.size()) {
                return script.get(index).respondTo(call);
            }
            final LlmResponse scriptedFallback = fallbacks.get(sessionId);
            if (scriptedFallback == null) {
                throw new ScriptExhaustedException(sessionId, script.size(), index + 1);
            }
            return scriptedFallback;
        }
        final LlmResponse fallback = fallbacks.get(sessionId);
        return fallback != null ? fallback : globalFallback;
    }

    private String route(LlmCallMetadata metadata) {
        final String trace = metadata.getTraceId().orElse(null);
        if (trace == null) {
            return UNROUTED;
        }
        // A fork inherits the parent's trace id (SubagentLlmDefaults.effectiveMetadata -> withDefaults), so routing on
        // the trace alone would interleave the fork's calls with its parent's. feature=subagent + component=<name> is
        // what actually distinguishes them.
        if (metadata.getFeature().filter(SUBAGENT_FEATURE::equals).isPresent()) {
            return forkRoute(trace, metadata.getComponent().orElse("<unnamed>"));
        }
        // Same story for the compaction summariser: DefaultCompactionEngine traces it with the buffer's session id, so
        // on the bare trace it would eat the response meant for the turn's next iteration.
        if (metadata.getFeature().filter(COMPACTION_FEATURE::equals).isPresent()) {
            return compactionRoute(trace);
        }
        // A trace id that matches no registered session still routes to itself when the test only inspects calls
        // (rather than scripting them) — but a session with neither script nor fallback falls through to the global
        // fallback anyway, so the two cases stay distinguishable via callCount().
        return trace;
    }

    /**
     * Thrown when a scripted route is asked for one more response than it holds.
     *
     * <p>
     * This is a test-authoring error, not a runtime condition: the runtime made an LLM call the test did not
     * anticipate.
     * Answering it from the global fallback instead would let an extra iteration, a mis-routed fork, or a summariser
     * call landing on the wrong key pass as a green run whose final answer happens to be {@code "done"}.
     */
    static final class ScriptExhaustedException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        ScriptExhaustedException(String route, int scriptSize, int requested) {
            super(String
                    .format("script for route '%s' is exhausted: it holds %d response(s) but call #%d was requested. "
                            + "Either the runtime made an unanticipated LLM call, or the script is short — "
                            + "add the missing entries, or register fallback(\"%s\", ...) if the extra calls are "
                            + "expected.", route, scriptSize, requested, route));
        }
    }

    /** One entry of a dynamic script: builds a response from the call it is answering. */
    @FunctionalInterface
    interface Responder {
        LlmResponse respondTo(Call call);
    }

    /** One recorded {@code sendMessage} invocation — everything the runtime offered the model. */
    static final class Call {

        private final String systemPrompt;
        private final List<Message> messages;
        private final List<ToolDefinition> tools;
        private final LlmCallMetadata metadata;

        Call(String systemPrompt, List<Message> messages, List<ToolDefinition> tools, LlmCallMetadata metadata) {
            this.systemPrompt = systemPrompt;
            this.messages = messages == null ? List.of() : List.copyOf(messages);
            this.tools = tools == null ? List.of() : List.copyOf(tools);
            this.metadata = metadata == null ? LlmCallMetadata.empty() : metadata;
        }

        String systemPrompt() {
            return systemPrompt;
        }

        /** The metadata the caller stamped — carries the trace id, and for a fork the component/feature. */
        LlmCallMetadata metadata() {
            return metadata;
        }

        List<ToolDefinition> tools() {
            return tools;
        }

        /** Names of the tools the runtime offered on this call. */
        List<String> toolNames() {
            return tools.stream().map(ToolDefinition::getName).collect(Collectors.toList());
        }

        /** Non-null text contents of every message on this call, in order. */
        List<String> messageContents() {
            return messages.stream().map(Message::getContent).filter(Objects::nonNull).collect(Collectors.toList());
        }

        /**
         * Tool observations carried on this call.
         *
         * <p>
         * A tool result does <b>not</b> live in {@link Message#getContent()} — it is a structured
         * {@link ToolUseResult} on a {@code Role.TOOL} message, and the text-content view of such a message is empty.
         * Assertions about "did the tool's output get back to the model" must therefore look here, not at
         * {@link #messageContents()}.
         */
        List<String> observations() {
            return messages.stream().flatMap(m -> m.getToolUseResults().stream()).map(ToolUseResult::getContent)
                    .filter(Objects::nonNull).collect(Collectors.toList());
        }

        /** The most recent tool observation on this call, or {@code ""} when the call carries none. */
        String lastObservation() {
            final List<String> all = observations();
            return all.isEmpty() ? "" : all.get(all.size() - 1);
        }

        /** Every piece of text the model could see on this call — message text plus tool observations. */
        List<String> allText() {
            final List<String> all = new ArrayList<>(messageContents());
            all.addAll(observations());
            return all;
        }
    }
}
