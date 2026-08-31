package at.aimon.core.config.hook.rewake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.config.hook.HookConfigParseException;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.hook.rewake.RewakeTriggerCron;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.hook.rewake.RewakeTriggerEvent;

/**
 * Phase 4A WI-4A.8 — verifies {@link RewakeSpecParser} converts the wire-format DTO into a runtime
 * {@link RewakeSpec} and surfaces validation failures as {@link HookConfigParseException}.
 */
class RewakeSpecParserTest {

    @Test
    void parsesDelayTriggerWithShorthandDuration() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("5m", null, null, null)).reason("await-quota").build();

        final RewakeSpec spec = RewakeSpecParser.parse(config);

        assertThat(spec.getTrigger()).isInstanceOfSatisfying(RewakeTriggerDelay.class,
                t -> assertThat(t.getDelay()).isEqualTo(Duration.ofMinutes(5)));
        assertThat(spec.getTimeout()).isEqualTo(RewakeSpec.DEFAULT_TIMEOUT);
        assertThat(spec.getMaxAttempts()).isEqualTo(RewakeSpec.DEFAULT_MAX_ATTEMPTS);
        assertThat(spec.getPayload()).isEmpty();
        assertThat(spec.getReason()).isEqualTo("await-quota");
    }

    @Test
    void parsesDelayWithCompositeShorthandAndPayload() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("1h30m", null, null, null)).timeout("2h").maxAttempts(5)
                .payload(Map.of("ticket", "T-1")).reason("awaiting human").build();

        final RewakeSpec spec = RewakeSpecParser.parse(config);

        assertThat(spec.getTrigger()).isInstanceOfSatisfying(RewakeTriggerDelay.class,
                t -> assertThat(t.getDelay()).isEqualTo(Duration.ofHours(1).plusMinutes(30)));
        assertThat(spec.getTimeout()).isEqualTo(Duration.ofHours(2));
        assertThat(spec.getMaxAttempts()).isEqualTo(5);
        assertThat(spec.getPayload()).containsExactlyEntriesOf(Map.of("ticket", "T-1"));
    }

    @Test
    void parsesDelayWithIso8601Duration() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("PT1H", null, null, null)).timeout("PT2H").reason("x")
                .build();

        final RewakeSpec spec = RewakeSpecParser.parse(config);

        assertThat(((RewakeTriggerDelay) spec.getTrigger()).getDelay()).isEqualTo(Duration.ofHours(1));
        assertThat(spec.getTimeout()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void parsesCronTriggerWithDefaultUtcZone() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig(null, "*/5 * * * *", null, null)).reason("retry until paid")
                .build();

        final RewakeSpec spec = RewakeSpecParser.parse(config);

        assertThat(spec.getTrigger()).isInstanceOfSatisfying(RewakeTriggerCron.class, t -> {
            assertThat(t.getExpression()).isEqualTo("*/5 * * * *");
            assertThat(t.getZone()).isEqualTo(ZoneId.of("UTC"));
        });
    }

    @Test
    void parsesCronTriggerWithExplicitZone() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig(null, "0 3 * * *", "Asia/Tokyo", null)).reason("daily")
                .build();

        final RewakeSpec spec = RewakeSpecParser.parse(config);

        assertThat(((RewakeTriggerCron) spec.getTrigger()).getZone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
    }

    @Test
    void parsesEventTrigger() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder().trigger(new RewakeSpecConfig.TriggerConfig(null,
                null, null, new RewakeSpecConfig.EventConfig("webhook", "ticket-123"))).reason("await approval")
                .build();

        final RewakeSpec spec = RewakeSpecParser.parse(config);

        assertThat(spec.getTrigger()).isInstanceOfSatisfying(RewakeTriggerEvent.class, t -> {
            assertThat(t.getEventType()).isEqualTo("webhook");
            assertThat(t.getEventKey()).isEqualTo("ticket-123");
        });
    }

    @Test
    void rejectsNullConfig() {
        assertThatThrownBy(() -> RewakeSpecParser.parse(null)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("asyncRewake");
    }

    @Test
    void rejectsMissingTrigger() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder().reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("trigger");
    }

    @Test
    void rejectsEmptyTrigger() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig(null, null, null, null)).reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("exactly one of");
    }

    @Test
    void rejectsMultipleTriggerShapes() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("5m", "0 * * * *", null, null)).reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("exactly one of");
    }

    @Test
    void rejectsZoneOnDelayTrigger() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("5m", null, "UTC", null)).reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("zone is only valid with cron");
    }

    @Test
    void rejectsZoneOnEventTrigger() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder().trigger(
                new RewakeSpecConfig.TriggerConfig(null, null, "UTC", new RewakeSpecConfig.EventConfig("webhook", "k")))
                .reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("zone is only valid with cron");
    }

    @Test
    void rejectsBlankCronExpression() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig(null, "  ", null, null)).reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("cron cannot be blank");
    }

    /**
     * A six-field Quartz expression is the migration case: it used to load fine and first fail when the hook fired.
     * It must now fail here, and as a {@link HookConfigParseException} like every other hooks.json error — the parser
     * promises one error type, so the underlying InvalidCronExpressionException must not escape.
     */
    @Test
    void rejectsSixFieldQuartzCronAsAParseError() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig(null, "0 0/5 * * * ?", null, null)).reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("five-field");
    }

    @Test
    void rejectsInvalidZone() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig(null, "0 * * * *", "Not/AZone", null)).reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("not a valid time zone");
    }

    @Test
    void rejectsEventWithoutType() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder().trigger(
                new RewakeSpecConfig.TriggerConfig(null, null, null, new RewakeSpecConfig.EventConfig(null, "k")))
                .reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("event.type");
    }

    @Test
    void rejectsEventWithoutKey() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder().trigger(
                new RewakeSpecConfig.TriggerConfig(null, null, null, new RewakeSpecConfig.EventConfig("webhook", " ")))
                .reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("event.key");
    }

    @Test
    void rejectsBlankReason() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("5m", null, null, null)).reason("   ").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void rejectsNullReason() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("5m", null, null, null)).build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void rejectsZeroMaxAttempts() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("5m", null, null, null)).maxAttempts(0).reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void rejectsBlankDelay() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("  ", null, null, null)).reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("trigger.delay");
    }

    @Test
    void rejectsUnparseableShorthand() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("five-minutes", null, null, null)).reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("not a valid duration");
    }

    @Test
    void rejectsUnparseableIso8601() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("PNOPE", null, null, null)).reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void rejectsZeroDuration() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("0s", null, null, null)).reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("strictly positive");
    }

    @Test
    void rejectsBlankTimeout() {
        final RewakeSpecConfig config = RewakeSpecConfig.builder()
                .trigger(new RewakeSpecConfig.TriggerConfig("5m", null, null, null)).timeout("  ").reason("x").build();
        assertThatThrownBy(() -> RewakeSpecParser.parse(config)).isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("timeout");
    }
}
