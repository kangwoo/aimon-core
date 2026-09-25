package at.aimon.core.llms.openai;

import static at.aimon.core.llms.openai.ContextEngineLiveRig.VAULT_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.compact.CompactionKind;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;

/**
 * The rolling context engine against the real OpenAI API: what a scripted client in {@code aimon-core} cannot
 * establish.
 *
 * <p>
 * Every rolling test in {@code aimon-core} answers its summary calls with a canned string, so none of them can show
 * that
 * the requests the engine builds — the summary call, and the next turn's view with a summary span in it — are ones the
 * provider accepts, or that a real model that has lost a fact from its view gets it back, from the summary or by
 * calling
 * {@code SessionHistory} over a range sealed out of the record. This class runs the scenario of
 * {@code AnthropicContextEngineLiveTest} on Chat Completions; the Anthropic class also covers extended thinking and the
 * default engine's view mode, which are not repeated here.
 *
 * <p>
 * <strong>How.</strong> The real executor path, a version-2 transcript sealed into an in-memory segment store, and a
 * rolling engine with a small window so a cycle comes every few turns — see {@link ContextEngineLiveRig}. A fact is
 * planted in a large tool result on the first turn, prose filler turns push the session through two rolling cycles, the
 * fact is asked for, and the session is read back through the version-2 codec into a fresh record store and continued
 * for one more turn.
 *
 * <p>
 * <strong>Cost.</strong> About 15 billed calls on {@code gpt-4o-mini}, well under 30,000 tokens. Whether the model
 * calls
 * the report tool on the first turn is the model's decision, so its absence aborts through {@code assumeTrue} rather
 * than failing; everything after that point is red when it goes wrong.
 *
 * <p>
 * Gated on {@code OPENAI_KEY} like {@link OpenAILlmClientIntegrationTest}, so a keyless build — CI included — skips it.
 */
@DisplayName("Rolling context engine against the real OpenAI API")
@EnabledIfEnvironmentVariable(named = "OPENAI_KEY", matches = ".+")
class OpenAIContextEngineLiveTest {

    /** The model {@link OpenAILlmClientIntegrationTest} pins ordinary behaviour on: cheap, on Chat Completions. */
    private static final String MODEL = "gpt-4o-mini";

    private static final String PLANT = "Call the " + ContextEngineLiveRig.REPORT_TOOL
            + " tool with report_id \"R-1\". After reading it, reply with only the word: received.";

    private static final String ASK = "Earlier, report R-1 contained a vault access code. What is it? If it is no"
            + " longer shown to you, use the SessionHistory tool to search for it. Reply with the code only.";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("rolling: a pruned fact survives several cycles, and the session survives a codec round trip")
    void rollingRecoversAPrunedFactAndSurvivesAReload() {
        final OpenAILlmClient client = new OpenAILlmClient(
                OpenAIConfig.builder().apiKey(System.getenv("OPENAI_KEY")).model(MODEL).maxTokens(1000).build());
        final ContextEngineLiveRig rig = ContextEngineLiveRig.rolling(client, LlmModel.builder().build(),
                tempDir.resolve("first"));

        final OrcaAgentExecutionResult planted = rig.turn(PLANT);
        assumeTrue(ContextEngineLiveRig.calledTheReportTool(planted), "model did not fetch the report");
        final long plantedSeq = ContextEngineLiveRig
                .seqOfToolResultContaining(planted.getSnapshot().getLogState(), VAULT_CODE)
                .orElseThrow(() -> new AssertionError("the report's tool result is not in the log"));

        for (int n = 1; n <= ContextEngineLiveRig.MAX_FILLER_TURNS
                && rig.compactionsOfKind(CompactionKind.ROLLING) < 2; n++) {
            rig.turn(ContextEngineLiveRig.fillerNote(n));
        }

        assertThat(rig.compactionsOfKind(CompactionKind.ROLLING)).as("rolling cycles").isGreaterThanOrEqualTo(2);
        assertEverySummaryAccepted(rig);

        final SessionLogState log = rig.storedLog();
        assertThat(log.getViewState().hidesOriginal(plantedSeq)
                || log.getViewState().getElisions().containsKey(plantedSeq))
                .as("the report's tool result is no longer in the view verbatim").isTrue();
        assertThat(log.getManifest()).as("a hidden range was sealed out of the record").isNotEmpty();

        final OrcaAgentExecutionResult asked = rig.turn(ASK);
        assertThat(asked.getFinalAnswer()).as("the planted fact is recovered").contains(VAULT_CODE);

        final List<Message> viewBefore = rig.storedView();
        final ContextEngineLiveRig reloaded = rig.reloadThroughCodec(tempDir.resolve("second"));
        assertThat(reloaded.storedView()).as("the reloaded session projects the same view").isEqualTo(viewBefore);

        final OrcaAgentExecutionResult continued = reloaded
                .turn("Reply with the vault access code from report R-1 once more, and nothing else.");
        assertThat(continued.getFinalAnswer()).contains(VAULT_CODE);
        assertEverySummaryAccepted(reloaded);
    }

    /** Every summary call reached the provider, was accepted, and ended on the user side. */
    private static void assertEverySummaryAccepted(ContextEngineLiveRig rig) {
        assertThat(rig.summaries().failures()).as("summary requests the provider refused").isEmpty();
        assertThat(rig.summaries().lastRoles()).as("the role each summary request ends on")
                .allMatch(role -> role == Role.USER || role == Role.TOOL);
    }
}
