package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StructuredOutputSupport — goal augmentation, fence stripping, parse + minimal schema validation")
class StructuredOutputSupportTest {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "required", List.of("name", "score"),
            "properties", Map.of("name", Map.of("type", "string"), "score", Map.of("type", "integer"), "tag",
                    Map.of("type", "string", "enum", List.of("a", "b"))));

    @Test
    @DisplayName("augmentGoal appends a JSON-only instruction and the serialized schema")
    void augmentGoal() {
        final String augmented = StructuredOutputSupport.augmentGoal("do it", SCHEMA);

        assertThat(augmented).startsWith("do it").contains("ONLY a single JSON object").contains("JSON Schema:")
                .contains("\"required\"");
    }

    @Test
    @DisplayName("parses a valid object matching the schema")
    void parsesValidObject() {
        assertThat(StructuredOutputSupport.parse("{\"name\": \"x\", \"score\": 5}", SCHEMA)).get()
                .satisfies(m -> assertThat(m).containsEntry("name", "x").containsEntry("score", 5));
    }

    @Test
    @DisplayName("parses a valid object wrapped in markdown fences")
    void parsesFencedObject() {
        assertThat(StructuredOutputSupport.parse("```json\n{\"name\":\"x\",\"score\":5,\"tag\":\"a\"}\n```", SCHEMA))
                .isPresent();
    }

    @Test
    @DisplayName("rejects non-JSON, a JSON array, missing required fields, wrong types, and enum violations")
    void rejectsInvalid() {
        assertThat(StructuredOutputSupport.parse("sorry, I can't", SCHEMA)).isEmpty();
        assertThat(StructuredOutputSupport.parse("[1, 2, 3]", SCHEMA)).isEmpty();
        assertThat(StructuredOutputSupport.parse("{\"name\":\"x\"}", SCHEMA)).isEmpty(); // missing score
        assertThat(StructuredOutputSupport.parse("{\"name\":\"x\",\"score\":\"five\"}", SCHEMA)).isEmpty(); // score
                                                                                                            // type
        assertThat(StructuredOutputSupport.parse("{\"name\":5,\"score\":5}", SCHEMA)).isEmpty(); // name type
        assertThat(StructuredOutputSupport.parse("{\"name\":\"x\",\"score\":5,\"tag\":\"z\"}", SCHEMA)).isEmpty(); // enum
    }

    @Test
    @DisplayName("a JSON null array element under an enum schema degrades to empty (regression: enum null NPE)")
    void enumNullInArrayIsSafe() {
        final Map<String, Object> schema = Map.of("type", "object", "required", List.of("tags"), "properties",
                Map.of("tags", Map.of("type", "array", "items", Map.of("enum", List.of("a", "b")))));

        assertThat(StructuredOutputSupport.parse("{\"tags\": [null]}", schema)).isEmpty();
    }

    @Test
    @DisplayName("a present-but-null property is validated against its type (null vs string → empty)")
    void presentNullPropertyRejected() {
        assertThat(StructuredOutputSupport.parse("{\"name\": null, \"score\": 5}", SCHEMA)).isEmpty();
    }

    @Test
    @DisplayName("validates nested objects and arrays")
    void validatesNested() {
        final Map<String, Object> schema = Map.of("type", "object", "required", List.of("items"), "properties",
                Map.of("items", Map.of("type", "array", "items", Map.of("type", "integer"))));

        assertThat(StructuredOutputSupport.parse("{\"items\":[1,2,3]}", schema)).isPresent();
        assertThat(StructuredOutputSupport.parse("{\"items\":[1,\"two\"]}", schema)).isEmpty();
    }

    @Test
    @DisplayName("null / non-object text yields empty")
    void nullText() {
        assertThat(StructuredOutputSupport.parse(null, SCHEMA)).isEmpty();
    }
}
