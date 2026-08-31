package at.aimon.core.config.hook.rewake;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import at.aimon.core.config.hook.HookConfigParseException;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.hook.rewake.RewakeTrigger;
import at.aimon.core.hook.rewake.RewakeTriggerCron;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.hook.rewake.RewakeTriggerEvent;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;

/**
 * Converts the wire-format {@link RewakeSpecConfig} DTO into a runtime {@link RewakeSpec}.
 *
 * <p>
 * Validation responsibilities (the DTO itself is a permissive Jackson mirror):
 * <ul>
 * <li>Exactly one of {@code delay} / {@code cron} / {@code event} must be set on
 * {@link RewakeSpecConfig.TriggerConfig}.
 * <li>{@code delay} and {@code timeout} accept either shorthand ({@code "5m"}, {@code "1h30m"}, {@code "30s"}) or
 * ISO-8601 ({@code "PT1H"}). Strings starting with {@code P} or {@code p} are delegated to {@link Duration#parse};
 * other
 * strings go through the shorthand parser.
 * <li>{@code cron} requires a valid five-field expression ({@link at.aimon.core.scheduling.cron.UnixCronExpression});
 * {@code zone} defaults to {@code UTC} when omitted.
 * <li>{@code event} requires non-blank {@code type} and {@code key}.
 * <li>{@code reason} must be non-null and non-blank.
 * </ul>
 *
 * <p>
 * Defaults are inherited from {@link RewakeSpec#DEFAULT_TIMEOUT} and {@link RewakeSpec#DEFAULT_MAX_ATTEMPTS} when the
 * wire form omits the corresponding field.
 *
 * <p>
 * All failures surface as {@link HookConfigParseException} so {@code hooks.json} parsing keeps a single error type.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class RewakeSpecParser {

    private static final ZoneId DEFAULT_CRON_ZONE = ZoneId.of("UTC");

    /** Matches at least one of {@code <h>h}, {@code <m>m}, {@code <s>s}; e.g. {@code 1h30m}, {@code 45s}. */
    private static final Pattern SHORTHAND_DURATION = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$");

    private RewakeSpecParser() {
        // utility class
    }

    /**
     * Parses a wire-format {@link RewakeSpecConfig} into a runtime {@link RewakeSpec}.
     *
     * @param config
     *            wire-format DTO (must not be null)
     * @return validated runtime spec (never null)
     * @throws HookConfigParseException
     *             on any validation or parsing failure
     */
    public static RewakeSpec parse(RewakeSpecConfig config) {
        if (config == null) {
            throw new HookConfigParseException("asyncRewake block cannot be null");
        }

        final RewakeTrigger trigger = parseTrigger(config.getTrigger());
        final Duration timeout = config.getTimeout() == null
                ? RewakeSpec.DEFAULT_TIMEOUT
                : parseDuration("timeout", config.getTimeout());
        final int maxAttempts = config.getMaxAttempts() == null
                ? RewakeSpec.DEFAULT_MAX_ATTEMPTS
                : config.getMaxAttempts();
        if (maxAttempts < 1) {
            throw new HookConfigParseException("asyncRewake.maxAttempts must be >= 1, got: " + maxAttempts);
        }

        final String reason = config.getReason();
        if (reason == null || reason.isBlank()) {
            throw new HookConfigParseException("asyncRewake.reason is required and must not be blank");
        }

        final Map<String, String> payload = config.getPayload();

        try {
            return RewakeSpec.builder().trigger(trigger).timeout(timeout).maxAttempts(maxAttempts).payload(payload)
                    .reason(reason).build();
        } catch (RuntimeException e) {
            throw new HookConfigParseException("Invalid asyncRewake block: " + e.getMessage(), e);
        }
    }

    private static RewakeTrigger parseTrigger(RewakeSpecConfig.TriggerConfig trigger) {
        if (trigger == null) {
            throw new HookConfigParseException("asyncRewake.trigger is required");
        }
        final boolean hasDelay = trigger.getDelay() != null;
        final boolean hasCron = trigger.getCron() != null;
        final boolean hasEvent = trigger.getEvent() != null;
        final int populated = (hasDelay ? 1 : 0) + (hasCron ? 1 : 0) + (hasEvent ? 1 : 0);
        if (populated == 0) {
            throw new HookConfigParseException(
                    "asyncRewake.trigger must set exactly one of {delay, cron, event}; none provided");
        }
        if (populated > 1) {
            throw new HookConfigParseException(
                    "asyncRewake.trigger must set exactly one of {delay, cron, event}; multiple provided");
        }

        if (hasDelay) {
            if (trigger.getZone() != null) {
                throw new HookConfigParseException("asyncRewake.trigger.zone is only valid with cron triggers");
            }
            return new RewakeTriggerDelay(parseDuration("trigger.delay", trigger.getDelay()));
        }
        if (hasCron) {
            final String expression = trigger.getCron();
            if (expression.isBlank()) {
                throw new HookConfigParseException("asyncRewake.trigger.cron cannot be blank");
            }
            final ZoneId zone = parseZone(trigger.getZone());
            try {
                return new RewakeTriggerCron(expression, zone);
            } catch (InvalidCronExpressionException e) {
                throw new HookConfigParseException(
                        "asyncRewake.trigger.cron is not a valid five-field cron " + "expression: " + e.getMessage(),
                        e);
            }
        }
        // event
        if (trigger.getZone() != null) {
            throw new HookConfigParseException("asyncRewake.trigger.zone is only valid with cron triggers");
        }
        return parseEvent(trigger.getEvent());
    }

    private static RewakeTriggerEvent parseEvent(RewakeSpecConfig.EventConfig event) {
        final String type = event.getType();
        if (type == null || type.isBlank()) {
            throw new HookConfigParseException("asyncRewake.trigger.event.type is required");
        }
        final String key = event.getKey();
        if (key == null || key.isBlank()) {
            throw new HookConfigParseException("asyncRewake.trigger.event.key is required");
        }
        return new RewakeTriggerEvent(type, key);
    }

    private static ZoneId parseZone(String zone) {
        if (zone == null || zone.isBlank()) {
            return DEFAULT_CRON_ZONE;
        }
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new HookConfigParseException("asyncRewake.trigger.zone is not a valid time zone: '" + zone + "'", e);
        }
    }

    /**
     * Parses a duration string supporting both shorthand ({@code 1h30m}, {@code 45s}) and ISO-8601 ({@code PT1H}).
     * Strings starting with {@code P} or {@code p} are delegated to {@link Duration#parse}; everything else is parsed
     * by the shorthand grammar.
     *
     * @param fieldName
     *            human-readable field name for error messages (e.g. {@code "timeout"})
     * @param raw
     *            non-null raw string from the JSON wire form
     * @return parsed strictly-positive duration
     * @throws HookConfigParseException
     *             on null/blank/unparseable input or non-positive duration
     */
    private static Duration parseDuration(String fieldName, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new HookConfigParseException("asyncRewake." + fieldName + " cannot be blank");
        }
        final String trimmed = raw.trim();
        final Duration duration;
        if (!trimmed.isEmpty() && (trimmed.charAt(0) == 'P' || trimmed.charAt(0) == 'p')) {
            try {
                duration = Duration.parse(trimmed);
            } catch (DateTimeParseException e) {
                throw new HookConfigParseException(
                        "asyncRewake." + fieldName + " is not a valid ISO-8601 duration: '" + raw + "'", e);
            }
        } else {
            duration = parseShorthand(fieldName, trimmed);
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new HookConfigParseException(
                    "asyncRewake." + fieldName + " must be strictly positive, got: '" + raw + "'");
        }
        return duration;
    }

    private static Duration parseShorthand(String fieldName, String raw) {
        final Matcher m = SHORTHAND_DURATION.matcher(raw);
        if (!m.matches() || (m.group(1) == null && m.group(2) == null && m.group(3) == null)) {
            throw new HookConfigParseException("asyncRewake." + fieldName
                    + " is not a valid duration (expected e.g. '5m', '1h30m', 'PT1H'): '" + raw + "'");
        }
        long seconds = 0L;
        if (m.group(1) != null) {
            seconds += Long.parseLong(m.group(1)) * 3600L;
        }
        if (m.group(2) != null) {
            seconds += Long.parseLong(m.group(2)) * 60L;
        }
        if (m.group(3) != null) {
            seconds += Long.parseLong(m.group(3));
        }
        return Duration.ofSeconds(seconds);
    }
}
