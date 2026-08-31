package at.aimon.core.subagent.task.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;

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
