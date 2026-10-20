package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;

import at.aimon.core.llms.openai.exception.OpenAICallAbortedException;

/**
 * The per-attempt hook, without a network: an aborted call's attempt is refused before anything is sent, with an
 * exception the SDK's retry loop does not retry.
 */
@DisplayName("OpenAIAbortableHttpClient - the per-attempt hook")
class OpenAIAbortableHttpClientTest {

    /** Nothing listens here; a request that reaches the network fails fast, and a refused one never gets there. */
    private static final String UNREACHABLE = "http://127.0.0.1:1";

    private final OpenAIAbortRegistry registry = new OpenAIAbortRegistry();
    private final OpenAIAbortableHttpClient transport = new OpenAIAbortableHttpClient(Timeout.builder().build(),
            registry);

    @AfterEach
    void tearDown() {
        transport.close();
    }

    private static HttpRequest requestFor(OpenAICallAbort abort) {
        return HttpRequest.builder().method(HttpMethod.POST).baseUrl(UNREACHABLE).addPathSegment("v1")
                .putHeader(OpenAICallAbort.HEADER, abort.key()).build();
    }

    @Test
    @DisplayName("an attempt of an aborted call is refused with a type the SDK does not retry")
    void refusesAnAbortedCallsAttempt() {
        final OpenAICallAbort abort = registry.open();
        abort.abort();

        assertThatThrownBy(() -> transport.execute(requestFor(abort), RequestOptions.none()))
                .isInstanceOf(OpenAICallAbortedException.class).isNotInstanceOf(OpenAIIoException.class)
                .isNotInstanceOf(OpenAIRetryableException.class).hasMessageContaining("refused before it was sent");
        assertThat(transport.inFlightCalls()).as("nothing was started").isZero();
    }

    @Test
    @DisplayName("the asynchronous form refuses the same way, as a failed future")
    void refusesAsynchronously() {
        final OpenAICallAbort abort = registry.open();
        abort.abort();

        final CompletableFuture<HttpResponse> attempt = transport.executeAsync(requestFor(abort),
                RequestOptions.none());

        assertThat(attempt).isCompletedExceptionally();
        assertThatThrownBy(attempt::get).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(OpenAICallAbortedException.class);
        assertThat(transport.inFlightCalls()).isZero();
    }

    @Test
    @DisplayName("a released call's key is unknown to the hook, and the attempt goes through to the network")
    void releasedKeyPassesThrough() {
        final OpenAICallAbort abort = registry.open();
        abort.abort();
        registry.release(abort);

        // Past the hook: the failure is the SDK's own transport classification, from the connection attempt.
        assertThatThrownBy(() -> transport.execute(requestFor(abort), RequestOptions.none()))
                .isInstanceOf(OpenAIIoException.class);
        assertThat(transport.inFlightCalls()).as("a failed attempt is finished").isZero();
    }

    @Test
    @DisplayName("a live call's attempt that fails on the network keeps the SDK's retryable classification")
    void liveCallKeepsTheSdkClassification() {
        final OpenAICallAbort abort = registry.open();

        assertThatThrownBy(() -> transport.execute(requestFor(abort), RequestOptions.none()))
                .isInstanceOf(OpenAIIoException.class).isNotInstanceOf(OpenAICallAbortedException.class);
        assertThat(abort.state()).isEqualTo(OpenAICallAbort.State.OPEN);
    }
}
