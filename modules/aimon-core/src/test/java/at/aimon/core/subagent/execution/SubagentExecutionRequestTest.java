package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;

class SubagentExecutionRequestTest {

    @Test
    void builder_withoutInvokingConversationId_isEmpty() {
        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("g").build();

        assertThat(request.getInvokingSessionId()).isEmpty();
    }

    @Test
    void builder_withInvokingConversationId_roundTripsThroughEqualsAndHashCode() {
        final SessionId invoker = SessionId.generate();

        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("g")
                .invokingSessionId(invoker).build();
        final SubagentExecutionRequest same = SubagentExecutionRequest.builder().taskId("task-1").goal("g")
                .invokingSessionId(invoker).build();
        final SubagentExecutionRequest other = SubagentExecutionRequest.builder().taskId("task-1").goal("g")
                .invokingSessionId(SessionId.generate()).build();

        assertThat(request.getInvokingSessionId()).contains(invoker);
        assertThat(request).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other);
        assertThat(request.toString()).contains(invoker.value());
    }

    @Test
    void builder_withExecutionAttributes_returnsSetAttributes() {
        Map<String, Object> attrs = Map.of("key1", "value1", "key2", 42);

        SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("test goal")
                .executionAttributes(attrs).build();

        assertThat(request.getExecutionAttributes()).isEqualTo(attrs);
    }

    @Test
    void builder_withoutExecutionAttributes_returnsEmptyMap() {
        SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("test goal")
                .build();

        assertThat(request.getExecutionAttributes()).isEmpty();
    }

    @Test
    void builder_withNullExecutionAttributes_returnsEmptyMap() {
        SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("test goal")
                .executionAttributes(null).build();

        assertThat(request.getExecutionAttributes()).isEmpty();
    }

    @Test
    void builder_executionAttributes_defensiveCopy() {
        HashMap<String, Object> mutableMap = new HashMap<>();
        mutableMap.put("key1", "value1");

        SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("test goal")
                .executionAttributes(mutableMap).build();

        // Modify original map after construction
        mutableMap.put("key2", "value2");

        assertThat(request.getExecutionAttributes()).hasSize(1).containsEntry("key1", "value1");
    }

    @Test
    void getExecutionAttributes_returnedMapIsUnmodifiable() {
        SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("test goal")
                .executionAttributes(Map.of("key1", "value1")).build();

        assertThatThrownBy(() -> request.getExecutionAttributes().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equals_sameFields_returnsTrue() {
        Map<String, Object> attrs = Map.of("key", "value");

        SubagentExecutionRequest request1 = SubagentExecutionRequest.builder().taskId("task-1").goal("test goal")
                .executionAttributes(attrs).build();
        SubagentExecutionRequest request2 = SubagentExecutionRequest.builder().taskId("task-1").goal("test goal")
                .executionAttributes(attrs).build();

        assertThat(request1).isEqualTo(request2);
        assertThat(request1.hashCode()).isEqualTo(request2.hashCode());
    }

    @Test
    void equals_differentExecutionAttributes_returnsFalse() {
        SubagentExecutionRequest request1 = SubagentExecutionRequest.builder().taskId("task-1").goal("test goal")
                .executionAttributes(Map.of("key", "value1")).build();
        SubagentExecutionRequest request2 = SubagentExecutionRequest.builder().taskId("task-1").goal("test goal")
                .executionAttributes(Map.of("key", "value2")).build();

        assertThat(request1).isNotEqualTo(request2);
    }

    @Test
    void toString_containsExecutionAttributes() {
        SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("test goal")
                .executionAttributes(Map.of("key", "value")).build();

        assertThat(request.toString()).contains("executionAttributes");
    }
}
