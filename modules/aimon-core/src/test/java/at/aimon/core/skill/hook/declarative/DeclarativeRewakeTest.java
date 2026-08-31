package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.FlowControl;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.hook.rewake.RewakeTriggerCron;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.hook.rewake.RewakeTriggerEvent;

class DeclarativeRewakeTest {

    @Test
    void attach_nullSpec_returnsSameResultInstance() {
        HookResult result = HookResult.withFeedback("nothing to schedule");

        assertThat(DeclarativeRewake.attach(result, null)).isSameAs(result);
    }

    @Test
    void attach_delaySpec_isAppended() {
        RewakeSpec spec = delaySpec();

        HookResult attached = DeclarativeRewake.attach(HookResult.success(), spec);

        assertThat(attached.getRewakeSpecs()).containsExactly(spec);
    }

    @Test
    void attach_eventSpec_isAppended() {
        RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerEvent("webhook", "ticket-42"))
                .timeout(Duration.ofMinutes(30)).reason("await webhook").build();

        HookResult attached = DeclarativeRewake.attach(HookResult.success(), spec);

        assertThat(attached.getRewakeSpecs()).containsExactly(spec);
    }

    /**
     * A cron spec is attached like any other. It has to be: this is also the path that produces the <em>initial</em>
     * envelope, so filtering cron here would make {@code asyncRewake} with a cron trigger schedule nothing at all.
     * The guard against a cron fire forking a fresh series on every tick lives on the re-fire path instead —
     * see {@code DefaultRewakeFireListenerTest#cronFollowUpIsNotChainedBecauseTheTriggerRepeatsNatively}.
     */
    @Test
    void attach_cronSpec_isAppendedSoTheInitialEnvelopeStillGetsScheduled() {
        RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerCron("*/5 * * * *", ZoneId.of("UTC")))
                .timeout(Duration.ofHours(2)).maxAttempts(10).reason("poll every five minutes").build();

        HookResult attached = DeclarativeRewake.attach(HookResult.success(), spec);

        assertThat(attached.getRewakeSpecs()).containsExactly(spec);
    }

    @Test
    void attach_preservesEveryOtherResultField() {
        RewakeSpec existing = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofSeconds(30)))
                .reason("already queued").build();
        ToolInput updatedInput = ToolInput.of("command", "ls -al");
        ToolResult updatedOutput = ToolResult.success("rewritten output");
        HookResult result = HookResult.builder().decision(Decision.DENY).flowControl(FlowControl.BLOCK)
                .feedback("denied by policy").updatedInput(updatedInput).updatedOutput(updatedOutput)
                .rewakeSpec(existing).build();
        RewakeSpec spec = delaySpec();

        HookResult attached = DeclarativeRewake.attach(result, spec);

        assertThat(attached.getDecision()).isEqualTo(Decision.DENY);
        assertThat(attached.getFlowControl()).isEqualTo(FlowControl.BLOCK);
        assertThat(attached.getFeedback()).contains("denied by policy");
        assertThat(attached.getUpdatedInput()).contains(updatedInput);
        assertThat(attached.getUpdatedOutput()).contains(updatedOutput);
        assertThat(attached.getRewakeSpecs()).containsExactly(existing, spec);
    }

    private static RewakeSpec delaySpec() {
        return RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(5)))
                .reason("retry after five minutes").build();
    }
}
