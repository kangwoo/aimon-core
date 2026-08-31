package at.aimon.core.agent.tool.generic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ToolSchemaGenerator}.
 *
 * <p>
 * The point of the class is that the schema is a reading of the record rather than a second declaration, so these tests
 * are written as "this record produces this schema" rather than as coverage of internal helpers.
 */
class ToolSchemaGeneratorTest {

    enum Mode {
        FAST, THOROUGH
    }

    record Simple(@ToolParam(required = true, description = "The thing to do") String action,
            @ToolParam(description = "How many") Integer count) {
    }

    record Named(@ToolParam(name = "output_mode") String outputMode, @ToolParam(name = "-i") Boolean caseInsensitive) {
    }

    record Primitives(int count, boolean enabled, String note) {
    }

    record Optionals(@ToolParam(required = true) Optional<String> mustNotMatter, String plain) {
    }

    record Types(String string, int integer, long bigger, BigInteger huge, double decimal, float smaller,
            BigDecimal exact, boolean flag, Mode mode, List<String> names, Set<Integer> numbers, String[] array,
            Map<String, Object> free) {
    }

    record Inner(@ToolParam(required = true) String id, Integer weight) {
    }

    record Outer(@ToolParam(description = "The nested one") Inner inner) {
    }

    record SelfNesting(SelfNesting child) {
    }

    record Unmappable(Thread thread) {
    }

    record RawCollection(@SuppressWarnings("rawtypes") List items) {
    }

    static class NotARecord {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(Class<?> type) {
        return (Map<String, Object>) ToolSchemaGenerator.generate(type).get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertyOf(Class<?> type, String name) {
        return (Map<String, Object>) propertiesOf(type).get(name);
    }

    @Nested
    class Shape {

        @Test
        void objectTypeAndClosedToUndeclaredKeys() {
            Map<String, Object> schema = ToolSchemaGenerator.generate(Simple.class);

            assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false);
        }

        @Test
        void propertiesFollowDeclarationOrder() {
            assertThat(propertiesOf(Types.class).keySet()).containsExactly("string", "integer", "bigger", "huge",
                    "decimal", "smaller", "exact", "flag", "mode", "names", "numbers", "array", "free");
        }

        @Test
        void returnedSchemaIsUnmodifiable() {
            Map<String, Object> schema = ToolSchemaGenerator.generate(Simple.class);

            assertThatThrownBy(() -> schema.put("extra", "value")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void requiredIsOmittedRatherThanEmptyWhenNothingIsRequired() {
            assertThat(ToolSchemaGenerator.generate(Named.class)).doesNotContainKey("required");
        }
    }

    @Nested
    class Naming {

        @Test
        void componentNameIsUsedVerbatimWhenNoNameIsDeclared() {
            // No camelCase-to-snake_case conversion: what the record says is what the model is told.
            assertThat(propertiesOf(Simple.class)).containsKeys("action", "count");
        }

        @Test
        void declaredNameWinsAndMayBeUnspellableInJava() {
            assertThat(propertiesOf(Named.class)).containsOnlyKeys("output_mode", "-i");
        }
    }

    @Nested
    class Requiredness {

        @Test
        void annotationDrivesRequiredness() {
            assertThat(ToolSchemaGenerator.generate(Simple.class)).containsEntry("required", List.of("action"));
        }

        @Test
        void everyPrimitiveIsRequiredWhateverTheAnnotationSays() {
            // A primitive has no value that could mean "absent", so it cannot be optional.
            assertThat(ToolSchemaGenerator.generate(Primitives.class)).containsEntry("required",
                    List.of("count", "enabled"));
        }

        @Test
        void optionalIsNeverRequiredEvenWhenDeclaredSo() {
            assertThat(ToolSchemaGenerator.generate(Optionals.class)).doesNotContainKey("required");
        }
    }

    @Nested
    class TypeMapping {

        @Test
        void mapsEveryScalarFamily() {
            Map<String, Object> properties = propertiesOf(Types.class);

            assertThat(typeOf(properties, "string")).isEqualTo("string");
            assertThat(typeOf(properties, "integer")).isEqualTo("integer");
            assertThat(typeOf(properties, "bigger")).isEqualTo("integer");
            assertThat(typeOf(properties, "huge")).isEqualTo("integer");
            assertThat(typeOf(properties, "decimal")).isEqualTo("number");
            assertThat(typeOf(properties, "smaller")).isEqualTo("number");
            assertThat(typeOf(properties, "exact")).isEqualTo("number");
            assertThat(typeOf(properties, "flag")).isEqualTo("boolean");
            assertThat(typeOf(properties, "mode")).isEqualTo("string");
            assertThat(typeOf(properties, "names")).isEqualTo("array");
            assertThat(typeOf(properties, "numbers")).isEqualTo("array");
            assertThat(typeOf(properties, "array")).isEqualTo("array");
            assertThat(typeOf(properties, "free")).isEqualTo("object");
        }

        @Test
        void collectionsDeriveTheirItemType() {
            assertThat(propertyOf(Types.class, "names")).containsEntry("items", Map.of("type", "string"));
            assertThat(propertyOf(Types.class, "numbers")).containsEntry("items", Map.of("type", "integer"));
            assertThat(propertyOf(Types.class, "array")).containsEntry("items", Map.of("type", "string"));
        }

        @Test
        void aMapStaysOpenBecauseItsWholePurposeIsFreeFormInput() {
            assertThat(propertyOf(Types.class, "free")).containsOnlyKeys("type");
        }

        @Test
        void optionalMapsToItsElementType() {
            assertThat(propertyOf(Optionals.class, "mustNotMatter")).containsEntry("type", "string");
        }

        @SuppressWarnings("unchecked")
        private static String typeOf(Map<String, Object> properties, String name) {
            return (String) ((Map<String, Object>) properties.get(name)).get("type");
        }
    }

    @Nested
    class NestedRecords {

        @SuppressWarnings("unchecked")
        @Test
        void nestedRecordIsDerivedTheSameWayAndIsAlsoClosed() {
            Map<String, Object> inner = propertyOf(Outer.class, "inner");

            assertThat(inner).containsEntry("type", "object").containsEntry("additionalProperties", false)
                    .containsEntry("required", List.of("id")).containsEntry("description", "The nested one");
            assertThat(((Map<String, Object>) inner.get("properties")).keySet()).containsExactly("id", "weight");
        }
    }

    @Nested
    class HandWrittenHalf {

        @Test
        void descriptionIsCarriedThroughVerbatim() {
            assertThat(propertyOf(Simple.class, "action")).containsEntry("description", "The thing to do");
        }

        @Test
        void aDeclaredAllowedSetBecomesTheEnum() {
            record Declared(@ToolParam(allowed = {
                    "content", "count"}) String mode){
            }

            assertThat(propertyOf(Declared.class, "mode")).containsEntry("enum", List.of("content", "count"));
        }

        @Test
        void aJavaEnumSuppliesItsOwnConstants() {
            assertThat(propertyOf(Types.class, "mode")).containsEntry("enum", List.of("FAST", "THOROUGH"));
        }

        @Test
        void aBlankDescriptionIsOmittedRatherThanEmitted() {
            assertThat(propertyOf(Simple.class, "count")).containsOnlyKeys("type", "description");
            assertThat(propertyOf(Named.class, "-i")).containsOnlyKeys("type");
        }
    }

    @Nested
    class RejectedAtConstruction {

        @Test
        void nonRecordInputType() {
            assertThatThrownBy(() -> ToolSchemaGenerator.generate(NotARecord.class))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be a record");
        }

        @Test
        void unmappableComponentType() {
            // Better to fail here — in a test, at startup — than to ship a tool whose schema silently omits it.
            assertThatThrownBy(() -> ToolSchemaGenerator.generate(Unmappable.class))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thread")
                    .hasMessageContaining("no JSON Schema mapping");
        }

        @Test
        void rawCollectionWithNoElementType() {
            assertThatThrownBy(() -> ToolSchemaGenerator.generate(RawCollection.class))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("raw collection");
        }

        @Test
        void selfNestingRecord() {
            assertThatThrownBy(() -> ToolSchemaGenerator.generate(SelfNesting.class))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nests itself");
        }

        @Test
        void nullInputType() {
            assertThatThrownBy(() -> ToolSchemaGenerator.generate(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
