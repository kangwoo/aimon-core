package at.aimon.core.hook.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;

class HookResultTest {

    @Test
    void successHasNoFeedbackOrMutations() {
        final HookResult result = HookResult.success();

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.getFeedback()).isEmpty();
        assertThat(result.getUpdatedInput()).isEmpty();
        assertThat(result.getUpdatedOutput()).isEmpty();
    }

    @Test
    void withFeedbackCarriesMessage() {
        final HookResult result = HookResult.withFeedback("ok");

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.getFeedback()).contains("ok");
    }

    @Test
    void withFeedbackRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> HookResult.withFeedback(null));
    }

    @Test
    void blockCarriesReasonAsFeedback() {
        final HookResult result = HookResult.block("nope");

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getFeedback()).contains("nope");
    }

    @Test
    void blockRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> HookResult.block(null));
    }

    @Test
    void withUpdatedInputCarriesReplacementInput() {
        final ToolInput input = ToolInput.of(Map.of("redacted", "***"));
        final HookResult result = HookResult.withUpdatedInput(input);

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.getUpdatedInput()).contains(input);
        assertThat(result.getUpdatedOutput()).isEmpty();
    }

    @Test
    void withUpdatedInputRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> HookResult.withUpdatedInput(null));
    }

    @Test
    void withUpdatedOutputCarriesReplacementOutput() {
        final ToolResult output = ToolResult.success("masked");
        final HookResult result = HookResult.withUpdatedOutput(output);

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.getUpdatedOutput()).contains(output);
        assertThat(result.getUpdatedInput()).isEmpty();
    }

    @Test
    void withUpdatedOutputRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> HookResult.withUpdatedOutput(null));
    }

    @Test
    void builderComposesSuccessFeedbackAndUpdatedInput() {
        final ToolInput updated = ToolInput.of(Map.of("k", "v"));
        final HookResult result = HookResult.builder().status(HookStatus.SUCCESS).feedback("note").updatedInput(updated)
                .build();

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.getFeedback()).contains("note");
        assertThat(result.getUpdatedInput()).contains(updated);
        assertThat(result.getUpdatedOutput()).isEmpty();
    }

    @Test
    void builderComposesBlockedWithUpdatedOutput() {
        final ToolResult masked = ToolResult.success("[masked]");
        final HookResult result = HookResult.builder().status(HookStatus.BLOCKED).feedback("denied")
                .updatedOutput(masked).build();

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getFeedback()).contains("denied");
        assertThat(result.getUpdatedOutput()).contains(masked);
    }

    @Test
    void builderRejectsNullStatus() {
        assertThatNullPointerException().isThrownBy(() -> HookResult.builder().status(null));
    }

    @Test
    void builderDefaultsToSuccessStatus() {
        final HookResult result = HookResult.builder().build();
        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void equalsAndHashCodeIncludeAllFields() {
        final ToolInput input = ToolInput.of(Map.of("k", "v"));
        final ToolResult output = ToolResult.success("x");
        final HookResult a = HookResult.builder().status(HookStatus.SUCCESS).feedback("f").updatedInput(input)
                .updatedOutput(output).build();
        final HookResult b = HookResult.builder().status(HookStatus.SUCCESS).feedback("f").updatedInput(input)
                .updatedOutput(output).build();
        final HookResult c = HookResult.builder().status(HookStatus.SUCCESS).feedback("f").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toStringIncludesPopulatedFieldsOnly() {
        final HookResult result = HookResult.success();
        assertThat(result.toString()).contains("ALLOW").contains("CONTINUE").doesNotContain("feedback")
                .doesNotContain("updatedInput").doesNotContain("updatedOutput");

        final HookResult full = HookResult.builder().feedback("f").updatedInput(ToolInput.of(Map.of("k", "v")))
                .updatedOutput(ToolResult.success("x")).build();
        assertThat(full.toString()).contains("feedback").contains("updatedInput").contains("updatedOutput");
    }

    // ---- Phase 3 (WI-3.1.a) -------------------------------------------------------------------

    @Test
    void allowAndDenyAliasesMapToDecision() {
        assertThat(HookResult.allow().getDecision()).isEqualTo(Decision.ALLOW);
        assertThat(HookResult.allow().getFlowControl()).isEqualTo(FlowControl.CONTINUE);
        assertThat(HookResult.allow().getStatus()).isEqualTo(HookStatus.SUCCESS);

        final HookResult denied = HookResult.deny("nope");
        assertThat(denied.getDecision()).isEqualTo(Decision.DENY);
        assertThat(denied.getFlowControl()).isEqualTo(FlowControl.BLOCK);
        assertThat(denied.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(denied.isBlocked()).isTrue();
        assertThat(denied.getFeedback()).contains("nope");
    }

    @Test
    void successFactoryStillProducesAllowDecision() {
        // Backward-compat: existing callers using success()/block() must observe consistent Phase 3 fields.
        assertThat(HookResult.success().getDecision()).isEqualTo(Decision.ALLOW);
        assertThat(HookResult.block("x").getDecision()).isEqualTo(Decision.DENY);
    }

    @Test
    void builderDecisionAndFlowControlAreSettable() {
        final HookResult ask = HookResult.builder().decision(Decision.ASK).feedback("confirm?").build();
        assertThat(ask.getDecision()).isEqualTo(Decision.ASK);
        assertThat(ask.getFlowControl()).isEqualTo(FlowControl.CONTINUE);
        assertThat(ask.getStatus()).isEqualTo(HookStatus.ASK);

        final HookResult fenced = HookResult.builder().flowControl(FlowControl.BLOCK).build();
        assertThat(fenced.getDecision()).isEqualTo(Decision.ALLOW); // unchanged
        assertThat(fenced.getFlowControl()).isEqualTo(FlowControl.BLOCK);
    }

    // ---- Phase 3 (WI-3.2.a) -----------------------------------------------------------------

    @Test
    void askFactoryProducesAskDecisionWithReasonFeedback() {
        final HookResult result = HookResult.ask("Confirm?");
        assertThat(result.getDecision()).isEqualTo(Decision.ASK);
        assertThat(result.getFlowControl()).isEqualTo(FlowControl.CONTINUE);
        assertThat(result.getStatus()).isEqualTo(HookStatus.ASK);
        assertThat(result.getFeedback()).contains("Confirm?");
        assertThat(result.isBlocked()).isFalse();
    }

    @Test
    void askFactoryRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> HookResult.ask(null));
    }

    @Test
    void builderStatusAskMapsToAskDecision() {
        final HookResult r = HookResult.builder().status(HookStatus.ASK).build();
        assertThat(r.getDecision()).isEqualTo(Decision.ASK);
        assertThat(r.getStatus()).isEqualTo(HookStatus.ASK);
    }

    @Test
    void builderRejectsNullDecisionAndFlowControl() {
        assertThatNullPointerException().isThrownBy(() -> HookResult.builder().decision(null));
        assertThatNullPointerException().isThrownBy(() -> HookResult.builder().flowControl(null));
    }

    @Test
    void mergeWithNoArgsReturnsAllow() {
        assertThat(HookResult.merge().getDecision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void mergePicksMostRestrictiveDecision() {
        final HookResult allow = HookResult.allow();
        final HookResult ask = HookResult.builder().decision(Decision.ASK).build();
        final HookResult deny = HookResult.deny("nope");

        assertThat(HookResult.merge(allow, ask).getDecision()).isEqualTo(Decision.ASK);
        assertThat(HookResult.merge(allow, deny).getDecision()).isEqualTo(Decision.DENY);
        assertThat(HookResult.merge(ask, deny).getDecision()).isEqualTo(Decision.DENY);
        assertThat(HookResult.merge(allow, ask, deny).getDecision()).isEqualTo(Decision.DENY);
    }

    @Test
    void mergePicksMostRestrictiveFlowControl() {
        final HookResult cont = HookResult.allow();
        final HookResult block = HookResult.builder().flowControl(FlowControl.BLOCK).build();
        assertThat(HookResult.merge(cont, block).getFlowControl()).isEqualTo(FlowControl.BLOCK);
    }

    @Test
    void mergeJoinsFeedbackInArgumentOrder() {
        final HookResult merged = HookResult.merge(HookResult.withFeedback("a"), HookResult.allow(),
                HookResult.deny("b"));
        assertThat(merged.getFeedback()).contains("a\nb");
    }

    @Test
    void mergeKeepsFirstNonNullUpdatedInputAndOutput() {
        final ToolInput firstInput = ToolInput.of(Map.of("k", "1"));
        final ToolInput secondInput = ToolInput.of(Map.of("k", "2"));
        final ToolResult firstOutput = ToolResult.success("first");
        final ToolResult secondOutput = ToolResult.success("second");

        final HookResult merged = HookResult.merge(HookResult.withUpdatedInput(firstInput),
                HookResult.withUpdatedInput(secondInput), HookResult.withUpdatedOutput(firstOutput),
                HookResult.withUpdatedOutput(secondOutput));

        assertThat(merged.getUpdatedInput()).contains(firstInput);
        assertThat(merged.getUpdatedOutput()).contains(firstOutput);
    }

    @Test
    void mergeSkipsNullElements() {
        final HookResult merged = HookResult.merge(null, HookResult.deny("stop"), null);
        assertThat(merged.getDecision()).isEqualTo(Decision.DENY);
        assertThat(merged.getFeedback()).contains("stop");
    }

    @Test
    void mergeIterableVariantWorksWithList() {
        final HookResult merged = HookResult
                .merge(java.util.List.of(HookResult.allow(), HookResult.deny("x"), HookResult.withFeedback("y")));
        assertThat(merged.getDecision()).isEqualTo(Decision.DENY);
        assertThat(merged.getFeedback().orElse("")).contains("x").contains("y");
    }

    @Test
    void mergeIterableRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> HookResult.merge((Iterable<HookResult>) null));
    }

    @Test
    void decisionMaxIsTotalOrder() {
        assertThat(Decision.max(Decision.ALLOW, Decision.ALLOW)).isEqualTo(Decision.ALLOW);
        assertThat(Decision.max(Decision.ALLOW, Decision.ASK)).isEqualTo(Decision.ASK);
        assertThat(Decision.max(Decision.ASK, Decision.ALLOW)).isEqualTo(Decision.ASK);
        assertThat(Decision.max(Decision.DENY, Decision.ALLOW)).isEqualTo(Decision.DENY);
        assertThat(Decision.max(Decision.DENY, Decision.ASK)).isEqualTo(Decision.DENY);
    }

    @Test
    void flowControlMaxIsTotalOrder() {
        assertThat(FlowControl.max(FlowControl.CONTINUE, FlowControl.CONTINUE)).isEqualTo(FlowControl.CONTINUE);
        assertThat(FlowControl.max(FlowControl.CONTINUE, FlowControl.BLOCK)).isEqualTo(FlowControl.BLOCK);
        assertThat(FlowControl.max(FlowControl.BLOCK, FlowControl.CONTINUE)).isEqualTo(FlowControl.BLOCK);
    }

    // ---- Phase 4A (async rewake) ------------------------------------------------------------

    @Test
    void defaultsToEmptyRewakeSpecs() {
        assertThat(HookResult.allow().getRewakeSpecs()).isEmpty();
        assertThat(HookResult.deny("x").getRewakeSpecs()).isEmpty();
        assertThat(HookResult.ask("y").getRewakeSpecs()).isEmpty();
    }

    @Test
    void asyncRewakeFactoryProducesAllowWithSingleSpec() {
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(5)))
                .reason("retry").build();
        final HookResult result = HookResult.asyncRewake(spec);

        assertThat(result.getDecision()).isEqualTo(Decision.ALLOW);
        assertThat(result.getFlowControl()).isEqualTo(FlowControl.CONTINUE);
        assertThat(result.getRewakeSpecs()).containsExactly(spec);
    }

    @Test
    void asyncRewakeRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> HookResult.asyncRewake(null));
    }

    @Test
    void builderRewakeSpecAccumulates() {
        final RewakeSpec a = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofSeconds(30))).reason("a")
                .build();
        final RewakeSpec b = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(1))).reason("b")
                .build();
        final HookResult result = HookResult.builder().rewakeSpec(a).rewakeSpec(b).build();

        assertThat(result.getRewakeSpecs()).containsExactly(a, b);
    }

    @Test
    void builderRewakeSpecRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> HookResult.builder().rewakeSpec(null));
    }

    @Test
    void getRewakeSpecsIsImmutable() {
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(1)))
                .reason("retry").build();
        final HookResult result = HookResult.asyncRewake(spec);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> result.getRewakeSpecs().add(spec));
    }

    @Test
    void mergeConcatenatesRewakeSpecsInArgumentOrder() {
        final RewakeSpec a = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofSeconds(10))).reason("a")
                .build();
        final RewakeSpec b = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofSeconds(20))).reason("b")
                .build();
        final RewakeSpec c = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofSeconds(30))).reason("c")
                .build();

        final HookResult merged = HookResult.merge(HookResult.asyncRewake(a),
                HookResult.builder().rewakeSpec(b).rewakeSpec(c).build());

        assertThat(merged.getRewakeSpecs()).containsExactly(a, b, c);
        assertThat(merged.getDecision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void mergeKeepsRewakeSpecsAlongsideDeny() {
        // Even if another hook in the chain denies, rewake specs from earlier ALLOWs are still scheduled.
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(1)))
                .reason("retry").build();
        final HookResult merged = HookResult.merge(HookResult.asyncRewake(spec), HookResult.deny("nope"));

        assertThat(merged.getDecision()).isEqualTo(Decision.DENY);
        assertThat(merged.getRewakeSpecs()).containsExactly(spec);
    }

    @Test
    void toStringIncludesRewakeSpecsWhenPopulated() {
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(1)))
                .reason("retry").build();
        assertThat(HookResult.asyncRewake(spec).toString()).contains("rewakeSpecs");
        assertThat(HookResult.allow().toString()).doesNotContain("rewakeSpecs");
    }

    @Test
    void equalsAndHashCodeIncludeRewakeSpecs() {
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(1)))
                .reason("retry").build();
        final HookResult a = HookResult.asyncRewake(spec);
        final HookResult b = HookResult.asyncRewake(spec);
        final HookResult c = HookResult.allow();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
