package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.openai.client.OpenAIClient;

/**
 * {@link OpenAILlmClient#close()} releases the SDK client, and through it the transport this module built, exactly
 * once.
 */
@DisplayName("OpenAILlmClient - close()")
class OpenAILlmClientCloseTest {

    private static OpenAIConfig config() {
        return OpenAIConfig.builder().apiKey("test-key").model("gpt-4o").build();
    }

    @Test
    @DisplayName("close() shuts down the transport the client built")
    void closeShutsDownTheOwnTransport() {
        final OpenAILlmClient client = new OpenAILlmClient(config());
        final OpenAIAbortableHttpClient transport = client.transport().orElseThrow();
        assertThat(transport.isShutDown()).isFalse();

        client.close();

        assertThat(transport.isShutDown()).isTrue();
    }

    @Test
    @DisplayName("close() closes the SDK client once, however often it is called")
    void closeIsIdempotent() {
        final OpenAIClient sdk = mock(OpenAIClient.class);
        final OpenAILlmClient client = new OpenAILlmClient(config(), sdk);

        client.close();
        client.close();

        verify(sdk, times(1)).close();
    }

    @Test
    @DisplayName("the client is AutoCloseable, so containers and try-with-resources release it")
    void isAutoCloseable() throws Exception {
        final OpenAILlmClient client = new OpenAILlmClient(config());
        final OpenAIAbortableHttpClient transport = client.transport().orElseThrow();

        try (AutoCloseable closeable = client) {
            assertThat(closeable).isSameAs(client);
        }

        assertThat(transport.isShutDown()).isTrue();
    }
}
