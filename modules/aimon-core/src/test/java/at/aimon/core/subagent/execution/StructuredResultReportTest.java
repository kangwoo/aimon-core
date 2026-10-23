package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ToolUse;
import at.aimon.core.subagent.SubagentExecutionPolicy;
import at.aimon.core.subagent.SubagentPolicyCapability;

@DisplayName("StructuredResultReport")
class StructuredResultReportTest {

    private static final Map<String, Object> SCHEMA = DefaultSubagentExecutorStructuredResultTest.FINDINGS_SCHEMA;

    @Test
    @DisplayName("a fitting payload has no violations")
    void fittingPayload() {
        assertThat(StructuredResultReport.validate(SCHEMA, DefaultSubagentExecutorStructuredResultTest
                .findings(DefaultSubagentExecutorStructuredResultTest.finding("HIGH")))).isEmpty();
    }

    @Test
    @DisplayName("violations at every level name where they are")
    void nestedViolations() {
        final Map<String, Object> unknownKey = new LinkedHashMap<>(
                DefaultSubagentExecutorStructuredResultTest.finding("LOW"));
        unknownKey.put("severty", "LOW");
        final Map<String, Object> payload = Map.of("findings", Arrays.asList("not an object", unknownKey, null));

        final List<String> violations = StructuredResultReport.validate(SCHEMA, payload);

        assertThat(violations).anySatisfy(v -> assertThat(v).startsWith("Parameter 'findings[0]' must be an object"))
                .anySatisfy(v -> assertThat(v).startsWith("In 'findings[1]': Unknown parameter 'severty'.")).anySatisfy(
                        v -> assertThat(v).isEqualTo("Item 'findings[2]' is null, which its schema does not allow."));
    }

    @Test
    @DisplayName("the top level is checked as the tool-input gate checks it: required, type, unknown keys")
    void topLevel() {
        assertThat(StructuredResultReport.validate(SCHEMA, Map.of()))
                .containsExactly("Parameter 'findings' is required (type: array). The tool was not executed.");
        assertThat(StructuredResultReport.validate(SCHEMA, Map.of("findings", List.of(), "extra", 1))).singleElement()
                .asString().startsWith("Unknown parameter 'extra'");
    }

    @Test
    @DisplayName("arrays of scalars check each item's type and enum; composition keywords are skipped")
    void scalarItemsAndComposition() {
        final Map<String, Object> schema = Map.of("type", "object", "properties",
                Map.of("tags", Map.of("type", "array", "items", Map.of("type", "string", "enum", List.of("a", "b"))),
                        "free", Map.of("type", "array", "items", Map.of("oneOf", List.of()))));

        assertThat(StructuredResultReport.validate(schema,
                Map.of("tags", List.of("a", "c", 3), "free", List.of(1, "x", Map.of()))))
                .containsExactly("Parameter 'tags[1]' must be one of [a, b], but was 'c'. The tool was not executed.",
                        "Parameter 'tags[2]' must be a string. The tool was not executed.");
    }

    @Test
    @DisplayName("a type given as a list, or left out beside properties/items, is still looked into")
    void typeListsAndAbsentTypes() {
        final Map<String, Object> inner = Map.of("properties", Map.of("level", Map.of("type", "string")), "required",
                List.of("level"));
        final Map<String, Object> schema = Map.of("type", "object", "properties",
                Map.of("one",
                        Map.of("type", List.of("object", "null"), "properties",
                                Map.of("level", Map.of("type", "string")), "required", List.of("level")),
                        "many", Map.of("type", List.of("array", "null"), "items", inner), "loose",
                        Map.of("items", inner)));

        assertThat(StructuredResultReport.validate(schema,
                Map.of("one", Map.of(), "many", List.of(Map.of("level", 1)), "loose", List.of(Map.of()))))
                .containsExactlyInAnyOrder(
                        "In 'one': Parameter 'level' is required (type: string). The tool was not executed.",
                        "In 'many[0]': Parameter 'level' must be a string. The tool was not executed.",
                        "In 'loose[0]': Parameter 'level' is required (type: string). The tool was not executed.");
    }

    @Test
    @DisplayName("a null array item is rejected unless the item schema's type lists null")
    void nullItems() {
        final Map<String, Object> strict = Map.of("type", "object", "properties",
                Map.of("findings", Map.of("type", "array", "items", Map.of("type", "object"))));
        final Map<String, Object> nullable = Map.of("type", "object", "properties",
                Map.of("findings", Map.of("type", "array", "items", Map.of("type", List.of("object", "null")))));
        final Map<String, Object> payload = Map.of("findings", Arrays.asList(Map.of(), null));

        assertThat(StructuredResultReport.validate(strict, payload))
                .containsExactly("Item 'findings[1]' is null, which its schema does not allow.");
        assertThat(StructuredResultReport.validate(nullable, payload)).isEmpty();
        // Kept in place in the delivered copy.
        assertThat(StructuredResultReport.normalise(nullable, payload).get("findings")).asList().hasSize(2)
                .containsNull();
    }

    @Test
    @DisplayName("numbers are normalised to the schema: whole values become integers, enum values match numerically")
    void numbers() {
        final Map<String, Object> schema = Map.of("type", "object", "properties",
                Map.of("line", Map.of("type", "integer"), "priority", Map.of("type", "number", "enum", List.of(1, 2)),
                        "ratio", Map.of("type", "number"), "lines",
                        Map.of("type", "array", "items", Map.of("type", "integer"))));
        final Map<String, Object> input = Map.of("line", 3.0, "priority", 1.0, "ratio", 0.5, "lines",
                List.of(1.0, 5_000_000_000.0, 7L));

        final StructuredResultReport.Outcome outcome = StructuredResultReport.judge(
                SubagentExecutionPolicy.builder("w").resultSchema(schema).build(),
                ToolUse.of("r", "ReportResult", input));

        final Map<String, Object> payload = outcome.payload().orElseThrow();
        assertThat(payload.get("line")).isEqualTo(3);
        assertThat(payload.get("priority")).isEqualTo(1);
        assertThat(payload.get("ratio")).isEqualTo(0.5);
        assertThat(payload.get("lines")).asList().containsExactly(1, 5_000_000_000L, 7);
        assertThat(StructuredResultReport.toJson(payload)).contains("\"line\":3").contains("\"priority\":1")
                .doesNotContain("3.0").doesNotContain("1.0");
        // A fractional value for an integer is still a violation.
        assertThat(StructuredResultReport.judge(SubagentExecutionPolicy.builder("w").resultSchema(schema).build(),
                ToolUse.of("r", "ReportResult", Map.of("line", 3.5))).payload()).isEmpty();
    }

    @Test
    @DisplayName("an accepted call answers with an ack and hands back a deep, unmodifiable copy")
    void acceptedCall() {
        final List<Object> list = new ArrayList<>(List.of(DefaultSubagentExecutorStructuredResultTest.finding("HIGH")));
        final Map<String, Object> input = new HashMap<>(Map.of("findings", list));

        final StructuredResultReport.Outcome outcome = StructuredResultReport.judge(
                DefaultSubagentExecutorStructuredResultTest.policy().build(), ToolUse.of("r", "ReportResult", input));

        assertThat(outcome.answer().isError()).isFalse();
        assertThat(outcome.answer().getContent()).startsWith("Recorded (");
        final Map<String, Object> payload = outcome.payload().orElseThrow();
        list.clear();
        assertThat(payload.get("findings")).asList().hasSize(1);
        assertThatThrownBy(() -> payload.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the size cap is on compact JSON")
    void sizeCap() {
        final Map<String, Object> input = DefaultSubagentExecutorStructuredResultTest
                .findings(DefaultSubagentExecutorStructuredResultTest.finding("HIGH"));
        final int length = StructuredResultReport.toJson(input).length();

        assertThat(StructuredResultReport
                .judge(DefaultSubagentExecutorStructuredResultTest.policy().resultPayloadMaxChars(length).build(),
                        ToolUse.of("r", "ReportResult", input))
                .payload()).isPresent();
        assertThat(StructuredResultReport
                .judge(DefaultSubagentExecutorStructuredResultTest.policy().resultPayloadMaxChars(length - 1).build(),
                        ToolUse.of("r", "ReportResult", input))
                .payload()).isEmpty();
    }

    @Test
    @DisplayName("JSON is one line, so a payload quoting a fence cannot close the block it is shown in")
    void jsonIsOneLine() {
        final String json = StructuredResultReport.toJson(Map.of("existingCode", "```\nend\n```"));

        assertThat(json).doesNotContain("\n").contains("\\n```");
    }

    @Test
    @DisplayName("the policy: schema must be an object schema, the cap needs a schema, and the axis is a capability")
    void policyAxis() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubagentExecutionPolicy.builder("w").resultSchema(Map.of("type", "array")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubagentExecutionPolicy.builder("w").resultPayloadMaxChars(10).build());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubagentExecutionPolicy.builder("w").resultSchema(SCHEMA).resultPayloadMaxChars(0));

        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("w").resultSchema(SCHEMA).build();
        assertThat(policy.getCapabilities()).containsExactly(SubagentPolicyCapability.STRUCTURED_RESULT);
        assertThat(policy.requiresStructuredResult()).isTrue();
        assertThat(policy.getResultPayloadMaxChars())
                .isEqualTo(SubagentExecutionPolicy.DEFAULT_RESULT_PAYLOAD_MAX_CHARS);
        assertThat(policy.getResultSchema()).contains(SCHEMA);
        assertThat(policy).isEqualTo(SubagentExecutionPolicy.builder("w").resultSchema(SCHEMA).build())
                .isNotEqualTo(
                        SubagentExecutionPolicy.builder("w").resultSchema(SCHEMA).resultPayloadMaxChars(5).build())
                .hasSameHashCodeAs(SubagentExecutionPolicy.builder("w").resultSchema(SCHEMA).build());
        assertThat(policy.toString()).contains("resultSchema=true").doesNotContain("severity");

        final SubagentExecutionPolicy none = SubagentExecutionPolicy.builder("w").build();
        assertThat(none.requiresStructuredResult()).isFalse();
        assertThat(none.getResultSchema()).isEmpty();
        assertThat(none.getCapabilities()).isEmpty();
    }

    @Test
    @DisplayName("the policy copies its schema: a later change to the caller's map does not reach it")
    void policyCopiesSchema() {
        final Map<String, Object> schema = new LinkedHashMap<>(SCHEMA);
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("w").resultSchema(schema).build();

        schema.put("required", List.of());

        assertThat(policy.getResultSchema().orElseThrow().get("required")).isEqualTo(List.of("findings"));
        assertThatThrownBy(() -> policy.getResultSchema().orElseThrow().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
