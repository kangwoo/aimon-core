package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.UnavailableExecutionEnvironment;

@DisplayName("ContextAssemblyRequest Tests")
class ContextAssemblyRequestTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("empty builder yields all-absent optionals and iteration 0")
        void emptyBuilder() {
            ContextAssemblyRequest request = ContextAssemblyRequest.builder().build();

            assertThat(request.getExecutionEnvironment()).isEmpty();
            assertThat(request.getAgentName()).isEmpty();
            assertThat(request.getIteration()).isZero();
        }

        @Test
        @DisplayName("set fields are retrievable")
        void setsFields() {
            ExecutionEnvironment env = UnavailableExecutionEnvironment.of("test");
            ContextAssemblyRequest request = ContextAssemblyRequest.builder().executionEnvironment(env)
                    .agentName("Agent").iteration(3).build();

            assertThat(request.getExecutionEnvironment()).contains(env);
            assertThat(request.getAgentName()).contains("Agent");
            assertThat(request.getIteration()).isEqualTo(3);
        }

        @Test
        @DisplayName("negative iteration rejected")
        void rejectsNegativeIteration() {
            assertThatThrownBy(() -> ContextAssemblyRequest.builder().iteration(-1).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("iteration");
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("same fields are equal")
        void sameFieldsEqual() {
            ExecutionEnvironment env = UnavailableExecutionEnvironment.of("test");
            ContextAssemblyRequest a = ContextAssemblyRequest.builder().executionEnvironment(env).agentName("A")
                    .iteration(1).build();
            ContextAssemblyRequest b = ContextAssemblyRequest.builder().executionEnvironment(env).agentName("A")
                    .iteration(1).build();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("differing iteration not equal")
        void differingIterationNotEqual() {
            assertThat(ContextAssemblyRequest.builder().iteration(1).build())
                    .isNotEqualTo(ContextAssemblyRequest.builder().iteration(2).build());
        }
    }
}
