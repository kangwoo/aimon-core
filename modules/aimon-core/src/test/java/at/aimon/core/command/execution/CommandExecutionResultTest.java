package at.aimon.core.command.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CommandExecutionResult Tests")
class CommandExecutionResultTest {

    @Test
    @DisplayName("Should create successful result")
    void shouldCreateSuccessfulResult() {
        CommandExecutionResult result = CommandExecutionResult.success("Task completed");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isFailure()).isFalse();
        assertThat(result.getResponse()).isEqualTo("Task completed");
        assertThat(result.getError()).isEmpty();
    }

    @Test
    @DisplayName("Should create failed result with error")
    void shouldCreateFailedResultWithError() {
        Exception error = new IOException("Connection failed");

        CommandExecutionResult result = CommandExecutionResult.failure(error);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isFailure()).isTrue();
        assertThat(result.getResponse()).contains("Command execution failed");
        assertThat(result.getResponse()).contains("Connection failed");
        assertThat(result.getError()).isPresent();
        assertThat(result.getError().get()).isSameAs(error);
    }

    @Test
    @DisplayName("Should create failed result with custom message")
    void shouldCreateFailedResultWithCustomMessage() {
        Exception error = new IOException("Root cause");

        CommandExecutionResult result = CommandExecutionResult.failure("Custom error message", error);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResponse()).isEqualTo("Custom error message");
        assertThat(result.getError()).isPresent();
        assertThat(result.getError().get()).isSameAs(error);
    }

    @Test
    @DisplayName("Should reject null response in success()")
    void shouldRejectNullResponseInSuccess() {
        assertThatThrownBy(() -> CommandExecutionResult.success(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Response cannot be null");
    }

    @Test
    @DisplayName("Should reject null error in failure()")
    void shouldRejectNullErrorInFailure() {
        assertThatThrownBy(() -> CommandExecutionResult.failure((Throwable) null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Error cannot be null");
    }

    @Test
    @DisplayName("Should reject null message in failure with message")
    void shouldRejectNullMessageInFailureWithMessage() {
        assertThatThrownBy(() -> CommandExecutionResult.failure(null, new IOException()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Message cannot be null");
    }

    @Test
    @DisplayName("Should reject null error in failure with message")
    void shouldRejectNullErrorInFailureWithMessage() {
        assertThatThrownBy(() -> CommandExecutionResult.failure("Message", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Error cannot be null");
    }

    @Test
    @DisplayName("Should have meaningful toString for success")
    void shouldHaveMeaningfulToStringForSuccess() {
        CommandExecutionResult result = CommandExecutionResult.success("Done");

        String toString = result.toString();

        assertThat(toString).contains("CommandExecutionResult");
        assertThat(toString).contains("success=true");
        assertThat(toString).contains("Done");
    }

    @Test
    @DisplayName("Should have meaningful toString for failure")
    void shouldHaveMeaningfulToStringForFailure() {
        CommandExecutionResult result = CommandExecutionResult.failure(new IOException("Error"));

        String toString = result.toString();

        assertThat(toString).contains("CommandExecutionResult");
        assertThat(toString).contains("success=false");
        assertThat(toString).contains("IOException");
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly")
    void shouldImplementEqualsAndHashCode() {
        CommandExecutionResult result1 = CommandExecutionResult.success("Response");
        CommandExecutionResult result2 = CommandExecutionResult.success("Response");
        CommandExecutionResult result3 = CommandExecutionResult.success("Different");

        assertThat(result1).isEqualTo(result2);
        assertThat(result1.hashCode()).isEqualTo(result2.hashCode());

        assertThat(result1).isNotEqualTo(result3);
        assertThat(result1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should handle empty response")
    void shouldHandleEmptyResponse() {
        CommandExecutionResult result = CommandExecutionResult.success("");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEmpty();
    }

    @Test
    @DisplayName("Should handle multiline response")
    void shouldHandleMultilineResponse() {
        String multiline = "Line 1\nLine 2\nLine 3";

        CommandExecutionResult result = CommandExecutionResult.success(multiline);

        assertThat(result.getResponse()).isEqualTo(multiline);
    }
}
