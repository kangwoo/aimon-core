package at.aimon.sample;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Writes the port the server actually bound to, so the process that launched this one can find it.
 *
 * <p>
 * The packaging tests run the application in a child JVM and talk to it over HTTP, which leaves them needing a
 * port. Every way of choosing one in advance is a race — reserve a socket, close it, hope nothing else takes it
 * between then and startup — and every way of recovering one afterwards by reading the log is a bet on the
 * wording of a message Spring Boot is free to change. Letting the server pick ({@code server.port=0}) and having
 * it say where it landed is neither.
 *
 * <p>
 * Inert unless {@code aimon.sample.port-file} is set, so the sample still runs as a plain application.
 */
@Component
public class PortFileWriter implements ApplicationListener<WebServerInitializedEvent> {

    private final String portFile;

    /**
     * Creates the listener.
     *
     * @param portFile
     *            where to write the bound port; empty (the default) disables the listener entirely
     */
    public PortFileWriter(@Value("${aimon.sample.port-file:}") String portFile) {
        this.portFile = portFile;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (portFile == null || portFile.isBlank()) {
            return;
        }
        try {
            // Written last, in one call, after the server is already listening: a reader that sees the file at
            // all can connect, so no handshake beyond "does it exist yet" is needed on the other side.
            Files.writeString(Path.of(portFile), Integer.toString(event.getWebServer().getPort()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Nothing downstream can proceed without the port, and a sample that limps on is worse than one that
            // stops with the reason on stderr.
            throw new UncheckedIOException("Failed to write the port file at " + portFile, e);
        }
    }
}
