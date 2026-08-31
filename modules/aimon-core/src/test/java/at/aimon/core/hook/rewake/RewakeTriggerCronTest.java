package at.aimon.core.hook.rewake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.core.scheduling.exception.InvalidCronExpressionException;

class RewakeTriggerCronTest {

    @Test
    void exposesExpressionAndZone() {
        final RewakeTriggerCron trigger = new RewakeTriggerCron("*/5 * * * *", ZoneId.of("UTC"));

        assertThat(trigger.getExpression()).isEqualTo("*/5 * * * *");
        assertThat(trigger.getZone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void normalisesSurroundingWhitespace() {
        assertThat(new RewakeTriggerCron("  0 0 * * *  ", ZoneId.of("UTC")).getExpression()).isEqualTo("0 0 * * *");
    }

    @Test
    void rejectsNullExpression() {
        assertThatNullPointerException().isThrownBy(() -> new RewakeTriggerCron(null, ZoneId.of("UTC")));
    }

    @Test
    void rejectsBlankExpression() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RewakeTriggerCron("   ", ZoneId.of("UTC")));
    }

    /**
     * The point of parsing in the constructor: a bad expression fails where it is written, not when the hook fires.
     * Six-field Quartz input is the case that matters most — it is what this field used to accept.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0 0 * * * ?", "0 */5 * * * ?", "0 0 * *", "0 0 L * *", "@daily", "not-a-cron",
            "60 0 * * *"})
    void rejectsExpressionsOutsideTheFiveFieldDialect(String expression) {
        assertThatThrownBy(() -> new RewakeTriggerCron(expression, ZoneId.of("UTC")))
                .isInstanceOf(InvalidCronExpressionException.class);
    }

    @Test
    void rejectsNullZone() {
        assertThatNullPointerException().isThrownBy(() -> new RewakeTriggerCron("0 0 * * *", null));
    }

    @Test
    void equalsAndHashCodeIncludeExpressionAndZone() {
        final RewakeTriggerCron a = new RewakeTriggerCron("0 0 * * *", ZoneId.of("UTC"));
        final RewakeTriggerCron b = new RewakeTriggerCron("0 0 * * *", ZoneId.of("UTC"));
        final RewakeTriggerCron different = new RewakeTriggerCron("30 * * * *", ZoneId.of("UTC"));
        final RewakeTriggerCron differentZone = new RewakeTriggerCron("0 0 * * *", ZoneId.of("Asia/Seoul"));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
        assertThat(a).isNotEqualTo(differentZone);
    }

    @Test
    void toStringIncludesExpressionAndZone() {
        assertThat(new RewakeTriggerCron("0 0 * * *", ZoneId.of("UTC")).toString()).contains("expression")
                .contains("zone");
    }
}
