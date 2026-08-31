package at.aimon.sample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * A model that asks for one named tool and then reports what came back.
 *
 * <p>
 * The live profile exists to answer a question the packaging tier cannot: does aimon-core still <em>work</em>
 * when it is assembled by the Spring Boot starter rather than by the CLI? Tool execution, the skill approval
 * chain and the scheduler are the three parts where the two assemblies genuinely differ — the starter's server
 * defaults leave the shell off, approvals at {@code DENY} and the scheduling backend at {@code none}, precisely
 * so that a server does not inherit a developer laptop's permissions. A configuration that is never exercised is
 * a configuration nobody knows the shape of, so this profile turns each of the three on and drives it.
 *
 * <p>
 * Driving them needs a model that emits {@code tool_use}, and the obvious way to get one — call a real provider —
 * would put a credential and a network round trip between the test and its subject. So the tool call is chosen
 * here instead, from a directive in the user input: {@code bash: uname -a} asks for {@code Bash},
 * {@code skill: alpha-notes} asks for {@code Skill}, and so on. The model is picking the tool the way a real one
 * would; it is only the <em>reasoning</em> that has been replaced by a prefix, and reasoning is not what a server
 * assembly can break.
 *
 * <p>
 * One tool call, then an answer. The second call arrives with the result attached, and the answer repeats that
 * result verbatim so an HTTP caller reads what the tool actually did — including its failures, which under the
 * approval axis are the interesting half. Input that matches no directive is answered directly, so a live-profile
 * turn can still be run without touching any of this.
 *
 * <p>
 * Thread-safe: the recording is a volatile reference to an immutable list and the call counter is atomic. The
 * multi-session axis runs turns concurrently, so this is load-bearing rather than defensive. Which of several
 * concurrent turns the recording ends up describing is deliberately unspecified — every turn is shown the same
 * catalog, which is the only thing the endpoint reading it asks about.
 */
public class ScenarioLlmClient implements RecordingLlmClient {

    /** Prefix of the answer returned once a tool has reported back. */
    public static final String SCENARIO_ANSWER_PREFIX = "scenario complete";

    /** The answer for input that names no tool. */
    public static final String NO_DIRECTIVE_ANSWER = "no directive in that input; nothing was asked of a tool";

    /** Separates a directive's two arguments, as in {@code write: notes.txt :: hello}. */
    private static final String ARG_SEPARATOR = "::";

    // Only the most recent call is ever read, so only the most recent call is kept. The scripted client next door
    // accumulates every call because the packaging tier runs exactly one turn per JVM; this profile runs as many
    // as a human types, and a list nothing reads is a list that only grows.
    private volatile List<String> lastToolDefinitions = List.of();
    private final AtomicLong callCounter = new AtomicLong();

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        record(tools);

        // A result on the last message means this is the second pass of the same turn: the tool has run and there
        // is nothing left to ask for. Checking the message rather than counting calls keeps the client correct
        // when a hook or the executor inserts a pass of its own.
        final Message last = lastOf(messages);
        if (last != null && last.hasToolResults()) {
            return LlmResponse.text(summarize(last.getToolUseResults()));
        }

        final ToolUse call = chooseTool(last == null ? "" : last.getContent());
        if (call == null) {
            return LlmResponse.text(NO_DIRECTIVE_ANSWER);
        }
        return LlmResponse.tools(List.of(call));
    }

    @Override
    public String getProviderName() {
        return "scenario";
    }

    @Override
    public List<String> lastToolDefinitions() {
        return lastToolDefinitions;
    }

    /**
     * Picks the tool call a directive asks for.
     *
     * <p>
     * Returns {@code null} rather than throwing when nothing matches. A model that cannot parse its input answers
     * in words; it does not fail the turn, and a sample that behaved otherwise would report a typo as a framework
     * defect.
     */
    private ToolUse chooseTool(String input) {
        final String text = input == null ? "" : input.trim();
        final int colon = text.indexOf(':');
        final String directive = (colon < 0 ? text : text.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        final String argument = colon < 0 ? "" : text.substring(colon + 1).trim();

        return switch (directive) {
            case "bash" -> toolUse("Bash", Map.of("command", argument));
            case "read" -> toolUse("Read", Map.of("file_path", argument));
            case "write" -> toolUse("Write",
                    Map.of("file_path", before(argument), "content", after(argument, "written by the live profile")));
            case "skill" -> toolUse("Skill", Map.of("skill", before(argument), "args", after(argument, "")));
            case "schedule" -> toolUse("schedule_task", scheduleRequest(before(argument)));
            case "schedules" -> toolUse("list_scheduled_tasks", Map.of());
            default -> null;
        };
    }

    /**
     * Builds a {@code schedule_task} payload that writes a file every five minutes.
     *
     * <p>
     * The routine step matters more than the cron does. Registering a task proves the Quartz backend was selected
     * and the tool reached it; a step that names a real tool with real parameters is what proves the task carries
     * something a re-fire could actually run, on a runtime resolved from an agent-scoped id long after this
     * session is gone.
     */
    private Map<String, Object> scheduleRequest(String name) {
        final Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("type", "cron");
        schedule.put("cron_expression", "*/5 * * * *");
        schedule.put("timezone", "Asia/Seoul");

        final Map<String, Object> step = new LinkedHashMap<>();
        step.put("tool", "Write");
        step.put("tool_params", "{\"file_path\": \"scheduled-" + name + ".txt\", \"content\": \"fired\"}");

        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", name);
        request.put("description", "registered by the live profile to prove the scheduler is reachable");
        request.put("schedule", schedule);
        request.put("routine", List.of(step));
        return request;
    }

    private ToolUse toolUse(String name, Map<String, Object> input) {
        return ToolUse.of("scenario-" + callCounter.incrementAndGet(), name, input);
    }

    private String summarize(List<ToolUseResult> results) {
        final StringBuilder answer = new StringBuilder(SCENARIO_ANSWER_PREFIX);
        for (ToolUseResult result : results) {
            answer.append('\n').append(result.isError() ? "ERROR " : "OK ").append(result.getContent());
        }
        return answer.toString();
    }

    private void record(List<ToolDefinition> tools) {
        final List<String> rendered = new ArrayList<>();
        if (tools != null) {
            for (ToolDefinition tool : tools) {
                rendered.add(tool.getName() + "\n" + tool.getDescription());
            }
        }
        lastToolDefinitions = List.copyOf(rendered);
    }

    private Message lastOf(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return messages.get(messages.size() - 1);
    }

    private String before(String argument) {
        final int at = argument.indexOf(ARG_SEPARATOR);
        return (at < 0 ? argument : argument.substring(0, at)).trim();
    }

    private String after(String argument, String fallback) {
        final int at = argument.indexOf(ARG_SEPARATOR);
        return at < 0 ? fallback : argument.substring(at + ARG_SEPARATOR.length()).trim();
    }
}
