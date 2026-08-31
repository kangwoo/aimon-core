package at.aimon.sample;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;

/**
 * A model that answers in one turn and remembers the tool definitions it was shown.
 *
 * <p>
 * The recording is not a convenience — it is the strongest assertion the sample can make. Bundled skills do not
 * reach the model through the system prompt; they reach it through the {@code Skill} tool's description, which
 * is rebuilt from the live registry on every call. So a description that names both {@code alpha-notes} and
 * {@code beta-notes} is proof that both dependency jars were read, that both survived materialization, and that
 * the result got as far as the wire. A skill registry queried directly over HTTP proves only the first of those
 * three.
 *
 * <p>
 * Scripting the answer also removes the last reason this sample would need a credential: with
 * {@code aimon.llm.provider=none} the starter backs off and takes this bean instead, so a packaging test runs a
 * real turn through the real executor and never leaves the machine.
 *
 * <p>
 * It answers in one pass and never asks for a tool, which is what keeps the packaging assertion about packaging:
 * the only thing that can make this turn fail is a skill that did not survive the jar. Driving tools is the live
 * profile's job and belongs to {@link ScenarioLlmClient}, which is a different bean under a different profile so
 * that neither scenario can move the other's result.
 *
 * <p>
 * Thread-safe: the recordings are held in copy-on-write lists, and a turn may run on any request thread.
 */
public class ScriptedLlmClient implements RecordingLlmClient {

    /** The canned answer, asserted verbatim by the packaging tests. */
    public static final String ANSWER = "sample agent answered without leaving the machine";

    private final List<List<String>> toolDefinitionsSeen = new CopyOnWriteArrayList<>();

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        final List<String> rendered = new ArrayList<>();
        if (tools != null) {
            for (ToolDefinition tool : tools) {
                rendered.add(tool.getName() + "\n" + tool.getDescription());
            }
        }
        toolDefinitionsSeen.add(List.copyOf(rendered));
        return LlmResponse.text(ANSWER);
    }

    @Override
    public String getProviderName() {
        return "scripted";
    }

    @Override
    public List<String> lastToolDefinitions() {
        if (toolDefinitionsSeen.isEmpty()) {
            return List.of();
        }
        return toolDefinitionsSeen.get(toolDefinitionsSeen.size() - 1);
    }
}
