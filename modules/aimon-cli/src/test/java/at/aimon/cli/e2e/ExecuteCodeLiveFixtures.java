package at.aimon.cli.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.AimonStackBuilder;
import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.ExecutionEnvironmentSpec;
import at.aimon.bootstrap.spec.ExecutorSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ShellEngineToolScope;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.tools.ToolSpans;
import at.aimon.core.tools.code.CodeSpanAttributes;
import at.aimon.core.tools.code.ExecuteCodeLanguageNotice;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.TraceSpanStore;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;

/**
 * The one fixture the real-model {@code ExecuteCode} E2E and its offline check share: two host tools opened to code
 * only, an agent that can reach the answer only through {@code ExecuteCode}, the stack, the observation capture, the
 * assertions of design B.3.3 and the stray-process check. {@link ExecuteCodeLiveFixtureTest} drives it with a scripted
 * model in plain {@code test}, so a fixture regression fails there before any billed run.
 */
final class ExecuteCodeLiveFixtures {

    static final String STATUS_TOOL = "GetHostStatus";
    static final String LOAD_TOOL = "GetHostLoad";

    /** The fixed host table: name → (status, load). */
    static final Map<String, Object[]> HOSTS = Map.of("h1", new Object[]{"up", 0.42}, "h2", new Object[]{"down", 0.0},
            "h3", new Object[]{"up", 0.93}, "h4", new Object[]{"up", 0.15}, "h5", new Object[]{"down", 0.88});

    /** The hosts the prompt asks for: down, or load above 0.8. */
    static final Set<String> EXPECTED_ANSWER = Set.of("h2", "h3", "h5");

    static final String SYSTEM_PROMPT = "You are an operations assistant. Use the ExecuteCode tool to call the host "
            + "tools from a program; they are available only there.";

    static final String USER_PROMPT = "For each of the hosts h1, h2, h3, h4 and h5 get status and load, and print only "
            + "the names of hosts that are down or have load above 0.8, one per line.";

    private static final Pattern RAW_MARKER = Pattern.compile("RAW-[0-9a-f-]{36}");
    private static final int DETAILS_CHARS = 3_000;

    private ExecuteCodeLiveFixtures() {
    }

    /** The two engines, each with the language name its summary line prints. */
    enum Variant {
        JAVASCRIPT(CodeExecutionEngine.JAVASCRIPT, "JavaScript"), PYTHON(CodeExecutionEngine.PYTHON, "Python");

        final String language;
        final String displayName;

        Variant(String language, String displayName) {
            this.language = language;
            this.displayName = displayName;
        }

        /** The allow-list: bare names only; the shell engine also needs an unconstrained {@code Bash}. */
        List<String> allowedTools() {
            return this == JAVASCRIPT
                    ? List.of(ExecuteCodeTool.TOOL_NAME, STATUS_TOOL, LOAD_TOOL)
                    : List.of(ExecuteCodeTool.TOOL_NAME, STATUS_TOOL, LOAD_TOOL, "Bash");
        }
    }

    /**
     * Builds one stack around {@code client} for {@code variant}.
     *
     * @param client
     *            the model (real or scripted)
     * @param variant
     *            the engine variant
     * @param engine
     *            the engine, owned by the caller (the stack never closes engines it is handed)
     * @param workspace
     *            the workspace root (a {@code @TempDir})
     * @param recorder
     *            the run-directory recorder the status tool feeds (shell variant), or null
     * @return the fixture; close it to close the stack
     */
    static Fixture start(LlmClient client, Variant variant, CodeExecutionEngine engine, Path workspace,
            RunDirectoryRecorder recorder) {
        return new Fixture(client, variant, engine, workspace, recorder);
    }

    /** One assembled stack plus what it observed. */
    static final class Fixture implements AutoCloseable {

        final Variant variant;
        final AimonStack stack;
        final List<ToolUseResult> executeCodeResults = new CopyOnWriteArrayList<>();
        final List<TraceSpan> spans = new CopyOnWriteArrayList<>();
        final HostTool status;
        final HostTool load;

        private Fixture(LlmClient client, Variant variant, CodeExecutionEngine engine, Path workspace,
                RunDirectoryRecorder recorder) {
            this.variant = variant;
            this.status = new HostTool(STATUS_TOOL, recorder);
            this.load = new HostTool(LOAD_TOOL, null);
            final DefaultAgent agent = DefaultAgent
                    .builder().name("ptc-live-" + variant.language).systemPrompt(SYSTEM_PROMPT).maxIterations(8)
                    .tools(variant.allowedTools()).programmatic(ProgrammaticToolSettings.builder()
                            .tools(List.of(STATUS_TOOL, LOAD_TOOL)).codeOnly(List.of(STATUS_TOOL, LOAD_TOOL)).build())
                    .build();
            final PostToolHook capture = context -> {
                if (ExecuteCodeTool.TOOL_NAME.equals(context.getToolUse().getName())) {
                    executeCodeResults.add(context.getCurrentToolUseResult());
                }
                return HookResult.success();
            };
            final ToolSpec.Builder tools = ToolSpec.builder().codeExecutionEngines(List.of(engine))
                    .addProvider((registry, providerContext) -> {
                        registry.register(status);
                        registry.register(load);
                    });
            final AimonStackSpec.Builder spec = AimonStackSpec.builder().workspaceRoot(workspace.toString())
                    .llm(LlmSpec.of(client))
                    .agent(AgentSpec.builder().bundle(AgentBundle.builder().agent(agent).build())
                            .addCustomizer(
                                    runtime -> runtime.getHookRegistry().register(HookEventType.POST_TOOL, capture))
                            .build())
                    .executor(ExecutorSpec.builder()
                            .tracer(new DefaultTracer(new RecordingStore(spans), SpanExporter.noop())).build());
            if (variant == Variant.PYTHON) {
                // The host tools truthfully reach the host, beyond the default environment scope (review-1).
                tools.shellEngineToolScope(ShellEngineToolScope.HOST);
                final Path root = workspace.resolve("sandbox");
                root.toFile().mkdirs();
                spec.executionEnvironment(
                        ExecutionEnvironmentSpec.provider(() -> new SandboxedLocalTestEnvironmentProvider(root)));
            }
            this.stack = AimonStackBuilder.build(spec.tools(tools.build()).build());
        }

        OrcaAgentRuntime runtime() {
            return stack.runtime(stack.primaryRuntimeId()).orElseThrow();
        }

        /** Runs one turn with the fixed prompt. */
        OrcaAgentExecutionResult runTurn() {
            return stack.agentExecutor().execute(runtime(),
                    OrcaAgentExecutionRequest.builder().userInput(USER_PROMPT).sessionId(SessionId.generate()).build());
        }

        /**
         * The code-path TOOL spans of one program: parented to an ExecuteCode span and naming that program's
         * {@code tool_use} id as {@code ptc.parent_tool_use_id}.
         */
        List<TraceSpan> nestedToolSpans(String executeCodeToolUseId) {
            final Set<String> executeCodeSpans = spans.stream()
                    .filter(span -> span.getType() == SpanType.TOOL && ExecuteCodeTool.TOOL_NAME.equals(span.getName()))
                    .map(TraceSpan::getSpanId).collect(Collectors.toSet());
            return spans.stream()
                    .filter(span -> span.getType() == SpanType.TOOL
                            && ToolSpans.PATH_CODE.equals(span.getAttributes().get(ToolSpans.PATH))
                            && Objects.equals(executeCodeToolUseId,
                                    span.getAttributes().get(CodeSpanAttributes.PARENT_TOOL_USE_ID))
                            && span.getParentSpanId().filter(executeCodeSpans::contains).isPresent())
                    .toList();
        }

        @Override
        public void close() {
            stack.close();
        }
    }

    /** How many nested calls the programs made: the best single program's, and all of them together. */
    static final class NestedCounts {

        /** The successful nested calls of the best program that called both tools; 0 when none did. */
        final int bestProgram;
        /** The {@code tool_use} id of that program's {@code ExecuteCode} call, or null. */
        final String bestProgramId;
        /** Every dispatched (not refused) nested call, over all programs. */
        final int total;

        NestedCounts(int bestProgram, String bestProgramId, int total) {
            this.bestProgram = bestProgram;
            this.bestProgramId = bestProgramId;
            this.total = total;
        }
    }

    /**
     * Counts the nested calls per program. A call counts toward a program when it was dispatched (not refused) and its
     * tool answered without error; a program qualifies when those calls cover both host tools. Counting per program,
     * not over every {@code ExecuteCode} result together, is the point: two programs that each call one tool must not
     * add up to the property PTC exists for — one program calling several tools (design B.3.3 assertion 1, TASK item
     * 9).
     *
     * @param executeCodeResults
     *            the observed {@code ExecuteCode} results, in order
     * @return the counts (never null)
     */
    @SuppressWarnings("unchecked")
    static NestedCounts countNested(List<ToolUseResult> executeCodeResults) {
        int best = 0;
        String bestId = null;
        int total = 0;
        for (ToolUseResult call : executeCodeResults) {
            final Map<String, Object> payload = Optional.ofNullable(call.getRenderPayload()).orElse(Map.of());
            final List<Map<String, Object>> calls = (List<Map<String, Object>>) payload.getOrDefault("calls",
                    List.of());
            int succeeded = 0;
            final Set<String> covered = new LinkedHashSet<>();
            for (Map<String, Object> nested : calls) {
                if (nested.containsKey("refused")) {
                    continue;
                }
                total++;
                if (!Boolean.TRUE.equals(nested.get("error"))) {
                    succeeded++;
                    covered.add(String.valueOf(nested.get("name")));
                }
            }
            if (covered.contains(STATUS_TOOL) && covered.contains(LOAD_TOOL) && succeeded > best) {
                best = succeeded;
                bestId = call.getToolUseId();
            }
        }
        return new NestedCounts(best, bestId, total);
    }

    /**
     * The mechanical claims (design B.3.3, assertions 1 and 2): one program dispatched at least two nested calls that
     * covered both tools, and only what programs printed came back.
     *
     * @return the nested-call counts
     */
    static NestedCounts assertCoreClaims(Fixture fixture, OrcaAgentExecutionResult result) {
        final String diagnostics = diagnostics(fixture, result);
        assertThat(fixture.executeCodeResults).as("ExecuteCode was called%n%s", diagnostics).isNotEmpty();

        // 1. One program — not all of them added up — made >= 2 successful nested calls covering both tools, and the
        // trace shows them as code-path TOOL spans under that program's ExecuteCode span.
        final NestedCounts counts = countNested(fixture.executeCodeResults);
        assertThat(counts.bestProgram)
                .as("successful nested calls of one program that called both tools (all programs together: %d)%n%s",
                        counts.total, diagnostics)
                .isGreaterThanOrEqualTo(2);
        assertThat(fixture.nestedToolSpans(counts.bestProgramId))
                .as("TOOL spans with tool.path=code under the ExecuteCode span of %s%n%s", counts.bestProgramId,
                        diagnostics)
                .hasSizeGreaterThanOrEqualTo(2);

        // 2(a). No direct call of either host tool, and no nested result leaked as its own tool_result.
        for (Message message : result.getConversationHistory()) {
            for (ToolUse use : message.getToolUses()) {
                assertThat(use.getName()).as("direct tool_use in the conversation%n%s", diagnostics)
                        .isNotIn(STATUS_TOOL, LOAD_TOOL);
            }
            for (ToolUseResult toolResult : message.getToolUseResults()) {
                assertThat(toolResult.getToolUseId())
                        .as("a nested result leaked into the conversation%n%s", diagnostics).doesNotContain("#");
            }
        }
        for (ToolUseResult call : fixture.executeCodeResults) {
            final String content = call.getContent();
            // 2(b). The summary is the last line of the tool's own text and names this engine's language.
            assertThat(ExecuteCodeLanguageNotice.languageOf(content))
                    .as("summary line of%n%s%n%s", content, diagnostics).contains(fixture.variant.displayName);
            // 2(c). Every raw marker in the result is one the program itself printed or returned.
            final Map<String, Object> payload = Optional.ofNullable(call.getRenderPayload()).orElse(Map.of());
            final String produced = payload.getOrDefault("stdout", "") + "\n" + payload.getOrDefault("returnValue", "");
            assertThat(markers(content))
                    .as("raw tool output in the result that the program did not print%n%s", diagnostics)
                    .isSubsetOf(markers(produced));
            assertThat(content.length()).as("the result is smaller than the raw results it was computed from")
                    .isLessThan(DETAILS_CHARS * 2);
        }
        return counts;
    }

    /**
     * Runs one real-model turn and checks it, printing the {@code LIVE-E2E} result line whatever happens and the
     * diagnostics on failure. Never retries: a failure is reported, not re-rolled.
     *
     * @param provider
     *            {@code openai} or {@code anthropic}
     * @param variant
     *            the engine variant
     * @param engine
     *            the engine, owned by the caller
     * @param workspace
     *            the workspace root
     * @param recorder
     *            the run-directory recorder (shell variant), or null
     */
    static void runLive(String provider, Variant variant, CodeExecutionEngine engine, Path workspace,
            RunDirectoryRecorder recorder) throws Exception {
        final LlmClient client = LiveModels.client(provider);
        final String model = LiveModels.model(provider);
        Fixture fixture = null;
        OrcaAgentExecutionResult result = null;
        String outcome = "FAIL";
        try (Fixture started = start(client, variant, engine, workspace, recorder)) {
            fixture = started;
            result = started.runTurn();
            assertCoreClaims(started, result);
            assertAnswerReached(started, result);
            if (recorder != null) {
                assertThat(recorder.directories).as("run directories recorded while the runtime was alive")
                        .isNotEmpty();
            }
            outcome = "PASS";
        } catch (Throwable e) {
            if (fixture != null) {
                System.out.println(diagnostics(fixture, result));
            }
            throw e;
        } finally {
            if (fixture != null) {
                // What the model was shown, for judging model quality; small, and holds no key.
                for (int i = 0; i < fixture.executeCodeResults.size(); i++) {
                    final ToolUseResult r = fixture.executeCodeResults.get(i);
                    System.out
                            .println("LIVE-E2E program #" + (i + 1) + " (" + variant.language + ") " + programNumbers(r)
                                    + "\nLIVE-E2E ExecuteCode result:\n" + r.getContent() + "\nLIVE-E2E program:\n"
                                    + Optional.ofNullable(r.getRenderPayload()).map(p -> p.get("code")).orElse(""));
                }
            }
            System.out.println(resultLine(provider, model, variant, outcome, fixture, result));
            if (result != null) {
                System.out.println("LIVE-E2E final answer (" + variant.language + "): " + result.getFinalAnswer());
            }
            if (client instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
        if (recorder != null) {
            recorder.assertNoneLeft(Duration.ofSeconds(5));
        }
    }

    /**
     * Assertion 3: the turn reached the right answer — a program that completed printed exactly the expected hosts, or
     * the final answer names exactly them. The prompt asks the program to <i>print</i> the names, and a model that did
     * so often ends with "the names have been printed" without repeating them; judging only the final answer then
     * reported {@code answerMatches=false} for a correct run (both OpenAI models on JavaScript, 2026-11-07 and
     * 2026-11-10). The host table is fixed and the filter is one comparison per host, so a wrong set here is a wrong
     * run — data lost or garbled on its way through the nested calls, or a model that did not do the task — not
     * variance to log past.
     *
     * <p>
     * A program counts only when it ended {@code COMPLETED}: one that printed the right hosts and then hit
     * {@code SCRIPT_ERROR} did not do what it was written to do, and printing the names before the line that failed is
     * luck, not an answer.
     */
    static void assertAnswerReached(Fixture fixture, OrcaAgentExecutionResult result) {
        assertThat(answerReached(fixture.executeCodeResults, result)).as(
                "a program printed, or the final answer named, exactly %s (programs printed %s; final answer names"
                        + " %s)%n%s",
                sorted(EXPECTED_ANSWER), programPrinted(fixture.executeCodeResults),
                sorted(hostsNamed(finalAnswer(result))), diagnostics(fixture, result)).isTrue();
    }

    /** Whether a program that completed printed, or the final answer named, exactly the expected hosts. */
    static boolean answerReached(List<ToolUseResult> programs, OrcaAgentExecutionResult result) {
        return answerLooksRight(result) || programPrintedAnswer(programs);
    }

    /** Whether the final answer names exactly the expected hosts. */
    static boolean answerLooksRight(OrcaAgentExecutionResult result) {
        return hostsNamed(finalAnswer(result)).equals(EXPECTED_ANSWER);
    }

    /** Whether one program ended {@code COMPLETED} with stdout that names exactly the expected hosts. */
    static boolean programPrintedAnswer(List<ToolUseResult> programs) {
        return programs.stream()
                .anyMatch(program -> completed(program) && hostsNamed(stdoutOf(program)).equals(EXPECTED_ANSWER));
    }

    private static boolean completed(ToolUseResult program) {
        return "COMPLETED"
                .equals(String.valueOf(Optional.ofNullable(program.getRenderPayload()).orElse(Map.of()).get("status")));
    }

    /** The host names each program printed, in program order: {@code [[h2, h3, h5]]} for one correct program. */
    static List<List<String>> programPrinted(List<ToolUseResult> programs) {
        return programs.stream().map(program -> sorted(hostsNamed(stdoutOf(program)))).toList();
    }

    private static String finalAnswer(OrcaAgentExecutionResult result) {
        return result == null ? "" : Objects.requireNonNullElse(result.getFinalAnswer(), "");
    }

    private static String stdoutOf(ToolUseResult program) {
        return String
                .valueOf(Optional.ofNullable(program.getRenderPayload()).orElse(Map.of()).getOrDefault("stdout", ""));
    }

    private static Set<String> hostsNamed(String text) {
        final Set<String> named = new LinkedHashSet<>();
        final Matcher matcher = Pattern.compile("\\bh[1-5]\\b").matcher(text);
        while (matcher.find()) {
            named.add(matcher.group());
        }
        return named;
    }

    private static List<String> sorted(Set<String> hosts) {
        return hosts.stream().sorted().toList();
    }

    /** One program's nested calls that were dispatched (not refused) and answered without error. */
    @SuppressWarnings("unchecked")
    static int succeededCalls(ToolUseResult call) {
        final Map<String, Object> payload = Optional.ofNullable(call.getRenderPayload()).orElse(Map.of());
        final List<Map<String, Object>> calls = (List<Map<String, Object>>) payload.getOrDefault("calls", List.of());
        return (int) calls.stream()
                .filter(nested -> !nested.containsKey("refused") && !Boolean.TRUE.equals(nested.get("error"))).count();
    }

    /** One program's nested calls: dispatched (not refused), succeeded, and the tools those succeeded on. */
    @SuppressWarnings("unchecked")
    static String programNumbers(ToolUseResult call) {
        final Map<String, Object> payload = Optional.ofNullable(call.getRenderPayload()).orElse(Map.of());
        final List<Map<String, Object>> calls = (List<Map<String, Object>>) payload.getOrDefault("calls", List.of());
        int dispatched = 0;
        int succeeded = 0;
        final Set<String> tools = new LinkedHashSet<>();
        for (Map<String, Object> nested : calls) {
            if (!nested.containsKey("refused")) {
                dispatched++;
                if (!Boolean.TRUE.equals(nested.get("error"))) {
                    succeeded++;
                    tools.add(String.valueOf(nested.get("name")));
                }
            }
        }
        return "dispatched=" + dispatched + " succeeded=" + succeeded + " tools=" + tools + " status="
                + payload.getOrDefault("status", "?");
    }

    /**
     * The one-line outcome record; holds no key material. {@code nestedCallsBestProgram} is what assertion 1 judges —
     * the successful calls of the best single program that called both tools — and {@code nestedCallsAllPrograms} the
     * dispatched calls of every program together; {@code perProgram} lists each program's successful calls in order,
     * whichever tools they hit. {@code answer} is assertion 3's verdict; {@code programPrinted} (the hosts each program
     * printed) and {@code finalAnswerMatches} say which side carried it.
     */
    static String resultLine(String provider, String model, Variant variant, String outcome, Fixture fixture,
            OrcaAgentExecutionResult result) {
        final List<ToolUseResult> programs = fixture == null ? List.of() : fixture.executeCodeResults;
        final NestedCounts counts = countNested(programs);
        final String perProgram = programs.stream().map(ExecuteCodeLiveFixtures::succeededCalls).map(String::valueOf)
                .collect(Collectors.joining(","));
        final TokenUsage usage = result == null || result.getMetadata() == null
                ? null
                : result.getMetadata().getTokenUsage();
        final String tokens = usage == null || usage.isZero()
                ? "tokens=n/a"
                : "inputTokens=" + usage.getPromptTokens() + " outputTokens=" + usage.getCompletionTokens();
        return "LIVE-E2E provider=" + provider + " model=" + model + " engine=" + variant.language + " result="
                + outcome + " executeCodeCalls=" + programs.size() + " nestedCallsBestProgram=" + counts.bestProgram
                + " nestedCallsAllPrograms=" + counts.total + " perProgram=[" + perProgram + "] " + tokens
                + (result == null
                        ? ""
                        : " completion=" + result.getCompletionReason() + " answer="
                                + (answerReached(programs, result) ? "CORRECT" : "WRONG") + " programPrinted="
                                + programPrinted(programs) + " finalAnswerMatches=" + answerLooksRight(result));
    }

    /** The conversation, the render payloads and the span tree, printed on failure; none of them carries a key. */
    static String diagnostics(Fixture fixture, OrcaAgentExecutionResult result) {
        final StringBuilder out = new StringBuilder("--- conversation ---\n");
        if (result != null) {
            for (Message message : result.getConversationHistory()) {
                out.append('[').append(message.getRole()).append("] ").append(message.getContent()).append('\n');
                message.getToolUses().forEach(use -> out.append("  tool_use ").append(use.getName()).append(' ')
                        .append(use.getInput()).append('\n'));
                message.getToolUseResults().forEach(r -> out.append("  tool_result ").append(r.getToolUseId())
                        .append(": ").append(r.getContent()).append('\n'));
            }
            out.append("final: ").append(result.getFinalAnswer()).append('\n');
        }
        out.append("--- ExecuteCode payloads ---\n");
        fixture.executeCodeResults.forEach(r -> out.append(r.getRenderPayload()).append('\n'));
        out.append("--- spans ---\n");
        fixture.spans.forEach(span -> out.append(span.getType()).append(' ').append(span.getName()).append(" id=")
                .append(span.getSpanId()).append(" parent=").append(span.getParentSpanId().orElse("-")).append(' ')
                .append(span.getAttributes()).append('\n'));
        return out.toString();
    }

    private static Set<String> markers(String text) {
        final Set<String> found = new LinkedHashSet<>();
        final Matcher matcher = RAW_MARKER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /**
     * A host tool over the fixed table: read-only, reaching the host, callable from code. Each result carries a large
     * {@code details} field with a unique raw marker, so a result that leaked unprinted shows.
     */
    static final class HostTool implements Tool {

        private final String name;
        private final RunDirectoryRecorder recorder;
        final List<String> calls = new CopyOnWriteArrayList<>();

        HostTool(String name, RunDirectoryRecorder recorder) {
            this.name = name;
            this.recorder = recorder;
        }

        @Override
        public ToolDefinition getDefinition() {
            // The shape is spelled out since the answer is asserted: "the status as JSON" alone also reads as the
            // JSON string "down", and gpt-4o-mini compared the parsed object to "down" (2026-11-10).
            final String what = STATUS_TOOL.equals(name)
                    ? "the status of a host as a JSON object: {\"host\": \"h1\", \"status\": \"up\" or \"down\", "
                    : "the load of a host as a JSON object: {\"host\": \"h1\", \"load\": a number from 0.0 to 1.0, ";
            return ToolDefinition.of(name, "Returns " + what + "\"details\": a long diagnostic string}.",
                    Map.of("type", "object", "properties",
                            Map.of("host", Map.of("type", "string", "description", "the host name, e.g. h1")),
                            "required", List.of("host"), "additionalProperties", false));
        }

        @Override
        public SideEffectLevel getSideEffectLevel() {
            return SideEffectLevel.READ_ONLY;
        }

        @Override
        public DestructiveBehavior getDestructiveBehavior() {
            return DestructiveBehavior.NON_DESTRUCTIVE;
        }

        @Override
        public DataReach dataReach() {
            return DataReach.HOST;
        }

        @Override
        public boolean isCodeCallable() {
            return true;
        }

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.CONCURRENT_SAFE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            if (recorder != null) {
                recorder.capture();
            }
            final Object raw = input.toMap().get("host");
            final String host = raw == null ? "" : raw.toString();
            calls.add(host);
            final Object[] row = HOSTS.get(host);
            if (row == null) {
                return ToolResult.error("unknown host '" + host + "'; known hosts are h1..h5");
            }
            final String marker = "RAW-" + UUID.randomUUID();
            final String details = marker + " " + "x".repeat(DETAILS_CHARS);
            final String value = STATUS_TOOL.equals(name) ? "\"status\": \"" + row[0] + "\"" : "\"load\": " + row[1];
            return ToolResult.success("{\"host\": \"" + host + "\", " + value + ", \"details\": \"" + details + "\"}");
        }
    }

    /**
     * The run directories of the shell engine's Python runtime that actually ran, captured from the live processes
     * while a nested call is pending (the runtime blocks on the reply), and checked after the stack closes (design
     * B.3.3). {@code createTempDirectory} never reuses a name, so a pattern cannot match another run's runtime.
     *
     * <p>
     * Limit: a subprocess the program starts with its own {@code exec} has its own argv and is not matched; Q-C4 kills
     * it with the runtime's process group, and only the run-level {@code pgrep -f aimon_ptc.py} covers it.
     */
    static final class RunDirectoryRecorder {

        private static final Pattern RUN_DIRECTORY = Pattern.compile("(\\S*/aimon-run-[^/\\s]+)/aimon_ptc\\.py");

        final Set<String> directories = ConcurrentHashMap.newKeySet();
        final Set<Long> pids = ConcurrentHashMap.newKeySet();

        /** Records every JVM-descendant process whose command line runs {@code aimon_ptc.py} from a run directory. */
        void capture() {
            ProcessHandle.current().descendants().forEach(process -> {
                final String commandLine = process.info().commandLine().orElseGet(() -> psArgs(process.pid()));
                final Matcher matcher = RUN_DIRECTORY.matcher(commandLine);
                if (matcher.find()) {
                    directories.add(matcher.group(1));
                    pids.add(process.pid());
                }
            });
        }

        /** Asserts no recorded runtime is left, polling for up to {@code within} for kill latency. */
        void assertNoneLeft(Duration within) throws InterruptedException, IOException {
            final long deadline = System.nanoTime() + within.toNanos();
            List<String> left = leftovers();
            while (!left.isEmpty() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(100);
                left = leftovers();
            }
            assertThat(left).as("aimon_ptc.py processes left behind after the stack closed").isEmpty();
        }

        private List<String> leftovers() throws IOException, InterruptedException {
            final List<String> left = new ArrayList<>();
            for (String directory : directories) {
                final Process pgrep = new ProcessBuilder("pgrep", "-f", ereEscape(directory)).redirectErrorStream(true)
                        .start();
                final String out = new String(pgrep.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (pgrep.waitFor() != 1) {
                    left.add(directory + " -> pids " + out);
                }
            }
            for (Long pid : pids) {
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    left.add("pid " + pid);
                }
            }
            return left;
        }

        private static String ereEscape(String value) {
            return value.replaceAll("([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1");
        }

        private static String psArgs(long pid) {
            try {
                final Process ps = new ProcessBuilder("ps", "-ww", "-o", "args=", "-p", Long.toString(pid)).start();
                final String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                ps.waitFor(5, TimeUnit.SECONDS);
                return out;
            } catch (IOException e) {
                return "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
    }

    /** Whether {@code python3} ≥ 3.8 is on the path (the shell engine's runtime requirement). */
    static boolean python3Available() {
        try {
            final Process process = new ProcessBuilder("python3", "-c",
                    "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)").redirectErrorStream(true).start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A span store that keeps every span, so the test can walk the tree without knowing trace or session ids. */
    private static final class RecordingStore implements TraceSpanStore {

        private final Collection<TraceSpan> spans;
        private final InMemoryTraceSpanStore delegate = new InMemoryTraceSpanStore();

        RecordingStore(Collection<TraceSpan> spans) {
            this.spans = spans;
        }

        @Override
        public void record(TraceSpan span) {
            spans.add(span);
            delegate.record(span);
        }

        @Override
        public Optional<TraceSpan> get(String spanId) {
            return delegate.get(spanId);
        }

        @Override
        public List<TraceSpan> byTrace(String traceId) {
            return delegate.byTrace(traceId);
        }

        @Override
        public List<TraceSpan> bySession(String sessionId) {
            return delegate.bySession(sessionId);
        }

        @Override
        public void deleteOlderThan(java.time.Instant cutoff) {
            delegate.deleteOlderThan(cutoff);
        }
    }
}
