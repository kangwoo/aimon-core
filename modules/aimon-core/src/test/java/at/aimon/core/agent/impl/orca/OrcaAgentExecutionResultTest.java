package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.cost.CostSummary;
import at.aimon.core.llm.cost.Money;

@DisplayName("OrcaAgentExecutionResult Tests")
class OrcaAgentExecutionResultTest {

    @Nested
    @DisplayName("Backward Compatibility (3-param factory methods)")
    class BackwardCompatibility {

        @Test
        @DisplayName("Success with 3 params should return empty artifacts list")
        void successWith3ParamsShouldReturnEmptyArtifacts() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getFinalAnswer()).isEqualTo("answer");
            assertThat(result.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Failure with 3 params should return empty artifacts list")
        void failureWith3ParamsShouldReturnEmptyArtifacts() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.failure("error", createSessionRecord(),
                    createMetadata());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).isEqualTo("error");
            assertThat(result.getArtifacts()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Success with Artifacts (4-param factory method)")
    class SuccessWithArtifacts {

        @Test
        @DisplayName("Should create success result with artifacts")
        void shouldCreateSuccessWithArtifacts() {
            List<FileArtifact> artifacts = List.of(createArtifact("/reports/sales.csv", "sales.csv"),
                    createArtifact("/reports/summary.pdf", "summary.pdf"));

            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata(), artifacts);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getFinalAnswer()).isEqualTo("answer");
            assertThat(result.getArtifacts()).hasSize(2);
            assertThat(result.getArtifacts().get(0).getFileName()).isEqualTo("sales.csv");
            assertThat(result.getArtifacts().get(1).getFileName()).isEqualTo("summary.pdf");
        }

        @Test
        @DisplayName("Should create success result with empty artifacts list")
        void shouldCreateSuccessWithEmptyArtifacts() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata(), List.of());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Should reject null finalAnswer")
        void shouldRejectNullFinalAnswer() {
            assertThatThrownBy(
                    () -> OrcaAgentExecutionResult.success(null, createSessionRecord(), createMetadata(), List.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null artifacts in success")
        void shouldRejectNullArtifactsInSuccess() {
            assertThatThrownBy(
                    () -> OrcaAgentExecutionResult.success("answer", createSessionRecord(), createMetadata(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Failure with Artifacts (4-param factory method)")
    class FailureWithArtifacts {

        @Test
        @DisplayName("Should create failure result with partial artifacts")
        void shouldCreateFailureWithPartialArtifacts() {
            List<FileArtifact> artifacts = List.of(createArtifact("/partial/output.csv", "output.csv"));

            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.failure("something went wrong",
                    createSessionRecord(), createMetadata(), artifacts);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).isEqualTo("something went wrong");
            assertThat(result.getFinalAnswer()).isNull();
            assertThat(result.getArtifacts()).hasSize(1);
            assertThat(result.getArtifacts().get(0).getFileName()).isEqualTo("output.csv");
        }

        @Test
        @DisplayName("Should reject null errorMessage")
        void shouldRejectNullErrorMessage() {
            assertThatThrownBy(
                    () -> OrcaAgentExecutionResult.failure(null, createSessionRecord(), createMetadata(), List.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null artifacts in failure")
        void shouldRejectNullArtifactsInFailure() {
            assertThatThrownBy(
                    () -> OrcaAgentExecutionResult.failure("error", createSessionRecord(), createMetadata(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Artifacts Immutability")
    class ArtifactsImmutability {

        @Test
        @DisplayName("Artifacts list should be unmodifiable")
        void artifactsListShouldBeUnmodifiable() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata(), List.of(createArtifact("/a.csv", "a.csv")));

            List<FileArtifact> artifacts = result.getArtifacts();

            assertThatThrownBy(() -> artifacts.add(createArtifact("/hack.txt", "hack.txt")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("AgentExecutionResult Interface Contract")
    class InterfaceContract {

        @Test
        @DisplayName("Should implement AgentExecutionResult interface")
        void shouldImplementInterface() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata());

            assertThat(result).isInstanceOf(AgentExecutionResult.class);
        }

        @Test
        @DisplayName("getArtifacts should return artifacts via interface reference")
        void getArtifactsShouldWorkViaInterfaceReference() {
            List<FileArtifact> artifacts = List.of(createArtifact("/data.json", "data.json"));
            AgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata(), artifacts);

            assertThat(result.getArtifacts()).hasSize(1);
            assertThat(result.getArtifacts().get(0).getFileName()).isEqualTo("data.json");
        }
    }

    @Nested
    @DisplayName("Equals and HashCode with Artifacts")
    class EqualsAndHashCode {

        @Test
        @DisplayName("Results with same artifacts should be equal")
        void resultsWithSameArtifactsShouldBeEqual() {
            List<FileArtifact> artifacts = List.of(createArtifact("/a.csv", "a.csv"));
            SessionSnapshot snapshot = createSessionRecord();
            ExecutionMetadata metadata = createMetadata();

            OrcaAgentExecutionResult result1 = OrcaAgentExecutionResult.success("answer", snapshot, metadata,
                    artifacts);
            OrcaAgentExecutionResult result2 = OrcaAgentExecutionResult.success("answer", snapshot, metadata,
                    artifacts);

            assertThat(result1).isEqualTo(result2);
            assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
        }

        @Test
        @DisplayName("Results with different artifacts should not be equal")
        void resultsWithDifferentArtifactsShouldNotBeEqual() {
            SessionSnapshot snapshot = createSessionRecord();
            ExecutionMetadata metadata = createMetadata();

            OrcaAgentExecutionResult result1 = OrcaAgentExecutionResult.success("answer", snapshot, metadata,
                    List.of(createArtifact("/a.csv", "a.csv")));
            OrcaAgentExecutionResult result2 = OrcaAgentExecutionResult.success("answer", snapshot, metadata,
                    List.of(createArtifact("/b.csv", "b.csv")));

            assertThat(result1).isNotEqualTo(result2);
        }

        @Test
        @DisplayName("Result with artifacts should not equal result without artifacts")
        void resultWithArtifactsShouldNotEqualResultWithout() {
            SessionSnapshot snapshot = createSessionRecord();
            ExecutionMetadata metadata = createMetadata();

            OrcaAgentExecutionResult result1 = OrcaAgentExecutionResult.success("answer", snapshot, metadata);
            OrcaAgentExecutionResult result2 = OrcaAgentExecutionResult.success("answer", snapshot, metadata,
                    List.of(createArtifact("/a.csv", "a.csv")));

            assertThat(result1).isNotEqualTo(result2);
        }
    }

    @Nested
    @DisplayName("ToString with Artifacts")
    class ToStringTests {

        @Test
        @DisplayName("Success toString should include artifact count when present")
        void successToStringShouldIncludeArtifactCount() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata(), List.of(createArtifact("/a.csv", "a.csv"), createArtifact("/b.csv", "b.csv")));

            assertThat(result.toString()).contains("artifacts=2");
        }

        @Test
        @DisplayName("Success toString should not include artifacts when empty")
        void successToStringShouldNotIncludeArtifactsWhenEmpty() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata());

            assertThat(result.toString()).doesNotContain("artifacts");
        }

        @Test
        @DisplayName("Failure toString should include artifact count when present")
        void failureToStringShouldIncludeArtifactCount() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.failure("error", createSessionRecord(),
                    createMetadata(), List.of(createArtifact("/partial.csv", "partial.csv")));

            assertThat(result.toString()).contains("artifacts=1");
        }
    }

    @Nested
    @DisplayName("Completion Reason")
    class CompletionReasonTests {

        @Test
        @DisplayName("Default success has COMPLETED reason")
        void defaultSuccessIsCompleted() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata());

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        }

        @Test
        @DisplayName("Default failure has ERROR reason")
        void defaultFailureIsError() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.failure("boom", createSessionRecord(),
                    createMetadata());

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
        }

        @Test
        @DisplayName("Explicit MAX_ITERATIONS preserved on success factory")
        void explicitReasonOnSuccess() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("partial answer", createSessionRecord(),
                    createMetadata(), List.of(), CompletionReason.MAX_ITERATIONS);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        }

        @Test
        @DisplayName("Explicit ABORTED preserved on failure factory")
        void explicitReasonOnFailure() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.failure("cancelled", createSessionRecord(),
                    createMetadata(), List.of(), CompletionReason.ABORTED);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ABORTED);
        }

        @Test
        @DisplayName("Null completion reason rejected on success factory")
        void nullReasonRejectedOnSuccess() {
            assertThatThrownBy(() -> OrcaAgentExecutionResult.success("a", createSessionRecord(), createMetadata(),
                    List.of(), null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null completion reason rejected on failure factory")
        void nullReasonRejectedOnFailure() {
            assertThatThrownBy(() -> OrcaAgentExecutionResult.failure("e", createSessionRecord(), createMetadata(),
                    List.of(), null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Different completion reasons produce non-equal results")
        void reasonAffectsEquality() {
            SessionSnapshot snapshot = createSessionRecord();
            ExecutionMetadata metadata = createMetadata();

            OrcaAgentExecutionResult completed = OrcaAgentExecutionResult.success("a", snapshot, metadata, List.of(),
                    CompletionReason.COMPLETED);
            OrcaAgentExecutionResult maxIter = OrcaAgentExecutionResult.success("a", snapshot, metadata, List.of(),
                    CompletionReason.MAX_ITERATIONS);

            assertThat(completed).isNotEqualTo(maxIter);
        }

        @Test
        @DisplayName("toString includes reason")
        void toStringContainsReason() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("a", createSessionRecord(),
                    createMetadata(), List.of(), CompletionReason.TOKEN_BUDGET_EXCEEDED);

            assertThat(result.toString()).contains("reason=TOKEN_BUDGET_EXCEEDED");
        }
    }

    @Nested
    @DisplayName("Compaction Events")
    class CompactionEvents {

        @Test
        @DisplayName("Default success has no compaction events")
        void defaultSuccessHasNoCompactionEvents() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata());

            assertThat(result.getCompactionEvents()).isEmpty();
        }

        @Test
        @DisplayName("Default failure has no compaction events")
        void defaultFailureHasNoCompactionEvents() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.failure("error", createSessionRecord(),
                    createMetadata());

            assertThat(result.getCompactionEvents()).isEmpty();
        }

        @Test
        @DisplayName("withCompactionEvents attaches metadata in order")
        void witherAttachesEventsInOrder() {
            CompactionMetadata first = createCompactionMetadata(1);
            CompactionMetadata second = createCompactionMetadata(2);

            OrcaAgentExecutionResult attached = OrcaAgentExecutionResult
                    .success("answer", createSessionRecord(), createMetadata())
                    .withCompactionEvents(List.of(first, second));

            assertThat(attached.getCompactionEvents()).containsExactly(first, second);
        }

        @Test
        @DisplayName("withCompactionEvents preserves all other fields")
        void witherPreservesOtherFields() {
            SessionSnapshot snapshot = createSessionRecord();
            ExecutionMetadata metadata = createMetadata();
            List<FileArtifact> artifacts = List.of(createArtifact("/a.csv", "a.csv"));

            OrcaAgentExecutionResult original = OrcaAgentExecutionResult.success("answer", snapshot, metadata,
                    artifacts, CompletionReason.MAX_ITERATIONS, true);
            OrcaAgentExecutionResult attached = original.withCompactionEvents(List.of(createCompactionMetadata(7)));

            assertThat(attached.getFinalAnswer()).isEqualTo("answer");
            assertThat(attached.getSnapshot()).isEqualTo(snapshot);
            assertThat(attached.getMetadata()).isEqualTo(metadata);
            assertThat(attached.isSuccess()).isTrue();
            assertThat(attached.getArtifacts()).isEqualTo(artifacts);
            assertThat(attached.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
            assertThat(attached.wasStreamed()).isTrue();
            assertThat(attached.getCompactionEvents()).hasSize(1);
        }

        @Test
        @DisplayName("withCompactionEvents returns same instance when value unchanged")
        void witherReturnsSameInstanceWhenUnchanged() {
            OrcaAgentExecutionResult original = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata());

            assertThat(original.withCompactionEvents(List.of())).isSameAs(original);
        }

        @Test
        @DisplayName("withCompactionEvents rejects null")
        void witherRejectsNull() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata());

            assertThatThrownBy(() -> result.withCompactionEvents(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("getCompactionEvents returns unmodifiable list")
        void getCompactionEventsIsUnmodifiable() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult
                    .success("answer", createSessionRecord(), createMetadata())
                    .withCompactionEvents(List.of(createCompactionMetadata(1)));

            List<CompactionMetadata> events = result.getCompactionEvents();
            assertThatThrownBy(() -> events.add(createCompactionMetadata(2)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Different compaction events produce non-equal results")
        void differentEventsAffectEquality() {
            SessionSnapshot snapshot = createSessionRecord();
            ExecutionMetadata metadata = createMetadata();
            OrcaAgentExecutionResult base = OrcaAgentExecutionResult.success("answer", snapshot, metadata);

            OrcaAgentExecutionResult withOne = base.withCompactionEvents(List.of(createCompactionMetadata(1)));
            OrcaAgentExecutionResult withTwo = base.withCompactionEvents(List.of(createCompactionMetadata(2)));

            assertThat(withOne).isNotEqualTo(withTwo);
            assertThat(withOne).isNotEqualTo(base);
        }

        @Test
        @DisplayName("Same compaction events produce equal results")
        void sameEventsProduceEqualResults() {
            SessionSnapshot snapshot = createSessionRecord();
            ExecutionMetadata metadata = createMetadata();
            CompactionMetadata event = createCompactionMetadata(1);

            OrcaAgentExecutionResult left = OrcaAgentExecutionResult.success("answer", snapshot, metadata)
                    .withCompactionEvents(List.of(event));
            OrcaAgentExecutionResult right = OrcaAgentExecutionResult.success("answer", snapshot, metadata)
                    .withCompactionEvents(List.of(event));

            assertThat(left).isEqualTo(right);
            assertThat(left.hashCode()).isEqualTo(right.hashCode());
        }
    }

    @Nested
    @DisplayName("Cost Summary")
    class CostSummaryTests {

        @Test
        @DisplayName("Default success has an empty cost summary")
        void defaultSuccessHasEmptyCostSummary() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata());

            assertThat(result.getCostSummary().isEmpty()).isTrue();
            assertThat(result.getCostSummary().getTotalCost().isZero()).isTrue();
        }

        @Test
        @DisplayName("Default failure has an empty cost summary")
        void defaultFailureHasEmptyCostSummary() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.failure("error", createSessionRecord(),
                    createMetadata());

            assertThat(result.getCostSummary().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("withCostSummary attaches the summary and preserves all other fields")
        void witherAttachesAndPreserves() {
            SessionSnapshot snapshot = createSessionRecord();
            ExecutionMetadata metadata = createMetadata();
            List<FileArtifact> artifacts = List.of(createArtifact("/a.csv", "a.csv"));
            CostSummary cost = CostSummary.empty().record("gpt-4o", TokenUsage.of(1_000, 500, 1_500), Money.usd(0.01));

            OrcaAgentExecutionResult original = OrcaAgentExecutionResult.success("answer", snapshot, metadata,
                    artifacts, CompletionReason.MAX_ITERATIONS, true);
            OrcaAgentExecutionResult attached = original.withCostSummary(cost);

            assertThat(attached.getCostSummary()).isEqualTo(cost);
            assertThat(attached.getFinalAnswer()).isEqualTo("answer");
            assertThat(attached.getSnapshot()).isEqualTo(snapshot);
            assertThat(attached.getMetadata()).isEqualTo(metadata);
            assertThat(attached.isSuccess()).isTrue();
            assertThat(attached.getArtifacts()).isEqualTo(artifacts);
            assertThat(attached.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
            assertThat(attached.wasStreamed()).isTrue();
        }

        @Test
        @DisplayName("withCostSummary returns same instance when value unchanged")
        void witherReturnsSameInstanceWhenUnchanged() {
            OrcaAgentExecutionResult original = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata());

            assertThat(original.withCostSummary(CostSummary.empty())).isSameAs(original);
        }

        @Test
        @DisplayName("withCostSummary rejects null")
        void witherRejectsNull() {
            OrcaAgentExecutionResult result = OrcaAgentExecutionResult.success("answer", createSessionRecord(),
                    createMetadata());

            assertThatThrownBy(() -> result.withCostSummary(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Different cost summaries produce non-equal results")
        void differentCostSummariesAffectEquality() {
            SessionSnapshot snapshot = createSessionRecord();
            ExecutionMetadata metadata = createMetadata();
            OrcaAgentExecutionResult base = OrcaAgentExecutionResult.success("answer", snapshot, metadata);

            OrcaAgentExecutionResult withCost = base.withCostSummary(
                    CostSummary.empty().record("gpt-4o", TokenUsage.of(1_000, 500, 1_500), Money.usd(0.01)));

            assertThat(withCost).isNotEqualTo(base);
        }

        @Test
        @DisplayName("withCompactionEvents preserves an attached cost summary")
        void compactionWitherPreservesCostSummary() {
            CostSummary cost = CostSummary.empty().record("gpt-4o", TokenUsage.of(1_000, 500, 1_500), Money.usd(0.01));

            OrcaAgentExecutionResult result = OrcaAgentExecutionResult
                    .success("answer", createSessionRecord(), createMetadata()).withCostSummary(cost)
                    .withCompactionEvents(List.of(createCompactionMetadata(1)));

            assertThat(result.getCostSummary()).isEqualTo(cost);
        }
    }

    private static CompactionMetadata createCompactionMetadata(int messagesSummarized) {
        Instant start = Instant.now();
        return CompactionMetadata.builder().trigger(CompactionTrigger.AUTO).preCompactTokenCount(1000)
                .postCompactTokenCount(200).messagesSummarized(messagesSummarized).startedAt(start)
                .completedAt(start.plusMillis(50)).build();
    }

    private static SessionSnapshot createSessionRecord() {
        return SessionSnapshot.of(SessionId.generate(), "Test system prompt", List.of());
    }

    private static ExecutionMetadata createMetadata() {
        Instant start = Instant.now();
        Instant end = start.plusMillis(100);
        return ExecutionMetadata.builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(start, end)
                .build();
    }

    private static FileArtifact createArtifact(String path, String fileName) {
        return FileArtifact.builder().path(path).fileName(fileName).size(1024).build();
    }
}
