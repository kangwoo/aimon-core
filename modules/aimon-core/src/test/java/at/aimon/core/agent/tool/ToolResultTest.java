package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.FileNotFoundException;
import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.InvalidPathException;

@DisplayName("ToolResult Tests")
class ToolResultTest {

    @Nested
    @DisplayName("Success cases")
    class SuccessCases {

        @Test
        @DisplayName("Should create successful result")
        void testSuccess_ValidContent_CreatesSuccessfulResult() {
            // Arrange
            String content = "Operation completed successfully";

            // Act
            ToolResult result = ToolResult.success(content);

            // Assert
            assertThat(result.getContent()).isEqualTo(content);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isError()).isFalse();
            assertThat(result.getException()).isEmpty();
        }

        @Test
        @DisplayName("Should reject null content in success()")
        void testSuccess_NullContent_ThrowsException() {
            // Act & Assert
            assertThatNullPointerException().isThrownBy(() -> ToolResult.success(null))
                    .withMessageContaining("Content cannot be null");
        }

        @Test
        @DisplayName("Should handle empty content")
        void testSuccess_EmptyContent_CreatesSuccessfulResult() {
            // Arrange
            String content = "";

            // Act
            ToolResult result = ToolResult.success(content);

            // Assert
            assertThat(result.getContent()).isEmpty();
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should handle multiline content")
        void testSuccess_MultilineContent_CreatesSuccessfulResult() {
            // Arrange
            String content = "Line 1\nLine 2\nLine 3";

            // Act
            ToolResult result = ToolResult.success(content);

            // Assert
            assertThat(result.getContent()).isEqualTo(content);
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("Error cases - message only")
    class ErrorMessageOnly {

        @Test
        @DisplayName("Should create error result with message")
        void testError_ValidMessage_CreatesErrorResult() {
            // Arrange
            String errorMessage = "Operation failed";

            // Act
            ToolResult result = ToolResult.error(errorMessage);

            // Assert
            assertThat(result.getContent()).isEqualTo(errorMessage);
            assertThat(result.isError()).isTrue();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getException()).isEmpty();
        }

        @Test
        @DisplayName("Should reject null message in error()")
        void testError_NullMessage_ThrowsException() {
            // Act & Assert
            assertThatNullPointerException().isThrownBy(() -> ToolResult.error((String) null))
                    .withMessageContaining("Content cannot be null");
        }
    }

    @Nested
    @DisplayName("Error cases - message with exception")
    class ErrorMessageWithException {

        @Test
        @DisplayName("Should create error result with message and exception")
        void testError_MessageAndException_CreatesErrorResult() {
            // Arrange
            String errorMessage = "File not found: /path/to/file";
            Exception exception = new FileNotFoundException("/path/to/file");

            // Act
            ToolResult result = ToolResult.error(errorMessage, exception);

            // Assert
            assertThat(result.getContent()).isEqualTo(errorMessage);
            assertThat(result.isError()).isTrue();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getException()).isPresent();
            assertThat(result.getException().get()).isSameAs(exception);
        }

        @Test
        @DisplayName("Should reject null message in error(message, exception)")
        void testError_NullMessageWithException_ThrowsException() {
            // Arrange
            Exception exception = new IOException("IO error");

            // Act & Assert
            assertThatNullPointerException().isThrownBy(() -> ToolResult.error(null, exception))
                    .withMessageContaining("Content cannot be null");
        }

        @Test
        @DisplayName("Should reject null exception in error(message, exception)")
        void testError_MessageWithNullException_ThrowsException() {
            // Arrange
            String message = "Error occurred";

            // Act & Assert
            assertThatNullPointerException().isThrownBy(() -> ToolResult.error(message, null))
                    .withMessageContaining("Exception cannot be null");
        }

        @Test
        @DisplayName("Should handle exception with null message")
        void testError_ExceptionWithNullMessage_UsesClassName() {
            // Arrange
            String errorMessage = "Custom error message";
            Exception exception = new IOException(); // No message

            // Act
            ToolResult result = ToolResult.error(errorMessage, exception);

            // Assert
            assertThat(result.getContent()).isEqualTo(errorMessage);
            assertThat(result.getException()).isPresent();
            assertThat(result.getException().get()).isSameAs(exception);
        }
    }

    @Nested
    @DisplayName("Error cases - exception only")
    class ErrorExceptionOnly {

        @Test
        @DisplayName("Should create error result from exception with message")
        void testError_ExceptionWithMessage_CreatesErrorResult() {
            // Arrange
            Exception exception = new IOException("IO error occurred");

            // Act
            ToolResult result = ToolResult.error(exception);

            // Assert
            assertThat(result.getContent()).isEqualTo("IO error occurred");
            assertThat(result.isError()).isTrue();
            assertThat(result.getException()).isPresent();
            assertThat(result.getException().get()).isSameAs(exception);
        }

        @Test
        @DisplayName("Should use class name when exception has no message")
        void testError_ExceptionWithoutMessage_UsesClassName() {
            // Arrange
            Exception exception = new FileNotFoundException();

            // Act
            ToolResult result = ToolResult.error(exception);

            // Assert
            assertThat(result.getContent()).isEqualTo("FileNotFoundException");
            assertThat(result.isError()).isTrue();
            assertThat(result.getException()).isPresent();
            assertThat(result.getException().get()).isSameAs(exception);
        }

        @Test
        @DisplayName("Should reject null exception in error(exception)")
        void testError_NullException_ThrowsException() {
            // Act & Assert
            assertThatNullPointerException().isThrownBy(() -> ToolResult.error((Exception) null))
                    .withMessageContaining("Exception cannot be null");
        }
    }

    @Nested
    @DisplayName("Type-specific error handling")
    class TypeSpecificErrorHandling {

        @Test
        @DisplayName("Should allow filtering exception by type")
        void testGetException_FilterByType_ReturnsMatchingException() {
            // Arrange
            Exception exception = new FileNotFoundException("File not found");
            ToolResult result = ToolResult.error("Error", exception);

            // Act & Assert
            assertThat(result.getException().filter(e -> e instanceof FileNotFoundException)).isPresent();

            assertThat(result.getException().filter(e -> e instanceof InvalidPathException)).isEmpty();
        }

        @Test
        @DisplayName("Should support different exception types")
        void testError_VariousExceptionTypes_PreservesType() {
            // Arrange
            Exception ioException = new IOException("IO error");
            Exception fileNotFoundException = new FileNotFoundException("File not found");
            Exception illegalArgumentException = new IllegalArgumentException("Invalid argument");

            // Act
            ToolResult result1 = ToolResult.error("IO error", ioException);
            ToolResult result2 = ToolResult.error(fileNotFoundException);
            ToolResult result3 = ToolResult.error("Invalid", illegalArgumentException);

            // Assert
            assertThat(result1.getException().get()).isInstanceOf(IOException.class);
            assertThat(result2.getException().get()).isInstanceOf(FileNotFoundException.class);
            assertThat(result3.getException().get()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("Should implement equals correctly for success results")
        void testEquals_SuccessResults_ReturnsCorrectly() {
            // Arrange
            ToolResult result1 = ToolResult.success("content");
            ToolResult result2 = ToolResult.success("content");
            ToolResult result3 = ToolResult.success("different");

            // Assert
            assertThat(result1).isEqualTo(result2);
            assertThat(result1).isNotEqualTo(result3);
            assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
        }

        @Test
        @DisplayName("Should implement equals correctly for error results without exception")
        void testEquals_ErrorResultsWithoutException_ReturnsCorrectly() {
            // Arrange
            ToolResult result1 = ToolResult.error("error");
            ToolResult result2 = ToolResult.error("error");
            ToolResult result3 = ToolResult.error("different error");

            // Assert
            assertThat(result1).isEqualTo(result2);
            assertThat(result1).isNotEqualTo(result3);
            assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
        }

        @Test
        @DisplayName("Should implement equals correctly for error results with exception")
        void testEquals_ErrorResultsWithException_ReturnsCorrectly() {
            // Arrange
            Exception exception1 = new IOException("IO error");
            Exception exception2 = new IOException("IO error");
            ToolResult result1 = ToolResult.error("error", exception1);
            ToolResult result2 = ToolResult.error("error", exception1); // Same exception instance
            ToolResult result3 = ToolResult.error("error", exception2); // Different exception instance

            // Assert
            assertThat(result1).isEqualTo(result2);
            assertThat(result1).isNotEqualTo(result3); // Different exception instances
        }

        @Test
        @DisplayName("Should distinguish between error with and without exception")
        void testEquals_ErrorWithAndWithoutException_NotEqual() {
            // Arrange
            ToolResult result1 = ToolResult.error("error");
            ToolResult result2 = ToolResult.error("error", new IOException("error"));

            // Assert
            assertThat(result1).isNotEqualTo(result2);
        }

        @Test
        @DisplayName("Should distinguish between success and error")
        void testEquals_SuccessAndError_NotEqual() {
            // Arrange
            ToolResult success = ToolResult.success("content");
            ToolResult error = ToolResult.error("content");

            // Assert
            assertThat(success).isNotEqualTo(error);
        }

        @Test
        @DisplayName("Should handle null and different class in equals")
        void testEquals_NullAndDifferentClass_ReturnsFalse() {
            // Arrange
            ToolResult result = ToolResult.success("content");

            // Assert
            assertThat(result).isNotEqualTo(null);
            assertThat(result).isNotEqualTo("different class");
        }

        @Test
        @DisplayName("Should return true for same instance")
        void testEquals_SameInstance_ReturnsTrue() {
            // Arrange
            ToolResult result = ToolResult.success("content");

            // Assert
            assertThat(result).isEqualTo(result);
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("Should have meaningful toString for success")
        void testToString_Success_ContainsRelevantInfo() {
            // Arrange
            ToolResult result = ToolResult.success("content");

            // Act
            String string = result.toString();

            // Assert
            assertThat(string).contains("ToolResult");
            assertThat(string).contains("isError=false");
            assertThat(string).contains("content='content'");
            assertThat(string).doesNotContain("exception=");
        }

        @Test
        @DisplayName("Should have meaningful toString for error without exception")
        void testToString_ErrorWithoutException_ContainsRelevantInfo() {
            // Arrange
            ToolResult result = ToolResult.error("error message");

            // Act
            String string = result.toString();

            // Assert
            assertThat(string).contains("ToolResult");
            assertThat(string).contains("isError=true");
            assertThat(string).contains("content='error message'");
            assertThat(string).doesNotContain("exception=");
        }

        @Test
        @DisplayName("Should have meaningful toString for error with exception")
        void testToString_ErrorWithException_ContainsRelevantInfo() {
            // Arrange
            Exception exception = new IOException("IO error");
            ToolResult result = ToolResult.error("error message", exception);

            // Act
            String string = result.toString();

            // Assert
            assertThat(string).contains("ToolResult");
            assertThat(string).contains("isError=true");
            assertThat(string).contains("content='error message'");
            assertThat(string).contains("exception=IOException");
            assertThat(string).contains("IO error");
        }

        @Test
        @DisplayName("Should handle exception with null message in toString")
        void testToString_ExceptionWithNullMessage_HandlesGracefully() {
            // Arrange
            Exception exception = new FileNotFoundException();
            ToolResult result = ToolResult.error("Custom message", exception);

            // Act
            String string = result.toString();

            // Assert
            assertThat(string).contains("exception=FileNotFoundException");
        }
    }
}
