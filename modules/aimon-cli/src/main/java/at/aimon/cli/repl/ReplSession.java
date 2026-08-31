package at.aimon.cli.repl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.cli.budget.BudgetCommandHandler;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.factory.AgentSetupFactory;
import at.aimon.cli.skill.InteractiveSkillApprovalChannel;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.RewoundTurn;
import at.aimon.core.agent.session.SubmitOutcome;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.SkillTurnSuspendedEvent;
import at.aimon.core.skill.policy.pending.PendingTurnId;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.TraceSpanStore;

public class ReplSession {

    /** Slash command that shows or mutates the session default {@link ExecutionBudget}. */
    public static final String BUDGET_COMMAND = "/budget";

    /** Slash command that lists / inspects / stops background workflow runs. */
    public static final String RUNS_COMMAND = "/runs";

    /** Slash command that takes an interrupted turn back out of the history and runs it again. */
    public static final String RETRY_COMMAND = "/retry";

    /**
     * Grace period granted to the cooperative interrupt path before the SIGINT handler falls back to
     * {@link CompletableFuture#cancel(boolean)}. Chosen so that COOPERATIVE-behaviour tools (see
     * {@link at.aimon.core.agent.interrupt.InterruptBehavior}) have a predictable window to observe the tripped
     * {@link at.aimon.core.agent.interrupt.CancellationSignal} and return an interrupted result without losing the
     * hard-cancel escape hatch the user expects from Ctrl+C on non-cooperative work.
     */
    private static final long SIGINT_FALLBACK_GRACE_MS = 500L;

    /** TRACE-02: max characters of captured tool/LLM content shown in the {@code /trace} one-line preview. */
    private static final int PREVIEW_MAX_CHARS = 160;

    private static final Logger log = LoggerFactory.getLogger(ReplSession.class);

    private final OrcaAgentExecutor agentExecutor;
    private final OrcaAgentRuntime agentRuntime;
    private final LiveSession liveSession;
    private final CliSettings settings;
    private final OutputFormatter formatter;
    private final BudgetCommandHandler budgetHandler;
    private final RunsCommandHandler runsHandler;
    private final MessageQueueManager messageQueueManager;
    /**
     * Pending-turn registry shared with the executor's pre-flight scanner. Read by the SIGINT path
     * ({@link #requestInterruptWithFallback}) to drop the just-suspended turn captured in
     * {@link #latestPendingTurnId} so abandoned turns are not left for the timeout reaper. May be {@code null} in
     * legacy test setups that skip SK-11 wiring.
     */
    private final PendingTurnRegistry pendingTurnRegistry;
    /**
     * Optional inline skill-approval channel (SK-11.6). When non-null, {@link #start()} binds the active JLine terminal
     * to this channel for the duration of the REPL so the channel can synchronously prompt the user for ASK decisions
     * mid-turn. When null, the executor's pre-flight scanner falls through to the legacy SK-11.4 suspend/resume path.
     */
    private final InteractiveSkillApprovalChannel skillApprovalChannel;
    // TRACE-01: present when cli.tracing is enabled; backs the `/trace` command. Empty otherwise.
    private final Optional<TraceSpanStore> traceSpanStore;
    /**
     * The id of the pending turn registered during the in-flight turn, captured from
     * {@link SkillTurnSuspendedEvent}. Reset to {@code null} at the start of every {@link #executeAgent} call so
     * SIGINT only ever drops a turn the user just observed in this session — never an unrelated suspended turn from a
     * prior interaction. Volatile because the SIGINT handler runs on JLine's signal-dispatch thread.
     */
    private volatile PendingTurnId latestPendingTurnId;
    /**
     * Bound to the JLine terminal for the duration of {@link #start()}; used by {@link #executeAgent(String)} to
     * install a SIGINT handler that cancels the in-flight {@code CompletableFuture} when the user presses Ctrl+C
     * while a turn is running. Null outside of {@code start()} (e.g. tests that drive {@link #processInput(String)}
     * directly), in which case signal handling is skipped.
     *
     * <p>
     * Marked {@code volatile} because the write in {@link #start()} and the read in {@link #awaitAndRender} may
     * happen on different threads if the REPL is ever driven by an async entry point (e.g. the SESSION-03 SDK
     * facade).
     */
    private volatile Terminal activeTerminal;
    private boolean running;
    /**
     * Re-entrancy guard for {@link #drainQueueAfterTurn()}: the drain loop calls {@link #processInput(String)} for
     * each replayed entry, and that path eventually invokes {@link #executeAgent(String)} which itself would call
     * {@code drainQueueAfterTurn} again in its {@code finally}. The outer loop already holds the drained batch, so the
     * nested drain must be a no-op to avoid redundant work and double-notifications on downstream listeners.
     */
    private boolean draining;

    /**
     * ReplSession을 생성한다.
     *
     * <p>
     * {@code initialBudget}이 {@code null}이면 세션 기본 budget 없이 동작하며, 이는 BUDGET-05 도입 이전 동작과 동일하다.
     *
     * <p>
     * Busy-state bookkeeping (previously implemented locally via {@code QueryGuard}) is now owned by
     * {@link LiveSession#offerAsync}: the session CAS-acquires its own busy flag and returns a
     * {@link SubmitOutcome} that tells this class whether the turn ran inline or was enqueued. The
     * {@link MessageQueueManager} obtained from {@code agentSetup} is the same instance wired into the session, so
     * mid-turn inputs the Orca loop injects (CQ-03) and inputs enqueued by the session (SESSION-04) share one queue.
     */
    public ReplSession(AgentSetupFactory.AgentSetup agentSetup, CliSettings settings, ExecutionBudget initialBudget) {
        Objects.requireNonNull(agentSetup, "agentSetup cannot be null");
        Objects.requireNonNull(settings, "settings cannot be null");

        this.agentExecutor = agentSetup.getAgentExecutor();
        this.agentRuntime = agentSetup.getAgentRuntime();
        this.liveSession = Objects.requireNonNull(agentSetup.getLiveSession(),
                "agentSetup.agentSession cannot be null");
        this.settings = settings;
        this.formatter = agentSetup.getOutputFormatter();
        this.budgetHandler = new BudgetCommandHandler(initialBudget);
        this.runsHandler = new RunsCommandHandler(agentRuntime.getWorkflowRunner().orElse(null));
        this.messageQueueManager = Objects.requireNonNull(agentSetup.getMessageQueueManager(),
                "agentSetup.messageQueueManager cannot be null");
        this.pendingTurnRegistry = agentSetup.getPendingTurnRegistry();
        this.skillApprovalChannel = agentSetup.getSkillApprovalChannel();
        this.traceSpanStore = agentSetup.getTraceSpanStore();
        this.latestPendingTurnId = null;
        this.running = false;
        this.draining = false;
        // Seed the session default budget from the CLI-supplied initial budget so session-scoped paths
        // (liveSession.submitAsync) observe the same budget that BudgetCommandHandler exposes through /budget.
        syncSessionBudget();
    }

    /** REPL 세션을 시작한다. */
    public void start() {
        running = true;
        formatter.displayWelcome();

        displayAgentInfo();

        try (Terminal terminal = TerminalBuilder.builder().build()) {
            this.activeTerminal = terminal;
            // Configure parser to not treat backslash as escape character
            DefaultParser parser = new DefaultParser();
            parser.setEscapeChars(null); // Disable escape character handling

            LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).parser(parser)
                    .option(LineReader.Option.BRACKETED_PASTE, true) // Enable bracketed paste mode
                    .build();

            // Configure Alt+Enter for multi-line input
            KeyMap<Binding> keyMap = lineReader.getKeyMaps().get(LineReader.MAIN);
            keyMap.bind(new Reference(LineReader.ACCEPT_LINE), "\033\r"); // Alt+Enter

            if (skillApprovalChannel != null) {
                // SK-11.6: hand the very same JLine reader the REPL uses to the inline approval channel so it can
                // prompt the user synchronously when the pre-flight scanner sees ASK decisions mid-turn. Re-using the
                // single reader (rather than constructing a second one on the same terminal) avoids racing on JLine's
                // raw/cooked mode flips. Bound for the lifetime of the REPL; cleared in the matching finally below.
                skillApprovalChannel.bindLineReader(lineReader);
            }

            while (running) {
                try {
                    StringBuilder multiLineInput = new StringBuilder();
                    String line;

                    // Read first line
                    line = lineReader.readLine(settings.getPrompt());
                    if (line == null) {
                        break;
                    }

                    // Handle backslash continuation for multi-line input
                    while (line != null && line.endsWith("\\")) {
                        // Remove trailing backslash and add the line
                        multiLineInput.append(line, 0, line.length() - 1);
                        multiLineInput.append("\n");

                        // Read continuation line with secondary prompt
                        line = lineReader.readLine("... ");
                    }

                    // Add the last line (without backslash or the only line)
                    if (line != null) {
                        multiLineInput.append(line);
                    }

                    String input = multiLineInput.toString().trim();
                    if (input.isEmpty()) {
                        continue;
                    }

                    processInput(input);

                } catch (UserInterruptException e) {
                    // Ctrl+C pressed
                    formatter.displayInfo("\nUse '/quit' or '/exit' to exit the REPL");
                } catch (EndOfFileException e) {
                    // Ctrl+D pressed
                    break;
                }
            }
        } catch (IOException e) {
            formatter.displayError("Failed to initialize terminal: " + e.getMessage());
        } finally {
            this.activeTerminal = null;
            if (skillApprovalChannel != null) {
                skillApprovalChannel.unbindLineReader();
            }
        }

        formatter.displayGoodbye();
    }

    private void displayAgentInfo() {
        formatter.displayInfo("Working Directory: " + agentRuntime.getEnvironment().getWorkingDirectory());
        formatter.displayInfo("LLM Provider: " + agentExecutor.getLlmClient().getProviderName());
        formatter.displayInfo("Available tools: " + agentRuntime.getToolRegistry().size() + " tools(s)");
        formatter.displayInfo(
                "Available commands: " + agentRuntime.getCommandRegistry().getAllCommands().size() + " command(s)");
        formatter.displayInfo("Registered subagents: " + agentRuntime.getSubagentRegistry().getAllSubagents().size()
                + " subagent(s)");
        formatter.displayInfo(
                "Available skills: " + agentRuntime.getSkillRegistry().getAllSkills().size() + " skill(s)");

        System.out.println();
    }

    /**
     * Routes a raw input line to the appropriate handler.
     *
     * <p>
     * Package-private to let tests drive the REPL's core dispatch without wiring a real JLine terminal. Commands
     * ({@code /…}) go to {@link #handleCommand(String)}; prompts hand off to {@link #executeAgent(String)}, which in
     * turn consults {@link LiveSession#offerAsync} to decide between inline execution and auto-enqueue (SESSION-04).
     */
    void processInput(String input) {
        if (input.startsWith("/")) {
            handleCommand(input);
        } else {
            executeAgent(input);
        }
    }

    private void handleCommand(String command) {
        final String trimmed = command.trim();
        final String head = firstToken(trimmed).toLowerCase(Locale.ROOT);
        switch (head) {
            case "/quit", "/exit" -> {
                running = false;
            }
            case BUDGET_COMMAND -> handleBudgetCommand(trimmed);
            case RUNS_COMMAND -> handleRunsCommand(trimmed);
            case "/trace" -> handleTraceCommand();
            case RETRY_COMMAND -> handleRetryCommand();
            default ->
                // Delegate all other commands to CoreAgent
                // CoreAgent will handle /help, /clear, /version, and custom commands
                executeAgent(command);
        }
    }

    /**
     * Runs the last interrupted turn again, from where it originally started.
     *
     * <p>
     * Split into a rewind and an ordinary {@link #executeAgent(String)} rather than calling
     * {@link LiveSession#retryLastTurn()}, because a retried turn needs everything a first attempt gets: the streaming
     * event listener, and above all the Ctrl+C handler {@link #awaitAndRender} binds around the wait. This is the turn
     * the user just stopped, so being unable to stop it a second time would be the worst place to lose that.
     *
     * <p>
     * The empty case covers both "the last turn finished" and "there was never a turn", and says the same thing about
     * either, because to the user they are the same situation: there is nothing here to run again.
     */
    private void handleRetryCommand() {
        final Optional<RewoundTurn> rewound;
        try {
            rewound = liveSession.rewindLastTurn();
        } catch (UnsupportedOperationException e) {
            formatter.displayInfo("This session does not support retrying.");
            return;
        } catch (Exception e) {
            formatter.displayError("Retry failed: " + e.getMessage());
            return;
        }

        if (rewound.isEmpty()) {
            formatter.displayInfo("Nothing to retry — the last turn was not interrupted.");
            return;
        }

        // asText() only to echo the line. What is submitted is the turn itself — its input and the options it was
        // submitted under — so a turn this REPL could not have typed, an image sent through the SDK against the same
        // session under some principal, replays as that turn rather than as a placeholder describing it.
        final RewoundTurn turn = rewound.get();
        formatter.displayInfo("[retrying] " + turn.getUserInput().asText());
        executeAgent(turn.getUserInput(), turn.getSubmitOptions());
    }

    /**
     * TRACE-01: renders the most recent turn's span tree (TURN → ITERATION → LLM/TOOL) for the current session.
     * Only available when {@code cli.tracing} is enabled.
     */
    private void handleTraceCommand() {
        if (traceSpanStore.isEmpty()) {
            formatter.displayInfo("Tracing is disabled. Enable it with `cli.tracing: true` in your config.");
            return;
        }
        final TraceSpanStore store = traceSpanStore.get();
        final String sessionId = liveSession.getSessionId().value();
        final Optional<TraceSpan> latestTurn = store.bySession(sessionId).stream()
                .filter(s -> s.getType() == SpanType.TURN).max(Comparator.comparing(TraceSpan::getStartTime));
        if (latestTurn.isEmpty()) {
            formatter.displayInfo("No trace recorded yet for this session.");
            return;
        }
        formatter.displayInfo(renderTraceTree(store.byTrace(latestTurn.get().getTraceId())));
    }

    private static String renderTraceTree(List<TraceSpan> spans) {
        final Map<String, List<TraceSpan>> childrenByParent = new LinkedHashMap<>();
        TraceSpan root = null;
        for (final TraceSpan span : spans) {
            if (span.getParentSpanId().isEmpty()) {
                root = span;
            } else {
                childrenByParent.computeIfAbsent(span.getParentSpanId().get(), k -> new ArrayList<>()).add(span);
            }
        }
        if (root == null) {
            return "(no root span found for the latest trace)";
        }
        final StringBuilder sb = new StringBuilder("Trace ").append(root.getTraceId()).append('\n');
        appendSpan(sb, root, childrenByParent, "");
        return sb.toString();
    }

    private static void appendSpan(StringBuilder sb, TraceSpan span, Map<String, List<TraceSpan>> childrenByParent,
            String indent) {
        final String latency = span.latency().map(d -> d.toMillis() + "ms").orElse("...");
        sb.append(indent).append("• ").append(span.getType()).append(' ').append(span.getName()).append("  [")
                .append(span.getStatus()).append(", ").append(latency);
        span.getModel().ifPresent(m -> sb.append(", model=").append(m));
        span.getTokenUsage().ifPresent(t -> sb.append(", tokens=").append(t.getTotalTokens()));
        sb.append("]\n");
        // TRACE-02: when content capture is on, show a one-line preview of the captured tool/LLM content.
        appendContentPreview(sb, span, indent);
        final List<TraceSpan> children = new ArrayList<>(childrenByParent.getOrDefault(span.getSpanId(), List.of()));
        children.sort(Comparator.comparing(TraceSpan::getStartTime));
        for (final TraceSpan child : children) {
            appendSpan(sb, child, childrenByParent, indent + "  ");
        }
    }

    /**
     * TRACE-02: renders a single-line preview of captured content. Only TOOL ({@code content}) and LLM ({@code text})
     * spans carry it, and only when content capture is enabled; otherwise this is a no-op.
     */
    private static void appendContentPreview(StringBuilder sb, TraceSpan span, String indent) {
        if (span.getOutputs().orElse(null) instanceof Map<?, ?> outputs) {
            final Object content = outputs.containsKey("content") ? outputs.get("content") : outputs.get("text");
            if (content instanceof String s && !s.isBlank()) {
                final String oneLine = s.replaceAll("\\s+", " ").strip();
                final String preview = oneLine.length() > PREVIEW_MAX_CHARS
                        ? oneLine.substring(0, PREVIEW_MAX_CHARS) + "…"
                        : oneLine;
                sb.append(indent).append("    ↳ ").append(preview).append('\n');
            }
        }
    }

    private void handleBudgetCommand(String commandLine) {
        // Strip the leading command token to recover the arguments.
        final String remainder = commandLine.length() > BUDGET_COMMAND.length()
                ? commandLine.substring(BUDGET_COMMAND.length()).trim()
                : "";
        BudgetCommandHandler.Result result = budgetHandler.handle(remainder);
        if (result.isError()) {
            formatter.displayError(result.getMessage());
        } else {
            formatter.displayInfo(result.getMessage());
            // Propagate any mutation to the session default so the next submitAsync picks it up.
            syncSessionBudget();
        }
    }

    private void handleRunsCommand(String commandLine) {
        // Strip the leading command token to recover the sub-command + arguments.
        final String args = commandLine.length() > RUNS_COMMAND.length()
                ? commandLine.substring(RUNS_COMMAND.length()).trim()
                : "";
        formatter.displayInfo(runsHandler.handle(args));
    }

    /**
     * Reflects the current {@link BudgetCommandHandler} state into the backing {@link LiveSession}.
     *
     * <p>
     * Only {@link DefaultLiveSession} is mutable; alternative {@link LiveSession} implementations (test doubles,
     * future backends) are left untouched and simply operate on their construction-time budget. This is the interim
     * bridge SESSION-03 uses to keep {@code /budget} functional; SESSION-04 will fold the budget handling into the
     * session facade itself.
     */
    private void syncSessionBudget() {
        if (!(liveSession instanceof DefaultLiveSession defaultSession)) {
            return;
        }
        final ExecutionBudget budget = budgetHandler.getBudget().orElse(ExecutionBudget.unlimited());
        final LiveSessionOptions current = defaultSession.getOptions();
        defaultSession.setOptions(LiveSessionOptions.builder().budget(budget).locale(current.getLocale().orElse(null))
                .sourceAgentId(current.getSourceAgentId().orElse(null)).build());
    }

    private static String firstToken(String input) {
        int idx = 0;
        while (idx < input.length() && !Character.isWhitespace(input.charAt(idx))) {
            idx++;
        }
        return input.substring(0, idx);
    }

    /**
     * Runs a typed prompt, which is always text — the input loop's entry point into
     * {@link #executeAgent(UserInput)}, where everything this does is described.
     */
    private void executeAgent(String userMessage) {
        executeAgent(TextInput.of(userMessage), SubmitOptions.empty());
    }

    /**
     * Runs one user prompt through the streaming agent path (STREAM-04), or buffers it when the session is already
     * busy (SESSION-04).
     *
     * <p>
     * Takes a {@link UserInput} and its {@link SubmitOptions} rather than a {@code String} because {@code /retry}
     * replays whatever started the interrupted turn, and that turn need not have been started here — a session shared
     * with an SDK host can carry one begun by an image, submitted under a principal this REPL never sets. A prompt
     * typed here is always text with no per-turn options, and arrives through {@link #executeAgent(String)}.
     *
     * <p>
     * Dispatch is delegated to {@link LiveSession#offerAsync}: when the session is idle the outcome carries an
     * {@link SubmitOutcome.Kind#EXECUTED EXECUTED} stage that streams each
     * {@link at.aimon.core.agent.stream.AgentExecutionEvent AgentExecutionEvent} to the terminal via
     * {@link OutputFormatter#displayEvent}; when the session is busy and the shared {@link MessageQueueManager} is
     * wired, the session enqueues the prompt with {@code NEXT} priority and returns a
     * {@link SubmitOutcome.Kind#QUEUED QUEUED} outcome, which this method surfaces as {@code "[queued: N]"}. A
     * non-text input offered to a busy session is refused instead of queued — the queue carries text only — and the
     * {@code catch} below reports that like any other dispatch failure. The REPL runs one turn at a time, so it does
     * not arise here.
     *
     * <p>
     * <b>Ctrl+C handling:</b> while we block on the returned future we install a SIGINT handler on the active JLine
     * terminal. On signal we {@linkplain CompletableFuture#cancel(boolean) cancel} the future, which unblocks this
     * method with a {@link CancellationException}; the underlying Orca worker thread is a daemon and finishes its
     * in-flight iteration naturally — full cooperative interrupt propagation is tracked separately under the
     * "InterruptBehavior" follow-up noted in the STREAM-04 plan. If no terminal is bound (tests that call
     * {@link #processInput(String)} directly), the handler install is skipped.
     */
    private void executeAgent(UserInput userMessage, SubmitOptions submitOptions) {
        // Reset before every turn so the SIGINT handler only drops a turn that suspended in THIS interaction.
        latestPendingTurnId = null;
        final SubmitOutcome outcome;
        try {
            outcome = liveSession.offerAsync(userMessage, submitOptions, this::captureAndDispatchEvent);
        } catch (Exception e) {
            formatter.displayError("Agent execution failed: " + e.getMessage());
            return;
        }

        if (outcome.getKind() == SubmitOutcome.Kind.QUEUED) {
            // The session appended `userMessage` to the shared queue on our behalf; the Orca ReAct loop's mid-turn
            // drain (CQ-03) or our own turn-end drain (CQ-05) will replay it as soon as the current turn finishes.
            formatter.displayInfo("[queued: " + outcome.getQueuePosition() + "]");
            return;
        }

        final CompletableFuture<AgentExecutionResult> future = outcome.getResultStage()
                .orElseThrow(() -> new IllegalStateException("EXECUTED outcome must carry a result stage"))
                .toCompletableFuture();

        try {
            awaitAndRender(future);
        } catch (CancellationException e) {
            // Defensive: awaitAndRender already renders "[Aborted]" when join() throws, but if a cancellation bubbles
            // out of an unexpected path (future reshaping, interceptor chains) we still want the abort banner — not
            // a misleading "Agent execution failed: null".
            formatter.displayInfo("[Aborted]");
        } catch (Exception e) {
            formatter.displayError("Agent execution failed: " + e.getMessage());
        } finally {
            drainQueueAfterTurn();
        }
    }

    /**
     * Blocks until the streaming agent execution completes, routing the outcome to the same code paths the synchronous
     * implementation used: {@link OutputFormatter#displayResult} on normal completion,
     * {@link OutputFormatter#displayError}
     * on failure, and a dedicated "[Aborted]" info line on user-initiated cancellation. SIGINT handler setup/teardown
     * is paired around the wait so we never leave a stale handler after the turn.
     */
    private void awaitAndRender(CompletableFuture<AgentExecutionResult> future) {
        final Terminal terminal = this.activeTerminal;
        Terminal.SignalHandler previous = null;
        if (terminal != null) {
            previous = terminal.handle(Terminal.Signal.INT, sig -> requestInterruptWithFallback(future));
        }
        try {
            AgentExecutionResult result = future.join();
            formatter.displayResult(result);
        } catch (CancellationException e) {
            formatter.displayInfo("[Aborted]");
        } catch (CompletionException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            // When the session wraps the streaming stage (e.g., DefaultLiveSession#submitAsync's thenApply), an
            // upstream CancellationException is re-thrown as CompletionException(cause=CancellationException) instead
            // of the raw CancellationException. Treat that as user-initiated abort so the semantic matches the
            // pre-session direct-executor path.
            if (cause instanceof CancellationException) {
                formatter.displayInfo("[Aborted]");
            } else {
                log.warn("Agent execution failed: {}", cause.getMessage(), cause);
                formatter.displayError("Agent execution failed: " + cause.getMessage());
            }
        } finally {
            if (terminal != null) {
                // JLine's contract: handle() returns null when no explicit prior handler was registered (i.e. the
                // default SIG_DFL behaviour was in effect). Restoring null would leak our cancel handler into the
                // next turn and trip an unrelated future.cancel(true) on the next Ctrl+C, so fall back to SIG_DFL.
                terminal.handle(Terminal.Signal.INT, previous != null ? previous : Terminal.SignalHandler.SIG_DFL);
            }
        }
    }

    /**
     * SIGINT handler body: requests a cooperative interrupt first so tools that honour the
     * {@link at.aimon.core.agent.interrupt.CancellationSignal} can wind down with a meaningful "interrupted" result,
     * then schedules a {@link CompletableFuture#cancel(boolean)} fallback after {@link #SIGINT_FALLBACK_GRACE_MS}
     * milliseconds so users are never trapped behind a NON_INTERRUPTIBLE tool.
     *
     * <p>
     * The cooperative request is a best-effort call — {@link LiveSession#interrupt(InterruptReason)} is documented as
     * a silent no-op when no turn is active, and {@code DefaultLiveSession}'s override is bounded, but we still catch
     * runtime exceptions to protect the signal-handling thread from an unexpected bug in a custom {@code LiveSession}
     * implementation (the handler runs inside JLine's signal dispatch and any throw here would propagate as an
     * uncaught exception on an infrastructure thread).
     *
     * <p>
     * The fallback is idempotent: {@code CompletableFuture.cancel} is a no-op if the future already completed via the
     * cooperative path, so a successful interrupt simply makes the scheduled cancel call a harmless observation.
     *
     * <p>
     * Package-private so the unit test can invoke it directly without standing up a real JLine terminal — the
     * production call site is the lambda inside {@link #awaitAndRender(CompletableFuture)}, not an external caller.
     */
    void requestInterruptWithFallback(CompletableFuture<AgentExecutionResult> future) {
        try {
            liveSession.interrupt(InterruptReason.USER_SIGINT);
        } catch (RuntimeException e) {
            log.warn("Cooperative interrupt request failed; falling back to hard cancel: {}", e.getMessage(), e);
        }
        dropPendingTurnIfAny();
        CompletableFuture.delayedExecutor(SIGINT_FALLBACK_GRACE_MS, TimeUnit.MILLISECONDS).execute(() -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
    }

    /**
     * Best-effort eviction of the pending turn that was registered during the in-flight turn. Called
     * from the SIGINT path so that a turn the user just observed suspending — but is now choosing to abort with
     * Ctrl+C — does not linger in the registry until the timeout reaper sweeps it. No-op when nothing has suspended in
     * this turn or when the registry is not wired.
     *
     * <p>
     * Runs on the JLine signal-dispatch thread; runtime exceptions are caught so a registry bug cannot leak to the
     * signal infrastructure.
     */
    private void dropPendingTurnIfAny() {
        final PendingTurnId id = latestPendingTurnId;
        if (id == null || pendingTurnRegistry == null) {
            return;
        }
        try {
            pendingTurnRegistry.remove(id)
                    .ifPresent(turn -> log.debug("SIGINT dropped pending turn {} from registry", turn.getId().value()));
        } catch (RuntimeException e) {
            log.warn("Failed to drop pending turn {} on SIGINT: {}", id.value(), e.getMessage(), e);
        } finally {
            latestPendingTurnId = null;
        }
    }

    /**
     * Event-consumer wrapper that captures the {@link PendingTurnId} from a {@link SkillTurnSuspendedEvent} into
     * {@link #latestPendingTurnId} before forwarding the event to {@link OutputFormatter#displayEvent}. The capture
     * happens here (rather than inside the formatter) so the side-effect lives next to the SIGINT handler that consumes
     * the field — keeping the rendering layer pure.
     *
     * <p>
     * Package-private so the SK-11.5 SIGINT-cleanup test can drive the consumer directly without standing up the
     * full agent loop, mirroring how {@link #requestInterruptWithFallback} is exposed for its test.
     */
    void captureAndDispatchEvent(AgentExecutionEvent event) {
        if (event instanceof SkillTurnSuspendedEvent suspended) {
            latestPendingTurnId = suspended.getPendingTurnId();
        }
        formatter.displayEvent(event);
    }

    /**
     * Consumes queue entries left for this agent runtime after a turn boundary (CQ-05).
     *
     * <p>
     * Called from {@link #executeAgent(String)}'s {@code finally} block so the slot is already released. Anything the
     * Orca ReAct loop could not inject mid-turn — either enqueued too late in the turn or parked at a priority the
     * mid-turn drain skipped — is replayed here in FIFO order. Each entry round-trips through
     * {@link #processInput(String)} so that slash commands take the command path and prompts take the agent path,
     * preserving the semantic boundary between the two.
     *
     * <p>
     * {@link #draining} prevents the replay from calling itself: each replayed prompt will re-enter
     * {@code executeAgent} → {@code finally} → {@code drainQueueAfterTurn}, but the outer call already holds the
     * batch in {@code drained}, so the nested call returns immediately and lets the outer loop continue.
     */
    private void drainQueueAfterTurn() {
        if (draining) {
            return;
        }
        draining = true;
        try {
            final List<QueuedInput> drained = messageQueueManager.drainForInjection(
                    q -> agentRuntime.getId().equals(q.getAgentRuntimeId()), QueuedInputPriority.LATER);
            for (QueuedInput entry : drained) {
                processInput(entry.getInputText());
            }
        } finally {
            draining = false;
        }
    }

    /** REPL 세션을 중지한다. */
    public void stop() {
        running = false;
    }

    /** Package-private accessor for tests. */
    MessageQueueManager getMessageQueueManager() {
        return messageQueueManager;
    }
}
