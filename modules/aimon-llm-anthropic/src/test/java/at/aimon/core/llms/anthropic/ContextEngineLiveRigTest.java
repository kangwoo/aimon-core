package at.aimon.core.llms.anthropic;

import static at.aimon.core.llms.anthropic.ContextEngineLiveRig.VAULT_CODE;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.compact.CompactionKind;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.tools.session.SessionHistoryTool;

/**
 * The live scenario's mechanics without a provider: {@link ContextEngineLiveRig} driven by a scripted client that
 * plays the model's part the way a cooperative model would.
 *
 * <p>
 * {@link AnthropicContextEngineLiveTest} runs only with a key, so without this class nothing in a keyless build would
 * notice the rig's thresholds drifting to where the filler turns no longer reach a rolling cycle, or sealing no longer
 * happening — the live test would then report its "rolling cycles" failure only on the day someone pays to run it. The
 * scripted summary deliberately drops the planted fact, so the fact can come back only through {@code SessionHistory}
 * reading a range sealed out of the record: the harder of the two recovery paths the live test accepts.
 */
@DisplayName("ContextEngineLiveRig - the live scenario against a scripted model")
class ContextEngineLiveRigTest {

    private static final Pattern CODE = Pattern.compile("ZEBRA-\\d+");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("the filler turns reach two rolling cycles, the fact is sealed away and read back, and a reload holds")
    void theRollingScenarioRunsToTheEnd() {
        final ScriptedModel model = new ScriptedModel();
        final ContextEngineLiveRig rig = ContextEngineLiveRig.rolling(model, LlmModel.builder().build(),
                tempDir.resolve("first"));

        final OrcaAgentExecutionResult planted = rig.turn("Call the fetch_report tool with report_id \"R-1\".");
        assertThat(ContextEngineLiveRig.calledTheReportTool(planted)).isTrue();
        final long plantedSeq = ContextEngineLiveRig
                .seqOfToolResultContaining(planted.getSnapshot().getLogState(), VAULT_CODE).orElseThrow();

        for (int n = 1; n <= ContextEngineLiveRig.MAX_FILLER_TURNS
                && rig.compactionsOfKind(CompactionKind.ROLLING) < 2; n++) {
            rig.turn(ContextEngineLiveRig.fillerNote(n));
        }

        assertThat(rig.compactionsOfKind(CompactionKind.ROLLING)).isGreaterThanOrEqualTo(2);
        assertThat(rig.summaries().failures()).isEmpty();
        assertThat(rig.summaries().lastRoles()).isNotEmpty().allMatch(role -> role == Role.USER || role == Role.TOOL);
        final SessionLogState log = rig.storedLog();
        assertThat(log.getViewState().hidesOriginal(plantedSeq)).isTrue();
        assertThat(log.getManifest()).isNotEmpty();
        assertThat(rig.storedView()).noneMatch(message -> textOf(message).contains(VAULT_CODE));

        final OrcaAgentExecutionResult asked = rig.turn("What was the vault access code in report R-1?");
        assertThat(asked.getFinalAnswer()).contains(VAULT_CODE);
        assertThat(model.historyCalls).hasValue(1);

        final List<Message> viewBefore = rig.storedView();
        final ContextEngineLiveRig reloaded = rig.reloadThroughCodec(tempDir.resolve("second"));
        assertThat(reloaded.storedView()).isEqualTo(viewBefore);
        assertThat(reloaded.turn("Reply with the vault access code once more.").getFinalAnswer()).contains(VAULT_CODE);
    }

    @Test
    @DisplayName("the default engine in view mode summarizes on demand without shortening the log")
    void theViewModeScenarioRunsToTheEnd() {
        final ContextEngineLiveRig rig = ContextEngineLiveRig.defaultViewMode(new ScriptedModel(),
                LlmModel.builder().build(), tempDir);
        rig.turn("Remember this codeword for later: PELICAN.");
        rig.turn(ContextEngineLiveRig.fillerNote(1));
        final int entriesBefore = ContextEngineLiveRig.loggedEntryCount(rig.storedLog());

        final CompactionResult compacted = rig.compactNow();

        assertThat(compacted.isSuccess()).isTrue();
        assertThat(rig.storedLog().getViewState().getSummarySpan()).isPresent();
        assertThat(ContextEngineLiveRig.loggedEntryCount(rig.storedLog())).isEqualTo(entriesBefore);
        assertThat(rig.summaries().count()).isEqualTo(1);
        // The view ends on the assistant's "noted"; sent as it is, the request is a prefill of a finished answer, which
        // Anthropic answers with no content blocks. The compaction engine closes it on the user side.
        assertThat(rig.summaries().lastRoles()).as("the role the forced summary request ends on")
                .containsExactly(Role.USER);
        rig.turn("What was the codeword?");
    }

    /** A message's text and its tool results' text: what the model reads of it. */
    private static String textOf(Message message) {
        final StringBuilder text = new StringBuilder(String.valueOf(message.getContent()));
        message.getToolUseResults().forEach(result -> text.append('\n').append(result.getContent()));
        return text.toString();
    }

    /**
     * Plays the model: fetches the report when asked, answers filler with "noted", and, asked for the code, answers
     * from
     * the view when it can see it and otherwise searches {@code SessionHistory}. A call without tools is a summary call
     * and gets a summary that leaves the code out.
     */
    private static final class ScriptedModel implements LlmClient {

        private final AtomicInteger ids = new AtomicInteger();
        private final AtomicInteger historyCalls = new AtomicInteger();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            if (tools.isEmpty()) {
                return LlmResponse.text("Summary: the user stored several meeting notes and fetched report R-1.");
            }
            final Message last = messages.get(messages.size() - 1);
            if (last.hasToolResults()) {
                for (ToolUseResult result : last.getToolUseResults()) {
                    final Matcher found = CODE.matcher(String.valueOf(result.getContent()));
                    if (result.getToolUseId().startsWith("history-") && found.find()) {
                        return LlmResponse.text(found.group());
                    }
                }
                return LlmResponse.text("received");
            }
            final String text = String.valueOf(last.getContent());
            if (text.contains(ContextEngineLiveRig.REPORT_TOOL)) {
                return LlmResponse.tools(List.of(ToolUse.of("report-" + ids.incrementAndGet(),
                        ContextEngineLiveRig.REPORT_TOOL, Map.of("report_id", "R-1"))));
            }
            if (text.contains("vault access code") || text.contains("codeword?")) {
                for (Message message : messages) {
                    final Matcher visible = CODE.matcher(textOf(message));
                    if (visible.find()) {
                        return LlmResponse.text(visible.group());
                    }
                }
                if (text.contains("codeword?")) {
                    return LlmResponse.text("PELICAN");
                }
                historyCalls.incrementAndGet();
                return LlmResponse.tools(List.of(ToolUse.of("history-" + ids.incrementAndGet(),
                        SessionHistoryTool.TOOL_NAME, Map.of("query", "vault access code"))));
            }
            return LlmResponse.text("noted");
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }
}
