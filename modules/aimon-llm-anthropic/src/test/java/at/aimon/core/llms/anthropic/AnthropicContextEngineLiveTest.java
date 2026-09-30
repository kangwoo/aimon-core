package at.aimon.core.llms.anthropic;

import static at.aimon.core.llms.anthropic.ContextEngineLiveRig.VAULT_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.compact.CompactionKind;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.context.ViewProjection;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.Role;

/**
 * The context engines against the real Anthropic API: what a scripted client in {@code aimon-core} cannot establish.
 *
 * <p>
 * <strong>What only a real provider can say.</strong> Every rolling and view-mode test in {@code aimon-core} answers
 * its summary calls with a canned string, so none of them can show that the request the engine builds is one the
 * provider accepts. The case that motivated this class is the prefill rejection: a summary input that ends on an
 * assistant message is read by Anthropic as a prefill, and with extended thinking enabled a prefill is refused with a
 * 400. The engine closes such an input with a user note; only a live call shows the provider agrees that it is closed.
 * The second thing a fixture cannot show is that a real model, having lost a fact from its view, gets it back — from
 * the summary, or by calling {@code SessionHistory} over a range that was sealed out of the record.
 *
 * <p>
 * <strong>How.</strong> The real executor path, a version-2 transcript sealed into an in-memory segment store, and a
 * rolling engine with a small window so a cycle comes every few turns — see {@link ContextEngineLiveRig}. A fact is
 * planted in a large tool result on the first turn, prose filler turns push the session through several rolling
 * cycles, and then the fact is asked for. The session is then read back through the version-2 codec into a fresh
 * record store and continued for one more turn.
 *
 * <p>
 * <strong>Cost.</strong> About 15 billed calls for the plain rolling scenario, about 8 for the extended-thinking one,
 * and 4 for the default engine — well under 50,000 tokens in all on {@code claude-haiku-4-5}. Whether the model calls
 * the report tool on the first turn is the model's decision, so its absence aborts through {@code assumeTrue} rather
 * than failing; everything after that point is red when it goes wrong.
 *
 * <p>
 * Gated on {@code ANTHROPIC_KEY} like {@link AnthropicThinkingLiveTest}, so a keyless build — CI included — skips it.
 */
@DisplayName("Context engines against the real Anthropic API")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_KEY", matches = ".+")
class AnthropicContextEngineLiveTest {

    /** The model {@link AnthropicThinkingLiveTest} runs extended thinking on: cheap, and it takes a thinking budget. */
    private static final String MODEL = "claude-haiku-4-5-20251001";

    private static final String PLANT = "Call the " + ContextEngineLiveRig.REPORT_TOOL
            + " tool with report_id \"R-1\". After reading it, reply with only the word: received.";

    private static final String ASK = "Earlier, report R-1 contained a vault access code. What is it? If it is no"
            + " longer shown to you, use the SessionHistory tool to search for it. Reply with the code only.";

    @TempDir
    Path tempDir;

    private static AnthropicConfig.Builder config() {
        return AnthropicConfig.builder().apiKey(System.getenv("ANTHROPIC_KEY")).model(MODEL).maxTokens(2000);
    }

    @Test
    @DisplayName("rolling: a pruned fact survives several cycles, and the session survives a codec round trip")
    void rollingRecoversAPrunedFactAndSurvivesAReload() throws Exception {
        try (AnthropicLlmClient client = new AnthropicLlmClient(config().build())) {
            final ContextEngineLiveRig rig = ContextEngineLiveRig.rolling(client, LlmModel.builder().build(),
                    tempDir.resolve("first"));

            final OrcaAgentExecutionResult planted = rig.turn(PLANT);
            assumeTrue(ContextEngineLiveRig.calledTheReportTool(planted), "model did not fetch the report");
            final long plantedSeq = ContextEngineLiveRig
                    .seqOfToolResultContaining(planted.getSnapshot().getLogState(), VAULT_CODE)
                    .orElseThrow(() -> new AssertionError("the report's tool result is not in the log"));

            pushThroughRollingCycles(rig, 2);

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
    }

    @Test
    @DisplayName("rolling under extended thinking: every summary request is accepted (none reads as a prefill)")
    void rollingSummariesAreAcceptedUnderExtendedThinking() throws Exception {
        try (AnthropicLlmClient client = new AnthropicLlmClient(
                config().thinkingMode(AnthropicThinkingMode.EXTENDED).build())) {
            final ContextEngineLiveRig rig = ContextEngineLiveRig.rolling(client,
                    LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build(), tempDir);

            final OrcaAgentExecutionResult planted = rig.turn(PLANT);
            assumeTrue(ContextEngineLiveRig.calledTheReportTool(planted), "model did not fetch the report");

            pushThroughRollingCycles(rig, 1);

            assertThat(rig.compactionsOfKind(CompactionKind.ROLLING)).as("rolling cycles").isGreaterThanOrEqualTo(1);
            assertEverySummaryAccepted(rig);
            // One more turn after a summary is installed: the view that carries it must be a request thinking accepts.
            rig.turn("Reply with only the word: done.");
        }
    }

    @Test
    @DisplayName("default engine in view mode: a forced summary is accepted, the log stays whole, the next turn works")
    void defaultEngineViewModeCompactsAndContinues() throws Exception {
        try (AnthropicLlmClient client = new AnthropicLlmClient(config().build())) {
            final ContextEngineLiveRig rig = ContextEngineLiveRig.defaultViewMode(client, LlmModel.builder().build(),
                    tempDir);
            rig.turn("Remember this codeword for later: PELICAN. Reply with only the word: ok.");
            rig.turn(ContextEngineLiveRig.fillerNote(1));
            final int entriesBefore = ContextEngineLiveRig.loggedEntryCount(rig.storedLog());

            final CompactionResult compacted = rig.compactNow();

            assertThat(compacted.isSuccess()).as("forced summary: %s", compacted.getError().orElse(null)).isTrue();
            assertEverySummaryAccepted(rig);
            final SessionLogState log = rig.storedLog();
            assertThat(log.getViewState().getSummarySpan()).as("view mode records the summary as a span").isPresent();
            assertThat(ContextEngineLiveRig.loggedEntryCount(log)).as("view mode leaves the log append-only")
                    .isEqualTo(entriesBefore);
            assertThat(ViewProjection.of(log).size()).as("the view is shorter than the log").isLessThan(entriesBefore);

            final OrcaAgentExecutionResult next = rig.turn("What was the codeword? Reply with the codeword only.");
            assertThat(next.getFinalAnswer()).containsIgnoringCase("PELICAN");
        }
    }

    /**
     * Filler turns until {@code cycles} rolling summaries have been installed, or
     * {@link ContextEngineLiveRig#MAX_FILLER_TURNS}.
     */
    private static void pushThroughRollingCycles(ContextEngineLiveRig rig, int cycles) {
        for (int n = 1; n <= ContextEngineLiveRig.MAX_FILLER_TURNS
                && rig.compactionsOfKind(CompactionKind.ROLLING) < cycles; n++) {
            rig.turn(ContextEngineLiveRig.fillerNote(n));
        }
    }

    /** Every summary call reached the provider, was accepted, and ended on the user side. */
    private static void assertEverySummaryAccepted(ContextEngineLiveRig rig) {
        assertThat(rig.summaries().failures()).as("summary requests the provider refused").isEmpty();
        assertThat(rig.summaries().lastRoles()).as("the role each summary request ends on")
                .allMatch(role -> role == Role.USER || role == Role.TOOL);
    }
}
