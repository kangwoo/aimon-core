package at.aimon.core.knowledges.opensearch;

import java.util.Objects;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

/**
 * Factory for creating {@link OpenSearchClient} instances from {@link OpenSearchConfig}.
 *
 * <p>
 * Simplifies client creation by encapsulating the Apache HttpClient 5 transport setup.
 *
 * <pre>{@code
 * OpenSearchConfig config = OpenSearchConfig.builder()
 *         .host("localhost")
 *         .username("admin")
 *         .password("admin")
 *         .build();
 *
 * OpenSearchClient client = OpenSearchClientFactory.create(config);
 * }</pre>
 *
 * <p>
 * The caller is responsible for closing the returned client's underlying transport when done:
 *
 * <pre>{@code
 * client._transport().close();
 * }</pre>
 */
public final class OpenSearchClientFactory {

    private OpenSearchClientFactory() {
        throw new AssertionError("Utility class");
    }

    /**
     * Creates an {@link OpenSearchClient} from the given configuration.
     *
     * @param config
     *            the OpenSearch configuration (must not be null)
     * @return a configured OpenSearch client
     */
    public static OpenSearchClient create(OpenSearchConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        final HttpHost host = new HttpHost(config.getScheme(), config.getHost(), config.getPort());
        final SSLContext sslContext = "https".equals(config.getScheme()) ? trustAllSslContext() : null;

        final OpenSearchTransport transport = ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper()).setHttpClientConfigCallback(httpClientBuilder -> {
                    if (config.hasCredentials()) {
                        final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                        credentialsProvider.setCredentials(new AuthScope(host), new UsernamePasswordCredentials(
                                config.getUsername(), config.getPassword().toCharArray()));
                        httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    }

                    // Trust all certificates for development — production should use proper TLS configuration
                    if (sslContext != null) {
                        final TlsStrategy tlsStrategy = ClientTlsStrategyBuilder.create().setSslContext(sslContext)
                                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE).buildAsync();
                        httpClientBuilder.setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                                .setTlsStrategy(tlsStrategy).build());
                    }
                    return httpClientBuilder;
                }).build();

        return new OpenSearchClient(transport);
    }

    private static SSLContext trustAllSslContext() {
        try {
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new javax.net.ssl.TrustManager[]{new TrustAllManager()}, null);
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure SSL context", e);
        }
    }

    /**
     * Trust-all X509 manager for development/testing. Production deployments should use proper certificate
     * validation.
     */
    private static final class TrustAllManager implements javax.net.ssl.X509TrustManager {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            // Trust all clients
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            // Trust all servers
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }
}
