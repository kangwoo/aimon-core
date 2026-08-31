package at.aimon.core.tools.web.fetch;

import java.io.IOException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/**
 * HTTP content fetcher.
 *
 * <p>
 * Uses OkHttpClient to fetch content from URLs. Encapsulates HTTP-related settings
 * such as User-Agent and response size limits.
 *
 * <p>
 * SSRF defense is handled by the {@link at.aimon.core.tools.web.security.SsrfRedirectInterceptor}
 * registered as a network interceptor on the OkHttpClient, so this class focuses
 * purely on HTTP fetch logic. Build the client via {@link WebHttpClientFactory} to get that protection wired in;
 * passing a bare {@code new OkHttpClient()} leaves the fetcher without DNS-rebinding defense.
 */
public class HttpContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpContentFetcher.class);

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (compatible; AimonBot/1.0)";
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 2_000_000;

    private final OkHttpClient httpClient;
    private final String userAgent;
    private final int maxResponseBytes;

    /**
     * Creates an HttpContentFetcher with custom settings.
     *
     * @param httpClient
     *            the OkHttpClient (timeout, redirect, SSRF interceptor configured externally)
     * @param userAgent
     *            the HTTP User-Agent header value (not blank)
     * @param maxResponseBytes
     *            the maximum response body size in bytes (must be &gt; 0)
     * @throws NullPointerException
     *             if httpClient or userAgent is null
     * @throws IllegalArgumentException
     *             if userAgent is blank or maxResponseBytes &lt; 1
     */
    public HttpContentFetcher(OkHttpClient httpClient, String userAgent, int maxResponseBytes) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent cannot be null");
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent cannot be blank");
        }
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be > 0");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Creates an HttpContentFetcher with default settings.
     *
     * @param httpClient
     *            the OkHttpClient
     */
    public HttpContentFetcher(OkHttpClient httpClient) {
        this(httpClient, DEFAULT_USER_AGENT, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * Fetches content from a URL.
     *
     * @param url
     *            the target URL
     * @return the fetch result containing body, status code, and content type
     * @throws IOException
     *             on HTTP request failure or non-successful status code
     */
    public FetchResult fetch(String url) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", userAgent)
                .header("Accept", "text/html, application/json, text/plain;q=0.9, */*;q=0.8").build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + url);
            }

            ResponseBody body = response.body();
            if (body == null) {
                return FetchResult.builder().statusCode(response.code()).build();
            }

            String contentType = body.contentType() != null ? body.contentType().toString() : "";
            String bodyStr = readLimitedBody(body, maxResponseBytes);

            return FetchResult.builder().body(bodyStr).statusCode(response.code()).contentType(contentType).build();
        }
    }

    private String readLimitedBody(ResponseBody body, int maxBytes) throws IOException {
        BufferedSource source = body.source();
        source.request(maxBytes + 1L);
        Buffer buffer = source.getBuffer();

        long size = buffer.size();
        if (size > maxBytes) {
            log.debug("Response truncated: {} -> {} bytes", size, maxBytes);
            return buffer.readUtf8(maxBytes);
        }
        return buffer.readUtf8();
    }
}
