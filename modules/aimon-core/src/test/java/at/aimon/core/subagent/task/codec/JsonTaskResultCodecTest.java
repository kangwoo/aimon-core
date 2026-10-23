package at.aimon.core.subagent.task.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.subagent.task.TaskResult;

class JsonTaskResultCodecTest {

    private JsonTaskResultCodec codec;

    @BeforeEach
    void setUp() {
        codec = new JsonTaskResultCodec();
    }

    private static List<String> fieldNames(JsonNode node) {
        final List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void roundTripsAStructuredResultWholeAndInOrder() {
        final Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", "HIGH");
        finding.put("line", 42);
        finding.put("ratio", 0.5);
        finding.put("blocking", true);
        finding.put("existingCode", "x".repeat(200_000) + "\n```\n🚀");
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("findings", List.of(finding, Map.of("severity", "LOW")));
        payload.put("reviewed", List.of("a.java", "b.java"));
        final TaskResult result = TaskResult.builder().success(true).finalAnswer("short")
                .completionReason(CompletionReason.COMPLETED).structuredResult(payload).structuredResultRequired(true)
                .build();

        final TaskResult decoded = codec.decode(codec.encode(result));

        assertThat(decoded).isEqualTo(result);
        assertThat(decoded.getStructuredResult()).contains(payload);
        assertThat(decoded.getStructuredResult().orElseThrow().keySet()).containsExactly("findings", "reviewed");
        assertThat(decoded.isStructuredResultRequired()).isTrue();
    }

    @Test
    void aRequiredResultThatWasNotReportedRoundTrips() {
        final TaskResult result = TaskResult.builder().success(true).finalAnswer("prose").structuredResultRequired(true)
                .build();

        final TaskResult decoded = codec.decode(codec.encode(result));

        assertThat(decoded.getStructuredResult()).isEmpty();
        assertThat(decoded.isStructuredResultRequired()).isTrue();
    }

    @Test
    void aResultWithoutAStructuredResultEncodesExactlyAsBefore() throws Exception {
        final String encoded = codec.encode(TaskResult.builder().success(true).finalAnswer("a").build());

        assertThat(fieldNames(new ObjectMapper().readTree(encoded))).containsExactly("version", "success",
                "finalAnswer", "completionReason", "iterationCount", "durationMillis", "totalTokens",
                "summaryTruncated");
    }

    @Test
    void anOldRecordReadsAsReportingNoneAndRequiringNone() {
        final TaskResult decoded = codec.decode("{\"version\":1,\"success\":true,\"finalAnswer\":\"a\","
                + "\"completionReason\":\"COMPLETED\",\"iterationCount\":1,\"durationMillis\":2,"
                + "\"totalTokens\":3,\"summaryTruncated\":false}");

        assertThat(decoded.getStructuredResult()).isEmpty();
        assertThat(decoded.isStructuredResultRequired()).isFalse();
        assertThat(decoded.getSummary()).isEqualTo("a");
    }

    @Test
    void aNewRecordStaysReadableToACodecThatIgnoresTheNewFields() throws Exception {
        // What an older node does with a newer record: same version, the unknown fields skipped.
        final String encoded = codec.encode(TaskResult.builder().success(true).finalAnswer("a")
                .structuredResult(Map.of("findings", List.of())).structuredResultRequired(true).build());
        final com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper()
                .readTree(encoded);

        assertThat(root.path("version").asInt()).isEqualTo(JsonTaskResultCodec.FORMAT_VERSION).isEqualTo(1);
        root.remove(List.of("structuredResult", "structuredResultRequired"));
        assertThat(codec.decode(root.toString()).getSummary()).isEqualTo("a");
    }

    @Test
    void aStructuredResultThatIsNotAnObjectReadsAsAbsent() {
        final TaskResult decoded = codec
                .decode("{\"version\":1,\"success\":true,\"structuredResult\":[1,2],\"futureField\":{}}");

        assertThat(decoded.getStructuredResult()).isEmpty();
        assertThat(decoded.isSuccess()).isTrue();
    }

    @Test
    void toStringNamesTheStructuredResultOnlyWhenThereIsOne() {
        assertThat(TaskResult.builder().success(true).build().toString()).doesNotContain("structuredResult");
        assertThat(TaskResult.builder().success(true).structuredResultRequired(true).build().toString())
                .contains("structuredResult=missing");
        assertThat(TaskResult.builder().success(true).structuredResult(Map.of("a", 1)).build().toString())
                .contains("structuredResult=reported");
    }

    @Test
    void encodeRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> codec.encode(null));
    }

    @Test
    void decodeRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> codec.decode(null));
    }

    @Test
    void roundTripsASuccessfulResult() {
        final TaskResult result = TaskResult.builder().success(true).finalAnswer("the answer 🚀")
                .completionReason(CompletionReason.COMPLETED).iterationCount(4).durationMillis(1_234L).totalTokens(555)
                .build();

        assertThat(codec.decode(codec.encode(result))).isEqualTo(result);
    }

    @Test
    void roundTripsAFailedResultWithItsTruncationFlag() {
        final TaskResult result = TaskResult.builder().success(false).errorMessage("kaboom")
                .completionReason(CompletionReason.WALL_CLOCK_EXCEEDED).iterationCount(9).durationMillis(90_000L)
                .totalTokens(12).summaryTruncated(true).build();

        assertThat(codec.decode(codec.encode(result))).isEqualTo(result);
    }

    @Test
    void absentTextFieldsAreOmittedRatherThanWrittenAsNull() throws Exception {
        final String encoded = codec.encode(TaskResult.builder().success(true).build());

        assertThat(fieldNames(new ObjectMapper().readTree(encoded))).doesNotContain("finalAnswer", "errorMessage");
    }

    @Test
    void absentTextFieldsDecodeBackToEmptyOptionals() {
        final TaskResult decoded = codec.decode(codec.encode(TaskResult.builder().success(true).build()));

        assertThat(decoded.getFinalAnswer()).isEmpty();
        assertThat(decoded.getErrorMessage()).isEmpty();
        assertThat(decoded.getSummary()).isEmpty();
    }

    @Test
    void encodedDocumentCarriesTheFormatVersion() throws Exception {
        final JsonNode root = new ObjectMapper().readTree(codec.encode(TaskResult.builder().success(true).build()));

        assertThat(root.get("version").asInt()).isEqualTo(JsonTaskResultCodec.FORMAT_VERSION);
    }

    @Test
    void decodeRejectsAnUnsupportedFormatVersion() {
        assertThatExceptionOfType(TaskResultCodecException.class)
                .isThrownBy(() -> codec.decode("{\"version\":99,\"success\":true}"))
                .withMessageContaining("Unsupported task result format version: 99");
    }

    @Test
    void decodeRejectsAMissingFormatVersion() {
        assertThatExceptionOfType(TaskResultCodecException.class).isThrownBy(() -> codec.decode("{\"success\":true}"));
    }

    @Test
    void decodeRejectsANonObjectDocument() {
        assertThatExceptionOfType(TaskResultCodecException.class).isThrownBy(() -> codec.decode("[1,2,3]"))
                .withMessageContaining("not a JSON object");
    }

    @Test
    void decodeRejectsMalformedJson() {
        assertThatExceptionOfType(TaskResultCodecException.class).isThrownBy(() -> codec.decode("{not json"));
    }

    @Test
    void unknownCompletionReasonDegradesInsteadOfDiscardingTheAnswer() {
        // A newer node may name its stop reason something this build has not heard of. The answer still matters more
        // than the label, so the reason falls back to the coarse fact the success flag already carries.
        final TaskResult decoded = codec.decode("{\"version\":1,\"success\":true,\"finalAnswer\":\"the answer\","
                + "\"completionReason\":\"INVENTED_BY_A_NEWER_NODE\"}");

        assertThat(decoded.getFinalAnswer()).contains("the answer");
        assertThat(decoded.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    @Test
    void unknownCompletionReasonOnAFailureDegradesToError() {
        final TaskResult decoded = codec.decode("{\"version\":1,\"success\":false,\"errorMessage\":\"kaboom\","
                + "\"completionReason\":\"INVENTED_BY_A_NEWER_NODE\"}");

        assertThat(decoded.getErrorMessage()).contains("kaboom");
        assertThat(decoded.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
    }

    @Test
    void aBlockedResultRoundTripsWithItsReason() {
        final TaskResult blocked = TaskResult.builder().success(false)
                .errorMessage("Execution blocked by OnStart hook [SUBAGENT/explorer]: no forks today")
                .completionReason(CompletionReason.BLOCKED).build();

        assertThat(codec.decode(codec.encode(blocked)).getCompletionReason()).isEqualTo(CompletionReason.BLOCKED);
    }

    @Test
    void unknownTopLevelFieldsAreIgnored() {
        final TaskResult decoded = codec
                .decode("{\"version\":1,\"success\":true,\"finalAnswer\":\"a\",\"fieldFromTheFuture\":{\"x\":1}}");

        assertThat(decoded.getFinalAnswer()).contains("a");
    }

    @Test
    void missingCountersDecodeToZero() {
        final TaskResult decoded = codec.decode("{\"version\":1,\"success\":true}");

        assertThat(decoded.getIterationCount()).isZero();
        assertThat(decoded.getDurationMillis()).isZero();
        assertThat(decoded.getTotalTokens()).isZero();
        assertThat(decoded.isSummaryTruncated()).isFalse();
    }
}
