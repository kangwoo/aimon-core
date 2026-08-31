package at.aimon.core.knowledges.opensearch;

import java.util.Objects;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;

/**
 * Factory for creating {@link OpenSearchClient} instances from {@link OpenSearchConfig}.
 *
 * <p>
 * Simplifies client creation by encapsulating the low-level REST client and transport setup.
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

        final HttpHost host = new HttpHost(config.getHost(), config.getPort(), config.getScheme());

        final HttpAsyncClientBuilder httpClientBuilder = HttpAsyncClients.custom();

        if (config.hasCredentials()) {
            final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(config.getUsername(), config.getPassword()));
            httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
        }

        // Trust all certificates for development — production should use proper TLS configuration
        if ("https".equals(config.getScheme())) {
            try {
                final javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                sslContext.init(null, new javax.net.ssl.TrustManager[]{new TrustAllManager()}, null);
                httpClientBuilder.setSSLContext(sslContext);
                httpClientBuilder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to configure SSL context", e);
            }
        }

        final RestClient restClient = RestClient.builder(host).setHttpClientConfigCallback(b -> httpClientBuilder)
                .build();

        final RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());

        return new OpenSearchClient(transport);
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
