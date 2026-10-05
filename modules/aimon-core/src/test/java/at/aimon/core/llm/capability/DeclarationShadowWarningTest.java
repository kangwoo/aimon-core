package at.aimon.core.llm.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.ReasoningEffort;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Backlog L-8: a declaration is the whole row for its name, so one that names a model the built-in table already
 * describes and leaves a flag of that row unstated hands that flag back at its fail-open value. The mechanism is a
 * decision (#46) and is not changed here; what these tests pin is that the registry now <em>says so</em>, once, at
 * construction — and stays quiet for every shape that is not that trap.
 *
 * <p>
 * Asserted on the logged text because the text is the feature: the operator who wrote one line has to be able to
 * read off the model, the row it displaced, each flag that fell back with the value it had, and what to write.
 */
@DisplayName("withDefaultsExtendedBy - warns when a declaration shadows a built-in row it does not restate")
class DeclarationShadowWarningTest {

    @BeforeEach
    void forget() {
        // The warning is once per process per distinct finding, and several tests here make the same finding.
        InMemoryModelCapabilityRegistry.forgetReportedShadows();
    }

    @Test
    @DisplayName("the documented trap: a dialect alone on a claude-* name warns, naming everything needed to fix it")
    void aBareDialectOnADescribedNameWarns() {
        final List<String> warnings = warningsFrom(Map.of("claude-sonnet-5",
                ModelCapabilityDeclaration.builder().thinkingDialect(ThinkingDialect.UNKNOWN).build()));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                // the model, and the built-in row it shadows -- a prefix row, said as one
                .contains("'claude-sonnet-5'").contains("prefix row 'claude-sonnet-5'")
                // the dropped flag with the value the built-in row gave it, and what it is now
                .contains("supportsSamplingParameters=false (now true)")
                // what to add
                .contains("supportsSamplingParameters: false")
                // and nothing about the flag the declaration did state
                .doesNotContain("thinkingDialect=");
    }

    @Test
    @DisplayName("the warning does not change what is registered — replace, not merge")
    void theWarningDoesNotPatchTheRow() {
        final ModelCapabilities resolved = InMemoryModelCapabilityRegistry
                .withDefaultsExtendedBy(Map.of("claude-sonnet-5",
                        ModelCapabilityDeclaration.builder().thinkingDialect(ThinkingDialect.UNKNOWN).build()))
                .resolve("claude-sonnet-5");

        assertThat(resolved.supportsSamplingParameters()).isTrue();
    }

    @Test
    @DisplayName("a complete restatement is silent")
    void aCompleteRestatementIsSilent() {
        assertThat(warningsFrom(Map.of("claude-sonnet-5", ModelCapabilityDeclaration.builder()
                .thinkingDialect(ThinkingDialect.UNKNOWN).supportsSamplingParameters(false).build()))).isEmpty();
    }

    @Test
    @DisplayName("a name no built-in row covers is silent, whatever it leaves out")
    void anUndescribedNameIsSilent() {
        assertThat(warningsFrom(Map.of("prod-assistant",
                ModelCapabilityDeclaration.builder().supportsSamplingParameters(false).build()))).isEmpty();
    }

    @Test
    @DisplayName("stating a different value on purpose is silent — only an unstated flag is a finding")
    void aDeliberatelyDifferentValueIsSilent() {
        // The way to say "yes, I mean the fail-open value": write it. This is also what the warning tells the
        // operator to do, so it has to be true.
        assertThat(warningsFrom(Map.of("claude-sonnet-5", ModelCapabilityDeclaration.builder()
                .thinkingDialect(ThinkingDialect.BUDGETED).supportsSamplingParameters(true).build()))).isEmpty();
    }

    @Test
    @DisplayName("a row that states only fail-open values has nothing to drop")
    void aRowOfDefaultsIsSilent() {
        // gpt-5-chat is registered with four explicit values and every one of them is unknown()'s. Replacing it
        // changes nothing on the wire, so there is nothing to report.
        assertThat(warningsFrom(Map.of("gpt-5-chat-latest",
                ModelCapabilityDeclaration.builder().supportsReasoningSummary(false).build()))).isEmpty();
    }

    @Test
    @DisplayName("an exact row names itself as one, and every dropped flag is listed — the ladder included")
    void anExactRowListsEveryDroppedFlag() {
        final List<String> warnings = warningsFrom(
                Map.of("o4-mini", ModelCapabilityDeclaration.builder().supportsToolsWithReasoning(true).build()));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("'o4-mini'").contains("exact row 'o4-mini'")
                .contains("supportsSamplingParameters=false (now true)")
                .contains("supportsReasoningEffort=true (now false)")
                .contains("supportsReasoningTraceRoundTrip=true (now false)")
                .contains("acceptedReasoningEfforts=[low, medium, high] (now [minimal, low, medium, high])")
                .contains("acceptedReasoningEfforts: [low, medium, high]")
                // stated by the declaration, and equal to the default anyway
                .doesNotContain("supportsToolsWithReasoning=");
    }

    @Test
    @DisplayName("either ladder key counts as stating the ladder")
    void eitherLadderKeyStatesTheLadder() {
        assertThat(warningsFrom(Map.of("o4-mini",
                ModelCapabilityDeclaration.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                        .supportsReasoningTraceRoundTrip(true).lowestReasoningEffort(ReasoningEffort.LOW).build())))
                .isEmpty();
        assertThat(warningsFrom(Map.of("o3",
                ModelCapabilityDeclaration.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                        .supportsReasoningTraceRoundTrip(true)
                        .acceptedReasoningEfforts(EnumSet.of(ReasoningEffort.LOW, ReasoningEffort.HIGH)).build())))
                .isEmpty();
    }

    @Test
    @DisplayName("an exact built-in row is the one reported when a prefix would also match")
    void theExactRowWinsTheReport() {
        // gpt-5.6-terra has an exact row that shadows the gpt-5 prefix, and the two differ in the ladder. The report
        // has to quote the row the name actually resolved to before the declaration displaced it.
        final List<String> warnings = warningsFrom(Map.of("gpt-5.6-terra",
                ModelCapabilityDeclaration.builder().supportsSamplingParameters(false).build()));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("exact row 'gpt-5.6-terra'")
                .contains("acceptedReasoningEfforts=[none, low, medium, high]");
    }

    @Test
    @DisplayName("a dialect-only row is a row too: leaving the dialect out is reported")
    void aDroppedDialectIsReported() {
        final List<String> warnings = warningsFrom(Map.of("claude-opus-4-6",
                ModelCapabilityDeclaration.builder().supportsSamplingParameters(true).build()));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("prefix row 'claude-opus-4-6'")
                .contains("thinkingDialect=either (now unknown)").contains("thinkingDialect: either");
    }

    @Test
    @DisplayName("the name is matched the way look-ups match it — case folded, snapshots under their prefix")
    void theNameIsMatchedAsLookUpsMatchIt() {
        final List<String> warnings = warningsFrom(Map.of("Claude-Sonnet-5-20260101",
                ModelCapabilityDeclaration.builder().thinkingDialect(ThinkingDialect.ADAPTIVE).build()));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("'Claude-Sonnet-5-20260101'").contains("prefix row 'claude-sonnet-5'");
    }

    @Test
    @DisplayName("one warning per shadowing declaration, and none for its well-formed neighbours")
    void oneWarningPerShadowingDeclaration() {
        final Map<String, ModelCapabilityDeclaration> declarations = new LinkedHashMap<>();
        declarations.put("claude-sonnet-5",
                ModelCapabilityDeclaration.builder().thinkingDialect(ThinkingDialect.UNKNOWN).build());
        declarations.put("prod-assistant",
                ModelCapabilityDeclaration.builder().supportsSamplingParameters(false).build());
        declarations.put("claude-opus-5",
                ModelCapabilityDeclaration.builder().thinkingDialect(ThinkingDialect.UNKNOWN).build());

        assertThat(warningsFrom(declarations)).hasSize(2);
    }

    @Test
    @DisplayName("the same finding is logged once per process, however many registries are built from it")
    void theSameFindingIsLoggedOnce() {
        // The starter builds this registry twice from one set of properties -- once in afterPropertiesSet to
        // validate, once in the LLM slice to use -- and an application that brings its own client builds it again.
        final Map<String, ModelCapabilityDeclaration> declarations = Map.of("claude-sonnet-5",
                ModelCapabilityDeclaration.builder().thinkingDialect(ThinkingDialect.UNKNOWN).build());

        assertThat(warningsFrom(declarations)).hasSize(1);
        assertThat(warningsFrom(declarations)).isEmpty();
    }

    @Test
    @DisplayName("a set of declarations that is refused warns about nothing — the refusal is the message")
    void aRefusedSetDoesNotAlsoWarn() {
        final Map<String, ModelCapabilityDeclaration> declarations = new LinkedHashMap<>();
        declarations.put("claude-sonnet-5",
                ModelCapabilityDeclaration.builder().thinkingDialect(ThinkingDialect.UNKNOWN).build());
        declarations.put("Claude-Sonnet-5",
                ModelCapabilityDeclaration.builder().thinkingDialect(ThinkingDialect.UNKNOWN).build());

        final Logger logger = (Logger) LoggerFactory.getLogger(InMemoryModelCapabilityRegistry.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(declarations))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertThat(appender.list).isEmpty();
    }

    private static List<String> warningsFrom(Map<String, ModelCapabilityDeclaration> declarations) {
        final Logger logger = (Logger) LoggerFactory.getLogger(InMemoryModelCapabilityRegistry.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(declarations);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
