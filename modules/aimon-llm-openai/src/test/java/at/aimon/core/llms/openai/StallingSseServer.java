package at.aimon.core.llms.openai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

/**
 * A local HTTP server that answers every request with the same opening of an SSE stream and then stalls: it never
 * sends the rest and never closes the response until the test ends.
 *
 * <p>
 * It is what a provider looks like to a client whose call is interrupted mid-stream. The transport underneath the
 * client is the real one — the SDK, OkHttp, a socket — which is the point: what a closed stream does to its reader is
 * the SDK's behaviour, and a mocked {@code StreamResponse} states an assumption about it rather than observing it.
 */
final class StallingSseServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private final CountDownLatch answered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private StallingSseServer(String sse) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            final OutputStream out = exchange.getResponseBody();
            out.write(sse.getBytes(StandardCharsets.UTF_8));
            out.flush();
            answered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                out.close();
            } catch (IOException ignored) {
                // The client hung up, which is what the test is about.
            }
        });
        server.setExecutor(handlers);
        server.start();
    }

    /** Starts a server that sends {@code sse} and stalls. */
    static StallingSseServer sending(String sse) throws IOException {
        return new StallingSseServer(sse);
    }

    /** The server's base URL, without a trailing slash. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Waits until a request has been received and the opening of the stream has been flushed to it, so the call is in
     * flight. The timeout is a hang guard.
     */
    boolean awaitAnswered(long seconds) throws InterruptedException {
        return answered.await(seconds, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        handlers.shutdownNow();
    }
}
