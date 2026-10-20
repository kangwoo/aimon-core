package at.aimon.core.toolinvocation.approval;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.tools.ForkAccess;
import at.aimon.core.tools.InvokingSessionAccess;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Requires the user to confirm a tool call before a tool that changes something is allowed to run.
 *
 * <p>
 * The gate reads the tool's own {@link SideEffectLevel} declaration and asks about anything above
 * {@link #getExemptAtOrBelow()} — by default anything above {@link SideEffectLevel#READ_ONLY}, i.e. every tool that
 * writes. It sits in {@link at.aimon.core.toolinvocation.SingleToolInvoker}, the one pipeline both the main agent and
 * subagent forks dispatch through, so a fork cannot route around it.
 *
 * <p>
 * <b>{@link DestructiveBehavior#DESTRUCTIVE} is asked about whatever the threshold says.</b> The threshold grades the
 * one question {@link SideEffectLevel} answers — does the tool write? — and an operator who raises it to
 * {@link SideEffectLevel#MUTATING} is saying writes as such are fine here, not that overwriting and deleting are. Those
 * are a second, unordered declaration ({@link Tool#getDestructiveBehavior()}), read only once the first says the tool
 * writes at all, and the gate puts them to the user unconditionally. This is the one rule the threshold cannot switch
 * off; an operator who wants no prompts at all leaves the gate unconfigured, which is an explicit choice rather than a
 * threshold that quietly turns out to mean "never ask".
 *
 * <p>
 * <b>This is a different mechanism from the side-effect ceiling</b> (
 * {@code DefaultToolExecutionManager(SideEffectLevel)}), and the two compose rather than overlap:
 *
 * <table border="1">
 * <caption>The two side-effect controls</caption>
 * <tr>
 * <th></th>
 * <th>Ceiling</th>
 * <th>This gate</th>
 * </tr>
 * <tr>
 * <td>Nature</td>
 * <td>static — the host decides up front</td>
 * <td>dynamic — a human decides at the moment of the call</td>
 * </tr>
 * <tr>
 * <td>Needs a person present</td>
 * <td>no</td>
 * <td>yes</td>
 * </tr>
 * <tr>
 * <td>Run with nobody to ask</td>
 * <td>enforced normally</td>
 * <td>falls back (see below)</td>
 * </tr>
 * </table>
 *
 * <p>
 * <b>Asked once per scope, not once per call.</b> An agent editing ten files must not produce ten prompts — a user
 * prompted that often stops reading the prompt, and an approval gate that is not read is worse than none. The answer
 * is therefore remembered in a {@link ToolApprovalStore} keyed by the session whose reach it has, and both answers
 * stick: a declined tool is not re-asked either. Reach follows the rule the skill-approval stack already uses for
 * {@code ApprovalScope.SESSION} — a turn is keyed by its own {@link ToolContextKeys#SESSION_ID}, and a fork, which has
 * no session of its own, by the {@link ToolContextKeys#INVOKING_SESSION_ID} it carries. So work a session delegated is
 * covered by what the user answered in that session, which is the span they could actually see when they answered.
 *
 * <p>
 * The prompt still shows the <b>call</b>, arguments and all — "allow {@code Bash}?" is not a question anyone can
 * answer, and the tool name alone was what the first cut of this gate asked. Arguments are shown but not keyed on: the
 * answer covers every later call of that tool, and the prompt says so rather than letting one concrete command read as
 * the whole of what is being approved.
 *
 * <p>
 * <b>Runs with no session at all</b> — scheduled routines, rewake replays — have no scope to key an answer to and
 * nobody to prompt. They are asked every time, which with the default
 * {@link AskPromptHandler#fromEnv(java.util.Map) env-derived handler} means denied. That is the fail-safe direction,
 * but it is a real constraint: <b>a deployment that runs mutating tools unattended must either leave this gate
 * unconfigured (the ceiling still applies) or supply a handler that can answer without a human.</b> Silently
 * approving unattended work is not offered, because the whole point of the gate is that a person said yes. Raising the
 * threshold is not a third way out, and less of one than it looks: {@link DestructiveBehavior#DESTRUCTIVE} is the
 * default, so until a tool has been audited and declares otherwise, an unattended run fails on it at any threshold.
 *
 * <p>
 * Thread-safe, given a thread-safe handler and store — both are required to be. A parallel tool batch may evaluate
 * several tools against one gate at once, and parallel foreground forks of one session share its scope.
 *
 * <p>
 * <b>The same question is asked once even when it arrives several times at once.</b> Looking an answer up, asking and
 * remembering is not atomic on its own, so two forks of one session calling the same tool together would both miss
 * and both ask. The gate therefore keeps the question currently in front of the user per (scope, tool): the first
 * caller asks, the others wait for that answer and share it, and nothing is remembered twice. Different questions —
 * another scope, or another tool in the same scope — never wait for each other here; putting several distinct prompts
 * on one console one at a time is the {@link AskPromptHandler}'s job. A caller interrupted while waiting for someone
 * else's prompt returns a refusal rather than an approval and remembers nothing. An asker whose run was cancelled
 * while it asked — interrupted, so its handler's answer is not the user's — remembers nothing either, and neither a
 * cancelled nor a failed prompt is handed to the waiters as the user's answer: they go back and ask for themselves.
 *
 * <p>
 * When the run is a fork labelled with its task id ({@link ForkAccess#TASK_ID_ATTRIBUTE}, present only when parallel
 * foreground tasks are enabled), the prompt starts with {@code [task <first 8 chars>]} so the user can tell which of
 * several concurrent forks is asking.
 *
 * @see SideEffectLevel
 * @see DestructiveBehavior
 * @see ToolApprovalStore
 */
public final class SideEffectApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(SideEffectApprovalGate.class);

    /** Longest a single argument value is shown as before it is cut. */
    private static final int MAX_VALUE_CHARS = 60;

    /** Longest the whole rendered argument list is shown as before it is cut. */
    private static final int MAX_ARGUMENTS_CHARS = 200;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final SideEffectLevel exemptAtOrBelow;
    private final AskPromptHandler channel;
    private final ToolApprovalStore store;

    /**
     * The questions currently in front of the user, keyed by scope and tool — the unit {@link #store} remembers
     * answers in. Holds an entry only while its prompt is open.
     */
    private final ConcurrentMap<String, CompletableFuture<Outcome>> inFlight = new ConcurrentHashMap<>();

    /**
     * Creates a gate that asks about every tool declaring more than {@link SideEffectLevel#READ_ONLY}, remembering
     * answers in a fresh {@link InMemoryToolApprovalStore}.
     *
     * @param channel
     *            resolves a prompt to allow/deny (must not be null); the CLI supplies an interactive implementation
     * @throws NullPointerException
     *             if {@code channel} is null
     */
    public SideEffectApprovalGate(AskPromptHandler channel) {
        this(SideEffectLevel.READ_ONLY, channel, new InMemoryToolApprovalStore());
    }

    /**
     * Creates a gate.
     *
     * @param exemptAtOrBelow
     *            the highest {@link SideEffectLevel} that passes without asking (must not be null).
     *            {@link SideEffectLevel#READ_ONLY}, the default, asks about everything that writes.
     *            {@link SideEffectLevel#MUTATING} exempts writes that are additive but <em>still asks about anything
     *            declaring {@link DestructiveBehavior#DESTRUCTIVE}</em> — it does not disable the gate, and no value of
     *            this parameter does. That asymmetry is the point: the threshold is ordered because
     *            {@link SideEffectLevel} is, and destructiveness is not on that scale. Note what the conservative
     *            default on the other axis makes of this setting today — with {@code DESTRUCTIVE} assumed of any
     *            unaudited tool, {@code MUTATING} exempts only the tools that have actually declared
     *            {@link DestructiveBehavior#NON_DESTRUCTIVE}, and exempts nothing at all until some have
     * @param channel
     *            resolves a prompt to allow/deny (must not be null)
     * @param store
     *            remembers answers so a scope is asked at most once per tool (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public SideEffectApprovalGate(SideEffectLevel exemptAtOrBelow, AskPromptHandler channel, ToolApprovalStore store) {
        this.exemptAtOrBelow = Objects.requireNonNull(exemptAtOrBelow, "Exempt-at-or-below level cannot be null");
        this.channel = Objects.requireNonNull(channel, "Approval channel cannot be null");
        this.store = Objects.requireNonNull(store, "Approval store cannot be null");
    }

    /**
     * Decides whether {@code tool} may run, prompting the user when its declarations are not exempt — either because
     * its {@link SideEffectLevel} exceeds the threshold or because it declares
     * {@link DestructiveBehavior#DESTRUCTIVE}, which the threshold does not cover — and this scope has not already
     * answered.
     *
     * <p>
     * {@code toolUse} is read for the prompt only. The decision keys on the tool, never on its arguments: keying on
     * arguments would make {@code Bash} a fresh question per command, which is the prompt storm the store exists to
     * prevent. The user is told as much, because a prompt that shows one call and silently buys the rest is a prompt
     * that misleads.
     *
     * @param tool
     *            the resolved tool about to be dispatched (must not be null)
     * @param toolUse
     *            the call being approved, shown to the user so the question names something concrete (must not be
     *            null); its arguments do not affect the decision or what is remembered
     * @param context
     *            the tool context of this dispatch, read for the session ids that scope the answer (must not be null)
     * @return {@link Optional#empty()} when the call may proceed, or the reason to refuse it — phrased for the model,
     *         which sees it as the tool result
     * @throws NullPointerException
     *             if any parameter is null
     */
    public Optional<String> denialReason(Tool tool, ToolUse toolUse, ToolContext context) {
        Objects.requireNonNull(tool, "Tool cannot be null");
        Objects.requireNonNull(toolUse, "Tool use cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final SideEffectLevel declared = tool.getSideEffectLevel();
        // A tool that changes nothing can destroy nothing, so the second axis is not read — and not readable as a
        // contradiction — below MUTATING.
        final DestructiveBehavior destructive = declared == SideEffectLevel.MUTATING
                ? tool.getDestructiveBehavior()
                : DestructiveBehavior.NON_DESTRUCTIVE;
        if (destructive == DestructiveBehavior.NON_DESTRUCTIVE && exemptAtOrBelow.permits(declared)) {
            return Optional.empty();
        }

        final String toolName = tool.getDefinition().getName();
        final Optional<String> scopeKey = scopeKeyOf(context);

        final Optional<Boolean> remembered = scopeKey.flatMap(key -> store.lookup(key, toolName));
        if (remembered.isPresent()) {
            return remembered.get() ? Optional.empty() : Optional.of(declinedMessage(toolName, true));
        }

        final String prompt = promptFor(labelOf(context), toolName, toolUse, declared, destructive);
        if (scopeKey.isEmpty()) {
            // No scope: nothing to remember and nothing to share — asked every time, as always.
            return decided(toolName, declared, destructive, scopeKey, channel.resolve(prompt) == Decision.ALLOW);
        }

        final String key = scopeKey.get();
        final String question = key + '\0' + toolName;
        while (true) {
            final CompletableFuture<Outcome> mine = new CompletableFuture<>();
            final CompletableFuture<Outcome> prior = inFlight.putIfAbsent(question, mine);
            if (prior != null) {
                // The same question is already in front of the user: wait for that answer instead of asking again.
                final Optional<Outcome> shared = awaitShared(prior);
                if (shared.isEmpty()) {
                    // This run was cancelled while it waited: a refusal, never an approval, and nothing remembered.
                    return Optional.of(cancelledMessage(toolName));
                }
                if (shared.get() == Outcome.ALLOWED) {
                    return Optional.empty();
                }
                if (shared.get() == Outcome.DECLINED) {
                    return Optional.of(declinedMessage(toolName, false));
                }
                // The asker got no answer from the user (its run was cancelled or its prompt failed): look again, and
                // ask for ourselves if the question is still unanswered. The asker may not have cleared its finished
                // slot yet; clearing it here (only if it is still that slot) keeps us from spinning on it.
                inFlight.remove(question, prior);
                final Optional<Boolean> answered = store.lookup(key, toolName);
                if (answered.isPresent()) {
                    return answered.get() ? Optional.empty() : Optional.of(declinedMessage(toolName, true));
                }
                continue;
            }
            return askAsOwner(mine, key, prompt, toolName, declared, destructive);
        }
    }

    /**
     * Asks the question this caller won the in-flight slot for, remembers the user's answer and hands it to the
     * waiters.
     */
    private Optional<String> askAsOwner(CompletableFuture<Outcome> mine, String key, String prompt, String toolName,
            SideEffectLevel declared, DestructiveBehavior destructive) {
        final String question = key + '\0' + toolName;
        try {
            // Won the slot, but another caller may have asked, remembered and left between our lookup and now.
            final Optional<Boolean> answeredMeanwhile = store.lookup(key, toolName);
            if (answeredMeanwhile.isPresent()) {
                mine.complete(Outcome.of(answeredMeanwhile.get()));
                return answeredMeanwhile.get() ? Optional.empty() : Optional.of(declinedMessage(toolName, true));
            }
            final boolean allowed = channel.resolve(prompt) == Decision.ALLOW;
            if (Thread.currentThread().isInterrupted()) {
                // This run was cancelled while it asked: whatever the handler returned (the CLI denies an interrupted
                // caller without prompting) is not the user's answer, so it is neither remembered nor shared.
                mine.complete(Outcome.NO_ANSWER);
                log.info("Approval prompt for tool '{}' cancelled for scope {}; nothing remembered", toolName, key);
                return Optional.of(cancelledMessage(toolName));
            }
            // Remember before completing, so a waiter that wakes and looks at the store already finds the answer.
            store.remember(key, toolName, allowed);
            mine.complete(Outcome.of(allowed));
            return decided(toolName, declared, destructive, Optional.of(key), allowed);
        } finally {
            if (!mine.isDone()) {
                // The prompt threw (RuntimeException or Error): never leave a waiter hanging, and do not pass the
                // failure off as the user's refusal — the waiters ask for themselves. The throwable itself still
                // propagates to the invoker's catch-all, as it always has.
                log.warn("Approval prompt for tool '{}' failed for scope {}; waiting calls will ask again", toolName,
                        key);
                mine.complete(Outcome.NO_ANSWER);
            }
            inFlight.remove(question, mine);
        }
    }

    /**
     * Waits for the outcome of a question another caller is asking right now. Empty when this caller is interrupted
     * while waiting — its run was cancelled; the flag is restored for the caller.
     */
    private static Optional<Outcome> awaitShared(CompletableFuture<Outcome> prior) {
        try {
            return Optional.of(prior.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            // Never completed exceptionally; treated as no answer so the caller asks for itself.
            return Optional.of(Outcome.NO_ANSWER);
        }
    }

    private static Optional<String> decided(String toolName, SideEffectLevel declared, DestructiveBehavior destructive,
            Optional<String> scopeKey, boolean allowed) {
        if (allowed) {
            log.debug("Tool '{}' ({}/{}) approved for scope {}", toolName, declared, destructive,
                    scopeKey.orElse("<none>"));
            return Optional.empty();
        }
        log.info("Tool '{}' ({}/{}) declined for scope {}", toolName, declared, destructive, scopeKey.orElse("<none>"));
        return Optional.of(declinedMessage(toolName, false));
    }

    /**
     * Returns the highest declaration that runs without asking.
     *
     * @return the exemption threshold (never null)
     */
    public SideEffectLevel getExemptAtOrBelow() {
        return exemptAtOrBelow;
    }

    /**
     * Returns the store answers are remembered in, so a session-end path can {@link ToolApprovalStore#revoke revoke}
     * what that session granted.
     *
     * @return the approval store (never null)
     */
    public ToolApprovalStore getStore() {
        return store;
    }

    /**
     * Resolves the scope an answer given now would apply to: this run's own session when it is a turn, otherwise the
     * session that spawned it. Empty for a run with neither, which therefore cannot cache an answer.
     */
    private static Optional<String> scopeKeyOf(ToolContext context) {
        final Optional<SessionId> session = context.get(ToolContextKeys.SESSION_ID)
                .or(() -> InvokingSessionAccess.invokerOf(context));
        return session.map(id -> "session:" + id.value());
    }

    /**
     * Builds the question the user answers.
     *
     * <p>
     * It names the actual call, because "allow Bash?" and "allow {@code Bash(rm -rf /)}?" are not the same question
     * and a user shown only the first cannot answer the second. It then says plainly that the answer outlives this
     * call — the store is keyed by tool, so approving {@code Bash(ls)} approves every later {@code Bash}. Showing the
     * arguments without saying that would be worse than not showing them: it reads as consent to one command.
     */
    private static String promptFor(String label, String toolName, ToolUse toolUse, SideEffectLevel declared,
            DestructiveBehavior destructive) {
        final String what = destructive == DestructiveBehavior.DESTRUCTIVE
                ? ", so it may overwrite or remove state that already exists"
                : ", so it may change state outside this agent";
        return label + "Allow " + renderCall(toolName, toolUse) + "? It declares " + declared + " / " + destructive
                + what + ". Approving covers every '" + toolName
                + "' call for the rest of this session, whatever its arguments.";
    }

    /**
     * Returns {@code "[task <first 8 chars>] "} when the run is a fork labelled with its task id, otherwise the empty
     * string — so a prompt from an unlabelled run is exactly what it always was.
     */
    private static String labelOf(ToolContext context) {
        return ForkAccess.taskIdOf(context).map(taskId -> "[task " + ForkAccess.shortTaskId(taskId) + "] ").orElse("");
    }

    /**
     * Renders the call as {@code 'Name(key=value, ...)'}, arguments sorted by key so the same call reads the same way
     * twice ({@link ToolUse} holds an unordered map), flattened to one line and truncated — a prompt is read in a
     * terminal, and an argument that scrolls the question off screen defeats the point of showing it.
     */
    private static String renderCall(String toolName, ToolUse toolUse) {
        final Map<String, Object> input = toolUse.getInput();
        if (input.isEmpty()) {
            return "'" + toolName + "()'";
        }
        final String rendered = input.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + shorten(String.valueOf(entry.getValue()), MAX_VALUE_CHARS))
                .collect(Collectors.joining(", "));
        return "'" + toolName + "(" + shorten(rendered, MAX_ARGUMENTS_CHARS) + ")'";
    }

    private static String shorten(String value, int limit) {
        final String oneLine = WHITESPACE.matcher(value).replaceAll(" ").trim();
        return oneLine.length() <= limit ? oneLine : oneLine.substring(0, limit) + "...";
    }

    /** The refusal the model sees when its run was cancelled before the user answered. */
    private static String cancelledMessage(String toolName) {
        return "Tool '" + toolName + "' was not approved: this run was cancelled while waiting for the user's answer.";
    }

    /**
     * The refusal the model sees. It names the user as the decider on purpose: told only that a tool "failed", a model
     * retries it, and a retry loop against a declined tool re-prompts nobody and accomplishes nothing.
     */
    private static String declinedMessage(String toolName, boolean remembered) {
        return "Tool '" + toolName + "' was not approved by the user" + (remembered ? " earlier in this session" : "")
                + ". Do not retry it; " + "either continue without it or ask the user how they would like to proceed.";
    }

    /** What a question in front of the user came to, as handed to the callers waiting on it. */
    private enum Outcome {
        ALLOWED, DECLINED,
        /**
         * The user gave no answer — the asker's run was cancelled while it asked, or its prompt failed. Nothing was
         * remembered, and the waiters ask again.
         */
        NO_ANSWER;

        static Outcome of(boolean allowed) {
            return allowed ? ALLOWED : DECLINED;
        }
    }
}
