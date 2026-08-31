package at.aimon.core.agent.tool.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;

@DisplayName("DefaultToolInputSchemaValidator Tests")
class DefaultToolInputSchemaValidatorTest {

    private final DefaultToolInputSchemaValidator validator = new DefaultToolInputSchemaValidator();

    @Nested
    @DisplayName("Required parameters")
    class RequiredParameters {

        @Test
        @DisplayName("A missing required parameter is reported with its declared type")
        void reportsMissingRequiredParameter() {
            Map<String, Object> schema = Map.of("type", "object", "properties",
                    Map.of("file_path", Map.of("type", "string")), "required", List.of("file_path"));

            SchemaValidationResult result = validator.validate(schema, ToolInput.of());

            assertThat(result.isValid()).isFalse();
            assertThat(result.getViolations())
                    .containsExactly("Parameter 'file_path' is required (type: string). The tool was not executed.");
        }

        @Test
        @DisplayName("required is checked even when the schema declares no properties")
        void checksRequiredWithoutProperties() {
            Map<String, Object> schema = Map.of("type", "object", "required", List.of("query"));

            SchemaValidationResult result = validator.validate(schema, ToolInput.of());

            assertThat(result.getViolations())
                    .containsExactly("Parameter 'query' is required. The tool was not executed.");
        }

        @Test
        @DisplayName("A parameter supplied as null is reported as missing, not as a type error")
        void nullValueIsReportedAsMissing() {
            Map<String, Object> schema = Map.of("type", "object", "properties",
                    Map.of("file_path", Map.of("type", "string")), "required", List.of("file_path"));
            Map<String, Object> data = new HashMap<>();
            data.put("file_path", null);

            SchemaValidationResult result = validator.validate(schema, ToolInput.of(data));

            assertThat(result.getViolations())
                    .containsExactly("Parameter 'file_path' is required (type: string). The tool was not executed.");
        }

        @Test
        @DisplayName("A supplied required parameter passes")
        void suppliedRequiredParameterPasses() {
            Map<String, Object> schema = Map.of("type", "object", "properties",
                    Map.of("file_path", Map.of("type", "string")), "required", List.of("file_path"));

            SchemaValidationResult result = validator.validate(schema, ToolInput.of("file_path", "/tmp/a.txt"));

            assertThat(result.isValid()).isTrue();
            assertThat(result.getViolations()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Declared types")
    class DeclaredTypes {

        @Test
        @DisplayName("A value of the wrong type is reported")
        void rejectsWrongType() {
            SchemaValidationResult result = validator.validate(numberSchema(), ToolInput.of("count", "12"));

            assertThat(result.getViolations())
                    .containsExactly("Parameter 'count' must be a number. The tool was not executed.");
        }

        @Test
        @DisplayName("integer rejects a genuine fractional part")
        void integerRejectsFraction() {
            Map<String, Object> schema = Map.of("type", "object", "properties",
                    Map.of("offset", Map.of("type", "integer")));

            SchemaValidationResult result = validator.validate(schema, ToolInput.of("offset", 3.5));

            assertThat(result.getViolations())
                    .containsExactly("Parameter 'offset' must be an integer. The tool was not executed.");
        }

        @Test
        @DisplayName("integer accepts a whole number that arrived as a double or a BigDecimal")
        void integerAcceptsWholeNumbers() {
            Map<String, Object> schema = Map.of("type", "object", "properties",
                    Map.of("offset", Map.of("type", "integer")));

            assertThat(validator.validate(schema, ToolInput.of("offset", 3.0)).isValid()).isTrue();
            assertThat(validator.validate(schema, ToolInput.of("offset", new BigDecimal("3.00"))).isValid()).isTrue();
            assertThat(validator.validate(schema, ToolInput.of("offset", 3L)).isValid()).isTrue();
        }

        @Test
        @DisplayName("number accepts an integer")
        void numberAcceptsInteger() {
            assertThat(validator.validate(numberSchema(), ToolInput.of("count", 12)).isValid()).isTrue();
        }

        @Test
        @DisplayName("array accepts a list, object accepts a map")
        void acceptsStructuredTypes() {
            Map<String, Object> schema = Map.of("type", "object", "properties",
                    Map.of("todos", Map.of("type", "array"), "env", Map.of("type", "object")));

            SchemaValidationResult result = validator.validate(schema,
                    ToolInput.of("todos", List.of("a"), "env", Map.of("K", "V")));

            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("A property that fails its type check is not also reported against its enum")
        void typeFailureDoesNotAlsoReportEnum() {
            Map<String, Object> schema = Map.of("type", "object", "properties",
                    Map.of("mode", Map.of("type", "string", "enum", List.of("content", "count"))));

            SchemaValidationResult result = validator.validate(schema, ToolInput.of("mode", 5));

            assertThat(result.getViolations())
                    .containsExactly("Parameter 'mode' must be a string. The tool was not executed.");
        }

        private Map<String, Object> numberSchema() {
            return Map.of("type", "object", "properties", Map.of("count", Map.of("type", "number")));
        }
    }

    @Nested
    @DisplayName("Enum values")
    class EnumValues {

        @Test
        @DisplayName("A value outside the enum is reported with the whole allowed list")
        void rejectsValueOutsideEnum() {
            SchemaValidationResult result = validator.validate(enumSchema(), ToolInput.of("output_mode", "lines"));

            assertThat(result.getViolations()).containsExactly("Parameter 'output_mode' must be one of "
                    + "[content, files_with_matches, count], but was 'lines'. The tool was not executed.");
        }

        @Test
        @DisplayName("A value inside the enum passes")
        void acceptsValueInEnum() {
            assertThat(validator.validate(enumSchema(), ToolInput.of("output_mode", "count")).isValid()).isTrue();
        }

        private Map<String, Object> enumSchema() {
            return Map.of("type", "object", "properties", Map.of("output_mode",
                    Map.of("type", "string", "enum", List.of("content", "files_with_matches", "count"))));
        }
    }

    @Nested
    @DisplayName("Unknown parameters")
    class UnknownParameters {

        @Test
        @DisplayName("An undeclared parameter passes when the schema does not opt into strictness")
        void ignoredWithoutAdditionalPropertiesFalse() {
            Map<String, Object> permissive = Map.of("type", "object", "properties",
                    Map.of("file_path", Map.of("type", "string")));
            Map<String, Object> explicitlyOpen = Map.of("type", "object", "additionalProperties", true, "properties",
                    Map.of("file_path", Map.of("type", "string")));

            assertThat(validator.validate(permissive, ToolInput.of("nonesuch", "x")).isValid()).isTrue();
            assertThat(validator.validate(explicitlyOpen, ToolInput.of("nonesuch", "x")).isValid()).isTrue();
        }

        @Test
        @DisplayName("An undeclared parameter is reported when additionalProperties is false")
        void reportedWhenAdditionalPropertiesFalse() {
            SchemaValidationResult result = validator.validate(strictSchema(), ToolInput.of("nonesuch", "x"));

            assertThat(result.getViolations())
                    .containsExactly("Unknown parameter 'nonesuch'. The tool was not executed.");
        }

        @Test
        @DisplayName("A near miss carries the name it probably meant")
        void suggestsTheUniqueNearMiss() {
            SchemaValidationResult result = validator.validate(strictSchema(), ToolInput.of("file_pat", "/tmp/a.txt"));

            assertThat(result.getViolations()).containsExactly(
                    "Unknown parameter 'file_pat'. Did you mean 'file_path'? The tool was not executed.");
        }

        @Test
        @DisplayName("No suggestion when every declared name is further than two edits away")
        void noSuggestionWhenTooFar() {
            SchemaValidationResult result = validator.validate(strictSchema(), ToolInput.of("recursive", true));

            assertThat(result.getViolations())
                    .containsExactly("Unknown parameter 'recursive'. The tool was not executed.");
        }

        @Test
        @DisplayName("No suggestion when two declared names are equally close")
        void noSuggestionWhenCandidatesTie() {
            Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false, "properties",
                    Map.of("max", Map.of("type", "number"), "min", Map.of("type", "number")));

            SchemaValidationResult result = validator.validate(schema, ToolInput.of("mix", 1));

            assertThat(result.getViolations()).containsExactly("Unknown parameter 'mix'. The tool was not executed.");
        }

        private Map<String, Object> strictSchema() {
            return Map.of("type", "object", "additionalProperties", false, "properties",
                    Map.of("file_path", Map.of("type", "string"), "limit", Map.of("type", "number")));
        }
    }

    /**
     * The degradation table of the design's §4.5d, one test per row.
     *
     * <p>
     * These are the tests that keep an unfamiliar MCP server from having its perfectly good calls rejected: every
     * construct this validator cannot decide must <em>pass</em>, never fail.
     */
    @Nested
    @DisplayName("Ignorance never blocks a call")
    class Degradation {

        @Test
        @DisplayName("A null, empty, or non-object schema is not judged at all")
        void schemasNotJudged() {
            ToolInput anything = ToolInput.of("whatever", 1);

            assertThat(validator.validate(null, anything).isValid()).isTrue();
            assertThat(validator.validate(Map.of(), anything).isValid()).isTrue();
            assertThat(validator.validate(Map.of("required", List.of("query")), anything).isValid()).isTrue();
            assertThat(validator.validate(Map.of("type", "string", "required", List.of("query")), anything).isValid())
                    .isTrue();
        }

        @Test
        @DisplayName("A property using $ref / oneOf / anyOf / allOf / not is skipped, its siblings are not")
        void compositionKeywordsSkipOnlyTheirOwnProperty() {
            for (String keyword : List.of("$ref", "oneOf", "anyOf", "allOf", "not")) {
                Map<String, Object> schema = Map.of("type", "object", "properties",
                        Map.of("composed", Map.of(keyword, "#/definitions/Whatever", "type", "string"), "plain",
                                Map.of("type", "string")));

                SchemaValidationResult result = validator.validate(schema, ToolInput.of("composed", 1, "plain", 2));

                assertThat(result.getViolations()).as("keyword %s", keyword)
                        .containsExactly("Parameter 'plain' must be a string. The tool was not executed.");
            }
        }

        @Test
        @DisplayName("A type name we do not know skips that property")
        void unknownTypeNameSkipsTheProperty() {
            Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("id", Map.of("type", "uuid")));

            assertThat(validator.validate(schema, ToolInput.of("id", 42)).isValid()).isTrue();
        }

        @Test
        @DisplayName("A type union passes when the value matches any listed member")
        void typeUnionMatchesAnyMember() {
            Map<String, Object> schema = Map.of("type", "object", "properties",
                    Map.of("value", Map.of("type", List.of("string", "number"))));

            assertThat(validator.validate(schema, ToolInput.of("value", "text")).isValid()).isTrue();
            assertThat(validator.validate(schema, ToolInput.of("value", 7)).isValid()).isTrue();
            assertThat(validator.validate(schema, ToolInput.of("value", true)).getViolations())
                    .containsExactly("Parameter 'value' must be a string or number. The tool was not executed.");
        }

        @Test
        @DisplayName("A type union containing a name we do not know skips the property")
        void unknownMemberSkipsTheUnion() {
            Map<String, Object> schema = Map.of("type", "object", "properties",
                    Map.of("value", Map.of("type", List.of("string", "uuid"))));

            assertThat(validator.validate(schema, ToolInput.of("value", 7)).isValid()).isTrue();
        }

        @Test
        @DisplayName("Nesting stops after one level — a declared object is checked for being one, not for its contents")
        void nestingStopsAfterOneLevel() {
            Map<String, Object> schema = Map.of("type", "object", "properties",
                    Map.of("filter", Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")),
                            "required", List.of("name"), "additionalProperties", false)));

            SchemaValidationResult result = validator.validate(schema, ToolInput.of("filter", Map.of("nope", 1)));

            assertThat(result.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("Multiple violations")
    class MultipleViolations {

        @Test
        @DisplayName("Every mismatch in one call is reported together")
        void reportsEveryMismatch() {
            Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false, "properties",
                    Map.of("file_path", Map.of("type", "string"), "limit", Map.of("type", "number"), "mode",
                            Map.of("type", "string", "enum", List.of("read", "write"))),
                    "required", List.of("file_path"));

            SchemaValidationResult result = validator.validate(schema,
                    ToolInput.of("limit", "ten", "mode", "append", "extra", 1));

            assertThat(result.isValid()).isFalse();
            assertThat(result.getViolations()).containsExactlyInAnyOrder(
                    "Parameter 'file_path' is required (type: string). The tool was not executed.",
                    "Parameter 'limit' must be a number. The tool was not executed.",
                    "Parameter 'mode' must be one of [read, write], but was 'append'. The tool was not executed.",
                    "Unknown parameter 'extra'. The tool was not executed.");
        }
    }
}
