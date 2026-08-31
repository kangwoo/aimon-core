package at.aimon.core.agent.impl.orca.it;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.todo.TodoWriteTool;

/**
 * Reports the identity-bearing entries of the {@link ToolContext} it is called with, as its observation.
 *
 * <p>
 * <b>Why this exists.</b> Several isolation properties this suite must prove are only visible from inside a tool call:
 * which todo bucket a turn writes to is chosen by {@link TodoWriteTool#CONTEXT_ID_KEY}, and a fork's identity is
 * expressed by the <em>absence</em> of {@link ToolContextKeys#SESSION_ID} together with the presence of
 * {@link ToolContextKeys#EXECUTION_ID}. No production tool surfaces any of that — {@code TodoWrite} is write-only (no
 * read tool, no prompt re-injection, private repository), so a test asserting "session A's todos are invisible to
 * session B" through production tools alone would be asserting nothing.
 *
 * <p>
 * <b>What keeps it honest.</b> It is registered <em>alongside</em> the production tools, never instead of one (see
 * {@code OrcaRuntimeItSupport.Options#extraToolProvider}), and it reads the context the executor actually built. It
 * observes; it does not participate. If the executor stops populating a key, this tool reports {@link #ABSENT} and the
 * test fails — which is the point.
 *
 * <p>
 * Observation format is one {@code key=value} per line, in a fixed order, so assertions can match exact lines:
 *
 * <pre>
 * conversationId=it-1
 * invokingConversationId=&lt;absent&gt;
 * executionId=&lt;absent&gt;
 * agentRuntimeId=agent:agent-a
 * todo_write.context_id=it-1
 * </pre>
 */
final class ContextProbeTool extends AbstractTool {

    static final String TOOL_NAME = "ContextProbe";

    /** Rendered for a key the executor did not populate. Angle brackets cannot collide with a real id. */
    static final String ABSENT = "<absent>";

    ContextProbeTool() {
        super(TOOL_NAME,
                "Integration-test probe. Reports the identity keys of the current tool context, one key=value per "
                        + "line. Takes no parameters.",
                Map.of("type", "object", "properties", Map.of()));
    }

    /** A provider that registers only this probe — append it to the default providers, never in place of them. */
    static OrcaToolProvider provider() {
        return new ContextProbeToolProvider();
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(context, "context must not be null");

        // LinkedHashMap: the report order is part of the contract this tool's javadoc states.
        final Map<String, String> report = new LinkedHashMap<>();
        report.put(ToolContextKeys.SESSION_ID.name(), render(context, ToolContextKeys.SESSION_ID, SessionId::value));
        report.put(ToolContextKeys.INVOKING_SESSION_ID.name(),
                render(context, ToolContextKeys.INVOKING_SESSION_ID, SessionId::value));
        report.put(ToolContextKeys.EXECUTION_ID.name(),
                render(context, ToolContextKeys.EXECUTION_ID, ExecutionId::value));
        report.put(ToolContextKeys.AGENT_RUNTIME_ID.name(),
                render(context, ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId::value));
        report.put(TodoWriteTool.CONTEXT_ID_KEY.name(),
                render(context, TodoWriteTool.CONTEXT_ID_KEY, Function.identity()));

        final StringBuilder out = new StringBuilder();
        report.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        return ToolResult.success(out.toString().stripTrailing());
    }

    private static <T> String render(ToolContext context, ToolContextKey<T> key, Function<T, String> toText) {
        return context.get(key).map(toText).orElse(ABSENT);
    }

    /**
     * Reads one {@code key=value} line out of a probe observation.
     *
     * @return the value, or {@link #ABSENT} when the key is reported absent
     * @throws AssertionError
     *             when the observation does not carry the key at all — that means the probe did not run, which a test
     *             must not mistake for "the key was absent from the context"
     */
    static String valueOf(String observation, String key) {
        for (String line : observation.split("\n")) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
        }
        throw new AssertionError("probe observation does not contain key '" + key + "': " + observation);
    }

    /** Convenience for the common assertion "this key is present" — returns the value and fails when absent. */
    static String presentValueOf(String observation, String key) {
        final String value = valueOf(observation, key);
        if (ABSENT.equals(value)) {
            throw new AssertionError("expected context key '" + key + "' to be present, but it was absent");
        }
        return value;
    }

    private static final class ContextProbeToolProvider implements OrcaToolProvider {

        @Override
        public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
            Objects.requireNonNull(registry, "registry must not be null");
            Objects.requireNonNull(context, "context must not be null");
            registry.register(new ContextProbeTool());
        }
    }
}
