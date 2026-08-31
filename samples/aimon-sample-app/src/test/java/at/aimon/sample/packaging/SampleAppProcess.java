package at.aimon.sample.packaging;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A running copy of the sample application in a JVM of its own, and the two questions worth asking it.
 *
 * <p>
 * The separate process is not incidental: a fat jar's class path exists only inside a JVM that was launched from
 * it, so an in-process test can never observe the thing these tests are about. Everything here — the port file,
 * the captured output, the HTTP calls — is the cost of that separation.
 *
 * <p>
 * Two launch shapes are supported and they differ in exactly one respect, the class path. {@link #launchJar} runs
 * {@code java -jar}, which is what an operator deploys. {@link #launchExploded} runs the same main class off
 * plain directories, which is what {@code bootRun}, an IDE and every other test in this build do. Comparing them
 * is the point.
 */
final class SampleAppProcess implements AutoCloseable {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final long POLL_INTERVAL_MILLIS = 100L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final String label;
    private final Process process;
    private final StringBuffer output;
    private final Thread drainer;
    private final HttpClient http;
    private final int port;

    private SampleAppProcess(String label, Process process, StringBuffer output, Thread drainer, int port) {
        this.label = label;
        this.process = process;
        this.output = output;
        this.drainer = drainer;
        this.port = port;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Launches the application from a fat jar, the way it is actually deployed.
     */
    static SampleAppProcess launchJar(String label, Path jar, Path workDir) {
        return launch(label, List.of("-jar", jar.toString()), workDir);
    }

    /**
     * Launches the same application off a directory class path — the layout {@code bootRun} and every IDE use.
     */
    static SampleAppProcess launchExploded(String label, String classpath, Path workDir) {
        return launch(label, List.of("-cp", classpath, "at.aimon.sample.SampleApplication"), workDir);
    }

    private static SampleAppProcess launch(String label, List<String> tail, Path workDir) {
        final Path portFile = workDir.resolve("port");
        final Path workspaceRoot = workDir.resolve("workspace");

        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Daimon.sample.port-file=" + portFile);
        // Each launch materialises into a directory of its own. Sharing one would let a later run pass on files
        // an earlier run wrote, which is the exact confusion the exploded-versus-packaged comparison exists to
        // avoid.
        command.add("-Daimon.workspace.root=" + workspaceRoot);
        command.addAll(tail);

        try {
            Files.createDirectories(workDir);
            final Process process = new ProcessBuilder(command)
                    // The child's log is evidence — a warning it must emit is one of the assertions — so stderr
                    // is folded into stdout and the whole stream is kept.
                    .redirectErrorStream(true).start();

            final StringBuffer output = new StringBuffer();
            final Thread drainer = new Thread(() -> drain(process, output), "sample-app-output-" + label);
            drainer.setDaemon(true);
            drainer.start();

            final int port = awaitPort(label, process, portFile, output);
            return new SampleAppProcess(label, process, output, drainer, port);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to launch the sample application (" + label + ")", e);
        }
    }

    private static void drain(Process process, StringBuffer sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.append(line).append('\n');
            }
        } catch (IOException e) {
            // The stream closes when the process exits; that is not a failure worth reporting over the one that
            // will already be reported by whatever was waiting on the process.
            sink.append("<output stream closed: ").append(e.getMessage()).append(">\n");
        }
    }

    private static int awaitPort(String label, Process process, Path portFile, StringBuffer output) {
        final long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(portFile)) {
                try {
                    final String text = Files.readString(portFile, StandardCharsets.UTF_8).trim();
                    if (!text.isEmpty()) {
                        return Integer.parseInt(text);
                    }
                } catch (IOException | NumberFormatException e) {
                    // Written in a single call after the server is listening, so a partial read means the write
                    // has not happened yet rather than that it went wrong. Keep waiting.
                }
            }
            if (!process.isAlive()) {
                throw new IllegalStateException("The sample application (" + label + ") exited with code "
                        + process.exitValue() + " before reporting a port. Output:\n" + output);
            }
            sleep();
        }
        process.destroyForcibly();
        throw new IllegalStateException("The sample application (" + label + ") did not report a port within "
                + STARTUP_TIMEOUT.toSeconds() + "s. Output:\n" + output);
    }

    private static void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the sample application to start", e);
        }
    }

    /**
     * Asks what the framework assembled: registries, workspace contents, class path shape.
     */
    Map<String, Object> introspect() {
        return send(HttpRequest.newBuilder(uri("/aimon/introspect")).GET());
    }

    /**
     * Runs one real turn and reports both the answer and the tool definitions the model was shown.
     */
    Map<String, Object> turn(String session, String input) {
        return send(HttpRequest.newBuilder(uri("/aimon/turn?session=" + session + "&input=" + input))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** Everything the child has written to stdout and stderr so far. */
    String output() {
        return output.toString();
    }

    /** A label for failure messages — which of the launch shapes this is. */
    String label() {
        return label;
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Map<String, Object> send(HttpRequest.Builder builder) {
        try {
            final HttpResponse<String> response = http.send(builder.timeout(REQUEST_TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("The sample application (" + label + ") answered "
                        + response.statusCode() + ": " + response.body() + "\nOutput:\n" + output);
            }
            return MAPPER.readValue(response.body(), JSON_OBJECT);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Request to the sample application (" + label + ") failed. Output:\n" + output, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the sample application (" + label + ")", e);
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            // Joined so the buffer is complete before a failing test prints it.
            drainer.join(SHUTDOWN_TIMEOUT.toMillis());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
