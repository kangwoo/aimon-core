package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.input.TextInput;

class OrcaAgentExecutionRequestTest {

    @Test
    void builder_withExecutionAttributes_returnsSetAttributes() {
        Map<String, Object> attrs = Map.of("key1", "value1", "key2", 42);

        OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .executionAttributes(attrs).build();

        assertThat(request.getExecutionAttributes()).isEqualTo(attrs);
    }

    @Test
    void builder_withoutExecutionAttributes_returnsEmptyMap() {
        OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .build();

        assertThat(request.getExecutionAttributes()).isEmpty();
    }

    @Test
    void builder_withNullExecutionAttributes_returnsEmptyMap() {
        OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .executionAttributes(null).build();

        assertThat(request.getExecutionAttributes()).isEmpty();
    }

    @Test
    void builder_executionAttributes_defensiveCopy() {
        HashMap<String, Object> mutableMap = new HashMap<>();
        mutableMap.put("key1", "value1");

        OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .executionAttributes(mutableMap).build();

        // Modify original map after construction
        mutableMap.put("key2", "value2");

        assertThat(request.getExecutionAttributes()).hasSize(1).containsEntry("key1", "value1");
    }

    @Test
    void getExecutionAttributes_returnedMapIsUnmodifiable() {
        OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .executionAttributes(Map.of("key1", "value1")).build();

        assertThatThrownBy(() -> request.getExecutionAttributes().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equals_sameFields_returnsTrue() {
        Map<String, Object> attrs = Map.of("key", "value");
        Map<String, Object> vars = Map.of("var", "val");

        OrcaAgentExecutionRequest request1 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .systemPromptVariables(vars).executionAttributes(attrs).build();
        OrcaAgentExecutionRequest request2 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .systemPromptVariables(vars).executionAttributes(attrs).build();

        assertThat(request1).isEqualTo(request2);
    }

    @Test
    void equals_differentExecutionAttributes_returnsFalse() {
        OrcaAgentExecutionRequest request1 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .executionAttributes(Map.of("key", "value1")).build();
        OrcaAgentExecutionRequest request2 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .executionAttributes(Map.of("key", "value2")).build();

        assertThat(request1).isNotEqualTo(request2);
    }

    @Test
    void equals_differentSystemPromptVariables_returnsFalse() {
        OrcaAgentExecutionRequest request1 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .systemPromptVariables(Map.of("var", "val1")).build();
        OrcaAgentExecutionRequest request2 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .systemPromptVariables(Map.of("var", "val2")).build();

        assertThat(request1).isNotEqualTo(request2);
    }

    @Test
    void hashCode_sameFields_sameHashCode() {
        Map<String, Object> attrs = Map.of("key", "value");
        Map<String, Object> vars = Map.of("var", "val");

        OrcaAgentExecutionRequest request1 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .systemPromptVariables(vars).executionAttributes(attrs).build();
        OrcaAgentExecutionRequest request2 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .systemPromptVariables(vars).executionAttributes(attrs).build();

        assertThat(request1.hashCode()).isEqualTo(request2.hashCode());
    }

    @Test
    void toString_containsExecutionAttributes() {
        OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .executionAttributes(Map.of("key", "value")).build();

        assertThat(request.toString()).contains("executionAttributes");
    }

    @Test
    void builder_withoutBudget_returnsEmptyOptional() {
        OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .build();

        assertThat(request.getBudget()).isEmpty();
    }

    @Test
    void builder_withNullBudget_returnsEmptyOptional() {
        OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .budget(null).build();

        assertThat(request.getBudget()).isEmpty();
    }

    @Test
    void builder_withCustomBudget_roundTripsValue() {
        ExecutionBudget budget = ExecutionBudget.builder().maxIterations(20).maxTokens(100_000)
                .maxWallClockDuration(Duration.ofMinutes(5)).build();

        OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .budget(budget).build();

        assertThat(request.getBudget()).contains(budget);
    }

    @Test
    void equals_sameBudget_returnsTrue() {
        ExecutionBudget budget = ExecutionBudget.builder().maxIterations(10).build();

        OrcaAgentExecutionRequest request1 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .budget(budget).build();
        OrcaAgentExecutionRequest request2 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .budget(ExecutionBudget.builder().maxIterations(10).build()).build();

        assertThat(request1).isEqualTo(request2);
        assertThat(request1.hashCode()).isEqualTo(request2.hashCode());
    }

    @Test
    void equals_differentBudget_returnsFalse() {
        OrcaAgentExecutionRequest request1 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .budget(ExecutionBudget.builder().maxIterations(10).build()).build();
        OrcaAgentExecutionRequest request2 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .budget(ExecutionBudget.builder().maxIterations(20).build()).build();

        assertThat(request1).isNotEqualTo(request2);
    }

    @Test
    void equals_unsetBudgetVsSetBudget_returnsFalse() {
        OrcaAgentExecutionRequest request1 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .build();
        OrcaAgentExecutionRequest request2 = OrcaAgentExecutionRequest.builder().userInput(TextInput.of("hello"))
                .budget(ExecutionBudget.builder().maxIterations(10).build()).build();

        assertThat(request1).isNotEqualTo(request2);
    }
}
