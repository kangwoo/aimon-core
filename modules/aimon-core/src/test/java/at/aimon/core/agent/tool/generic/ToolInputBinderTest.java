package at.aimon.core.agent.tool.generic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;

/**
 * Tests for {@link ToolInputBinder}.
 *
 * <p>
 * Binding has two jobs and they are tested separately: producing the record a tool then reads, and producing the
 * sentences a model reads when it cannot. The second is the one worth being fussy about — those strings are the
 * agent's contract with the model, not diagnostics.
 */
class ToolInputBinderTest {

    enum Mode {
        FAST, THOROUGH
    }

    record Simple(@ToolParam(required = true) String action, Integer count, Boolean enabled) {
    }

    record Named(@ToolParam(name = "output_mode") String outputMode, @ToolParam(name = "-i") Boolean insensitive) {
    }

    record Numbers(Integer small, Long big, Short tiny, Byte tiniest, BigInteger huge, Double decimal, Float lesser,
            BigDecimal exact) {
    }

    record Primitives(int count, boolean enabled) {
    }

    record Letters(Character letter, char flag) {
    }

    record Enums(Mode mode) {
    }

    record Allowed(@ToolParam(allowed = {
            "content", "count"}) String mode){
    }

    record Collections(List<String> names, Set<Integer> numbers, String[] array) {
    }

    record Matrix(List<List<String>> rows) {
    }

    record Optionals(Optional<String> maybe, String plain) {
    }

    record FreeForm(Map<String, Object> options) {
    }

    record Inner(@ToolParam(required = true) String id, Integer weight) {
    }

    record Outer(@ToolParam(required = true) Inner inner) {
    }

    record Guarded(@ToolParam(required = true) Integer from, @ToolParam(required = true) Integer to) {
        Guarded {
            if (from > to) {
                throw new IllegalArgumentException("Parameter 'from' must not exceed 'to'.");
            }
        }
    }

    static class NotARecord {
    }

    private static <T> BindResult<T> bind(Class<T> type, Map<String, Object> values) {
        return ToolInputBinder.forType(type).bind(ToolInput.of(values));
    }

    @Nested
    class Construction {

        @Test
        void rejectsANonRecordInputType() {
            assertThatThrownBy(() -> ToolInputBinder.forType(NotARecord.class))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be a record");
        }

        @Test
        void rejectsANullInputType() {
            assertThatThrownBy(() -> ToolInputBinder.forType(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejectsANullCall() {
            assertThatThrownBy(() -> ToolInputBinder.forType(Simple.class).bind(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Binding {

        @Test
        void bindsEveryComponentItWasGiven() {
            BindResult<Simple> result = bind(Simple.class, Map.of("action", "go", "count", 3, "enabled", true));

            assertThat(result.isBound()).isTrue();
            assertThat(result.getValue()).isEqualTo(new Simple("go", 3, true));
        }

        @Test
        void anAbsentOptionalParameterBindsToNullRatherThanAZero() {
            // The distinction GrepTool's -A / -B / -C depend on: "not supplied" is not "supplied as 0".
            BindResult<Simple> result = bind(Simple.class, Map.of("action", "go"));

            assertThat(result.getValue()).isEqualTo(new Simple("go", null, null));
        }

        @Test
        void anAbsentOptionalBindsToEmptyRatherThanNull() {
            BindResult<Optionals> result = bind(Optionals.class, Map.of("plain", "x"));

            assertThat(result.getValue().maybe()).isEmpty();
        }

        @Test
        void aSuppliedOptionalIsWrapped() {
            BindResult<Optionals> result = bind(Optionals.class, Map.of("maybe", "here", "plain", "x"));

            assertThat(result.getValue().maybe()).contains("here");
        }

        @Test
        void readsAParameterByItsDeclaredNameNotItsComponentName() {
            BindResult<Named> result = bind(Named.class, Map.of("output_mode", "content", "-i", true));

            assertThat(result.isBound()).isTrue();
            assertThat(result.getValue()).isEqualTo(new Named("content", true));
        }

        @Test
        void aComponentNameIsNotAnAcceptedAlias() {
            BindResult<Named> result = bind(Named.class, Map.of("outputMode", "content"));

            assertThat(result.isBound()).isFalse();
            assertThat(result.getViolations()).anyMatch(v -> v.contains("Unknown parameter 'outputMode'"));
        }
    }

    @Nested
    class NumberConversion {

        @Test
        void narrowsToEachIntegralWidth() {
            BindResult<Numbers> result = bind(Numbers.class, Map.of("small", 1, "big", 2, "tiny", 3, "tiniest", 4,
                    "huge", 5, "decimal", 6.5, "lesser", 7.5, "exact", 8.25));

            assertThat(result.getValue()).isEqualTo(
                    new Numbers(1, 2L, (short) 3, (byte) 4, BigInteger.valueOf(5), 6.5d, 7.5f, new BigDecimal("8.25")));
        }

        @Test
        void acceptsTheThreePointZeroAJsonParserHandsBackForAThree() {
            // Same reading as the executor's schema gate: a model does not choose which of the two its transport
            // produced, so rejecting one would be a distinction it could not act on.
            BindResult<Numbers> result = bind(Numbers.class, Map.of("small", 3.0));

            assertThat(result.isBound()).isTrue();
            assertThat(result.getValue().small()).isEqualTo(3);
        }

        @Test
        void rejectsAFractionForAnIntegralParameter() {
            BindResult<Numbers> result = bind(Numbers.class, Map.of("small", 3.5));

            assertThat(result.getViolations())
                    .containsExactly("Parameter 'small' must be an integer. The tool was not executed.");
        }

        @Test
        void rejectsAValueTooWideForItsDeclaredWidth() {
            BindResult<Numbers> result = bind(Numbers.class, Map.of("tiniest", 5000));

            assertThat(result.getViolations()).anyMatch(v -> v.contains("'tiniest'"));
        }

        @Test
        void rejectsAValueTooWideForALongRatherThanSaturatingIt() {
            // The two widest targets used to be the only ones without a range check: narrowing read through
            // longValue() first, so a value past Long.MAX_VALUE arrived already clamped and then passed every test
            // the real number would have failed.
            BindResult<Numbers> result = bind(Numbers.class, Map.of("big", new BigInteger("9223372036854775808")));

            assertThat(result.isBound()).isFalse();
            assertThat(result.getViolations())
                    .containsExactly("Parameter 'big' must be an integer. The tool was not executed.");
        }

        @Test
        void keepsAValueWiderThanALongWhenBigIntegerIsWhatWasDeclared() {
            BigInteger wide = new BigInteger("92233720368547758080");

            BindResult<Numbers> result = bind(Numbers.class, Map.of("huge", wide));

            assertThat(result.getValue().huge()).isEqualTo(wide);
        }

        @Test
        void widensAnIntegerSuppliedForADecimalParameter() {
            BindResult<Numbers> result = bind(Numbers.class, Map.of("decimal", 6));

            assertThat(result.getValue().decimal()).isEqualTo(6.0d);
        }
    }

    @Nested
    class Violations {

        @Test
        void aMissingRequiredParameterNamesItsDeclaredType() {
            BindResult<Simple> result = bind(Simple.class, Map.of());

            assertThat(result.isBound()).isFalse();
            assertThat(result.getViolations())
                    .containsExactly("Parameter 'action' is required (type: string). The tool was not executed.");
        }

        @Test
        void aTypeMismatchNamesTheTypeTheSchemaShowed() {
            BindResult<Simple> result = bind(Simple.class, Map.of("action", "go", "count", "three"));

            assertThat(result.getViolations())
                    .containsExactly("Parameter 'count' must be an integer. The tool was not executed.");
        }

        @Test
        void anUndeclaredParameterIsRejectedRatherThanIgnored() {
            BindResult<Simple> result = bind(Simple.class, Map.of("action", "go", "recursive", true));

            assertThat(result.getViolations())
                    .containsExactly("Unknown parameter 'recursive'. The tool was not executed.");
        }

        @Test
        void aMisspelledParameterIsAnsweredWithTheNameItProbablyMeant() {
            BindResult<Simple> result = bind(Simple.class, Map.of("action", "go", "cont", 3));

            assertThat(result.getViolations())
                    .containsExactly("Unknown parameter 'cont'. Did you mean 'count'? The tool was not executed.");
        }

        @Test
        void everyViolationIsReportedAtOnce() {
            // The reason binding exists rather than a run of getRequiredString calls: two wrong arguments cost one
            // retry, not two.
            Map<String, Object> values = new HashMap<>();
            values.put("count", "three");
            values.put("enabled", 1);
            values.put("recursive", true);

            BindResult<Simple> result = bind(Simple.class, values);

            assertThat(result.getViolations()).hasSize(4);
            assertThat(String.join("\n", result.getViolations())).contains("'action'").contains("'count'")
                    .contains("'enabled'").contains("'recursive'");
        }

        @Test
        void everyViolationEndsBySayingTheToolDidNotRun() {
            BindResult<Simple> result = bind(Simple.class, Map.of("count", "three"));

            assertThat(result.getViolations()).isNotEmpty().allMatch(v -> v.endsWith("The tool was not executed."));
        }

        @Test
        void aValueOutsideADeclaredSetListsTheWholeSet() {
            BindResult<Allowed> result = bind(Allowed.class, Map.of("mode", "lines"));

            assertThat(result.getViolations()).containsExactly(
                    "Parameter 'mode' must be one of [content, count], but was 'lines'. The tool was not executed.");
        }

        @Test
        void aValueOutsideAJavaEnumIsReportedOnceNotTwice() {
            // The constraint is in the type and in the schema; saying it twice would read as two separate problems.
            BindResult<Enums> result = bind(Enums.class, Map.of("mode", "SLOW"));

            assertThat(result.getViolations()).containsExactly(
                    "Parameter 'mode' must be one of [FAST, THOROUGH], but was 'SLOW'. The tool was not executed.");
        }

        @Test
        void aMissingPrimitiveIsAViolationRatherThanASilentZero() {
            BindResult<Primitives> result = bind(Primitives.class, Map.of());

            assertThat(result.getViolations()).hasSize(2);
            assertThat(String.join("\n", result.getViolations())).contains("'count'").contains("'enabled'");
        }
    }

    @Nested
    class CompoundValues {

        @Test
        void bindsEachCollectionShape() {
            BindResult<Collections> result = bind(Collections.class,
                    Map.of("names", List.of("a", "b"), "numbers", List.of(1, 2), "array", List.of("x")));

            assertThat(result.getValue().names()).containsExactly("a", "b");
            assertThat(result.getValue().numbers()).containsExactly(1, 2);
            assertThat(result.getValue().array()).containsExactly("x");
        }

        @Test
        void namesThePositionOfABadElement() {
            BindResult<Collections> result = bind(Collections.class, Map.of("names", List.of("a", 2)));

            assertThat(result.getViolations())
                    .containsExactly("Parameter 'names[1]' must be a string. The tool was not executed.");
        }

        @Test
        void rejectsAScalarWhereAnArrayWasDeclared() {
            BindResult<Collections> result = bind(Collections.class, Map.of("names", "a"));

            assertThat(result.getViolations())
                    .containsExactly("Parameter 'names' must be an array. The tool was not executed.");
        }

        @Test
        void bindsANestedRecord() {
            BindResult<Outer> result = bind(Outer.class, Map.of("inner", Map.of("id", "x", "weight", 2)));

            assertThat(result.getValue()).isEqualTo(new Outer(new Inner("x", 2)));
        }

        @Test
        void qualifiesAViolationInsideANestedRecord() {
            BindResult<Outer> result = bind(Outer.class, Map.of("inner", Map.of("weight", 2)));

            assertThat(result.getViolations())
                    .containsExactly("Parameter 'inner.id' is required (type: string). The tool was not executed.");
        }

        @Test
        void bindsASingleCharacterStringToACharacterComponent() {
            BindResult<Letters> result = bind(Letters.class, Map.of("letter", "x", "flag", "!"));

            assertThat(result.isBound()).isTrue();
            assertThat(result.getValue()).isEqualTo(new Letters('x', '!'));
        }

        @Test
        void refusesToTakeTheFirstCharacterOfALongerString() {
            // The generator advertises a char as "string", so the value arrives as one. Truncating it would have the
            // tool act on something the caller never sent.
            BindResult<Letters> result = bind(Letters.class, Map.of("letter", "xy", "flag", "!"));

            assertThat(result.getViolations()).containsExactly(
                    "Parameter 'letter' must be a string of exactly one character. The tool was not executed.");
        }

        @Test
        void reportsANullElementRatherThanFailingOnIt() {
            BindResult<Matrix> result = bind(Matrix.class, Map.of("rows", Arrays.asList(List.of("a"), null)));

            assertThat(result.isBound()).isFalse();
            assertThat(result.getViolations())
                    .containsExactly("Parameter 'rows[1]' must be an array. The tool was not executed.");
        }

        @Test
        void suggestsANestedNameUnderThePathTheCallerUsed() {
            // The suggestion is compared against qualified names too. Measuring a reported 'inner.weigth' against a
            // bare 'weight' puts every candidate past the edit-distance cut-off, which turned the suggestion off for
            // every nested record — the one place a path is most worth handing back.
            BindResult<Outer> result = bind(Outer.class, Map.of("inner", Map.of("id", "x", "weigth", 2)));

            assertThat(result.getViolations()).containsExactly(
                    "Unknown parameter 'inner.weigth'. Did you mean 'inner.weight'? The tool was not executed.");
        }

        @Test
        void leavesAFreeFormMapAlone() {
            BindResult<FreeForm> result = bind(FreeForm.class, Map.of("options", Map.of("anything", 1)));

            assertThat(result.getValue().options()).containsEntry("anything", 1);
        }
    }

    @Nested
    class RecordOwnRules {

        @Test
        void aCompactConstructorRejectionBecomesAViolationInTheToolsOwnWords() {
            BindResult<Guarded> result = bind(Guarded.class, Map.of("from", 9, "to", 1));

            assertThat(result.isBound()).isFalse();
            assertThat(result.getViolations())
                    .containsExactly("Parameter 'from' must not exceed 'to'. The tool was not executed.");
        }

        @Test
        void aCompactConstructorIsNotReachedWhenBindingAlreadyFailed() {
            // from is null here; running the guard would NPE rather than say anything useful.
            BindResult<Guarded> result = bind(Guarded.class, Map.of("to", 1));

            assertThat(result.getViolations())
                    .containsExactly("Parameter 'from' is required (type: integer). The tool was not executed.");
        }
    }

    @Nested
    class ResultContract {

        @Test
        void anUnboundResultRefusesToHandOverAValue() {
            BindResult<Simple> result = bind(Simple.class, Map.of());

            assertThatThrownBy(result::getValue).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void aBoundResultHasNoViolations() {
            BindResult<Simple> result = bind(Simple.class, Map.of("action", "go"));

            assertThat(result.getViolations()).isEmpty();
        }
    }
}
