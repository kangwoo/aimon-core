package at.aimon.core.tools.web.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Route;

@DisplayName("SsrfRedirectInterceptor Tests")
class SsrfRedirectInterceptorTest {

    private SsrfGuard ssrfGuard;
    private SsrfRedirectInterceptor interceptor;

    @BeforeEach
    void setUp() {
        ssrfGuard = new SsrfGuard();
        interceptor = new SsrfRedirectInterceptor(ssrfGuard);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw NullPointerException for null guard")
        void testNullGuard() {
            assertThatThrownBy(() -> new SsrfRedirectInterceptor(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("intercept - URL validation")
    class UrlValidation {

        @Test
        @DisplayName("Should block request to internal URL")
        void testBlockInternalUrl() throws IOException {
            Request request = new Request.Builder().url("http://127.0.0.1/admin").build();
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            when(chain.request()).thenReturn(request);

            assertThatThrownBy(() -> interceptor.intercept(chain)).isInstanceOf(IOException.class)
                    .hasMessageContaining("SSRF policy");
        }

        @Test
        @DisplayName("Should block request to private network")
        void testBlockPrivateNetwork() throws IOException {
            Request request = new Request.Builder().url("http://10.0.0.1/secret").build();
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            when(chain.request()).thenReturn(request);

            assertThatThrownBy(() -> interceptor.intercept(chain)).isInstanceOf(IOException.class)
                    .hasMessageContaining("SSRF policy");
        }
    }

    @Nested
    @DisplayName("intercept - connection validation")
    class ConnectionValidation {

        @Test
        @DisplayName("Should fail closed when connection is null")
        void testNullConnection() throws IOException {
            Request request = new Request.Builder().url("http://example.com/").build();
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            when(chain.request()).thenReturn(request);

            Response response = buildEmptyResponse(request);
            when(chain.proceed(request)).thenReturn(response);
            when(chain.connection()).thenReturn(null);

            assertThatThrownBy(() -> interceptor.intercept(chain)).isInstanceOf(IOException.class)
                    .hasMessageContaining("connection info unavailable");
        }

        @Test
        @DisplayName("Should fail closed when route is null")
        void testNullRoute() throws IOException {
            Request request = new Request.Builder().url("http://example.com/").build();
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            when(chain.request()).thenReturn(request);

            Response response = buildEmptyResponse(request);
            when(chain.proceed(request)).thenReturn(response);

            Connection connection = mock(Connection.class);
            when(connection.route()).thenReturn(null);
            when(chain.connection()).thenReturn(connection);

            assertThatThrownBy(() -> interceptor.intercept(chain)).isInstanceOf(IOException.class)
                    .hasMessageContaining("connection info unavailable");
        }

        @Test
        @DisplayName("Should block when resolved address is internal")
        void testBlockInternalResolvedAddress() throws IOException {
            Request request = new Request.Builder().url("http://example.com/").build();
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            when(chain.request()).thenReturn(request);

            Response response = buildEmptyResponse(request);
            when(chain.proceed(request)).thenReturn(response);

            InetSocketAddress socketAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 80);
            Route route = mock(Route.class);
            when(route.socketAddress()).thenReturn(socketAddress);

            Connection connection = mock(Connection.class);
            when(connection.route()).thenReturn(route);
            when(chain.connection()).thenReturn(connection);

            assertThatThrownBy(() -> interceptor.intercept(chain)).isInstanceOf(IOException.class)
                    .hasMessageContaining("SSRF policy");
        }

        @Test
        @DisplayName("Should allow when resolved address is public")
        void testAllowPublicResolvedAddress() throws IOException {
            Request request = new Request.Builder().url("http://example.com/").build();
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            when(chain.request()).thenReturn(request);

            Response response = buildEmptyResponse(request);
            when(chain.proceed(request)).thenReturn(response);

            InetSocketAddress socketAddress = new InetSocketAddress(InetAddress.getByName("93.184.216.34"), 80);
            Route route = mock(Route.class);
            when(route.socketAddress()).thenReturn(socketAddress);

            Connection connection = mock(Connection.class);
            when(connection.route()).thenReturn(route);
            when(chain.connection()).thenReturn(connection);

            Response result = interceptor.intercept(chain);
            assertThat(result).isEqualTo(response);
        }

        @Test
        @DisplayName("Should allow internal resolved address when the guard is disabled")
        void testDisabledGuardAllowsInternalAddress() throws IOException {
            final SsrfRedirectInterceptor disabledInterceptor = new SsrfRedirectInterceptor(
                    new SsrfGuard(SsrfGuardConfig.disabled()));
            Request request = new Request.Builder().url("http://internal.service/").build();
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            when(chain.request()).thenReturn(request);

            Response response = buildEmptyResponse(request);
            when(chain.proceed(request)).thenReturn(response);

            // 127.0.0.1 would normally be blocked; with protection disabled it passes through.
            InetSocketAddress socketAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 80);
            Route route = mock(Route.class);
            when(route.socketAddress()).thenReturn(socketAddress);

            Connection connection = mock(Connection.class);
            when(connection.route()).thenReturn(route);
            when(chain.connection()).thenReturn(connection);

            assertThat(disabledInterceptor.intercept(chain)).isEqualTo(response);
        }

        @Test
        @DisplayName("Should allow an allow-listed host even when its socket resolves to an internal address")
        void testAllowListedHostBypassesInternalAddress() throws IOException {
            final SsrfRedirectInterceptor allowInterceptor = new SsrfRedirectInterceptor(
                    new SsrfGuard(SsrfGuardConfig.builder().allowHost("internal.service").build()));
            Request request = new Request.Builder().url("http://internal.service/").build();
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            when(chain.request()).thenReturn(request);

            Response response = buildEmptyResponse(request);
            when(chain.proceed(request)).thenReturn(response);

            // 10.0.0.5 is private and would normally be blocked; the allow-listed host exempts the address check.
            InetSocketAddress socketAddress = new InetSocketAddress(InetAddress.getByName("10.0.0.5"), 80);
            Route route = mock(Route.class);
            when(route.socketAddress()).thenReturn(socketAddress);

            Connection connection = mock(Connection.class);
            when(connection.route()).thenReturn(route);
            when(chain.connection()).thenReturn(connection);

            assertThat(allowInterceptor.intercept(chain)).isEqualTo(response);
        }

        private Response buildEmptyResponse(Request request) {
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("", MediaType.get("text/plain"))).build();
        }
    }
}
