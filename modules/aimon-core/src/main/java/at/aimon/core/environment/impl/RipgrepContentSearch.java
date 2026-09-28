package at.aimon.core.environment.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.environment.ContentQuery;
import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.ContentSearchResult;
import at.aimon.core.filesystem.VfsPaths;

/**
 * A {@link ContentSearch} that runs {@code rg --json} in a local directory and normalises its output
 * (execution-environment design §4.2, §14 — the result is a value, not rg's wire format).
 *
 * <p>
 * It searches the way {@code Grep}'s own walk does, so both paths print the same text: {@code --no-ignore --hidden}
 * (the walk ignores nothing), {@code -a} (the walk reads every file as text), and one exclusion glob per hidden
 * ({@code DENY}) prefix. The exclusion globs alone do not keep the control store out — rg never applies them to a path
 * named on its command line, and a later user glob overrides them — so a target at or under a hidden prefix is refused
 * (the walk then answers, and finds nothing, as the path-rule filesystem hides it), and every reported path under one
 * is dropped. Context lines are rebuilt per match exactly as the walk
 * builds them. Multiline queries are not answered (rg reports multiline matches as whole line blocks, which the walk
 * does not); like any other failure — rg missing, a pattern rg rejects, a non-UTF-8 file — that throws, and
 * {@code Grep} walks the filesystem instead.
 *
 * <p>
 * The target is also checked by its real path: a symbolic link named as the target (rg follows those, though never one
 * it meets while walking) must land inside the root and outside every hidden prefix, as the walk's own path validation
 * requires. rg's output is drained on its own thread while the caller waits for the process, so the timeout and the
 * query's cancellation take effect while rg is still running: either kills it.
 */
final class RipgrepContentSearch implements ContentSearch {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final long POLL_MILLIS = 50;

    private final Path executable;
    private final Path root;
    private final List<String> hiddenPrefixes;
    private final Duration timeout;

    RipgrepContentSearch(Path executable, Path root, List<String> hiddenPrefixes) {
        this(executable, root, hiddenPrefixes, DEFAULT_TIMEOUT);
    }

    RipgrepContentSearch(Path executable, Path root, List<String> hiddenPrefixes, Duration timeout) {
        this.executable = Objects.requireNonNull(executable, "executable must not be null");
        this.root = Objects.requireNonNull(root, "root must not be null");
        this.hiddenPrefixes = List.copyOf(hiddenPrefixes);
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    /**
     * Finds {@code rg} on the {@code PATH} without starting a process.
     *
     * @return the executable, or empty when none is found
     */
    static Optional<Path> probe() {
        final String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            for (String name : List.of("rg", "rg.exe")) {
                final Path candidate = Path.of(entry, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** Returns the same search rooted at another directory (an isolated branch), without hidden prefixes. */
    RipgrepContentSearch rootedAt(Path branchRoot) {
        return new RipgrepContentSearch(executable, branchRoot, List.of(), timeout);
    }

    @Override
    public ContentSearchResult search(ContentQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.isMultiline()) {
            throw new UnsupportedOperationException("multiline queries are answered by the filesystem walk");
        }
        final String target = VfsPaths.resolveUnder(root.toString(), query.getPath());
        if (target == null) {
            throw new IllegalArgumentException("path outside the search root: " + query.getPath());
        }
        if (hidden(target)) {
            throw new IllegalArgumentException("path hidden from this environment: " + query.getPath());
        }
        confineRealPath(target, query.getPath());
        final List<String> command = new ArrayList<>(List.of(executable.toString(), "--json", "--no-ignore", "--hidden",
                "-a", "--no-config", "--no-messages"));
        if (query.isCaseInsensitive()) {
            command.add("-i");
        }
        if (query.getBeforeContext() > 0) {
            command.add("-B");
            command.add(String.valueOf(query.getBeforeContext()));
        }
        if (query.getAfterContext() > 0) {
            command.add("-A");
            command.add(String.valueOf(query.getAfterContext()));
        }
        for (String extension : query.getExtensions()) {
            command.add("--iglob");
            command.add("*" + extension);
        }
        query.getGlob().ifPresent(glob -> {
            command.add("--glob");
            command.add(glob);
        });
        // After the user's glob: when several globs match, rg lets the last one win.
        for (String prefix : hiddenPrefixes) {
            command.add("--glob");
            command.add("!/" + prefix);
        }
        command.add("-e");
        command.add(query.getPattern());
        command.add("--");
        command.add(target.isEmpty() ? "." : target);
        return withoutHidden(run(command, query));
    }

    /**
     * rg follows a symbolic link named on its command line: the target's real path must stay inside the root's real
     * path and outside every hidden prefix, or the walk answers instead (and refuses the link itself).
     */
    private void confineRealPath(String target, String requested) {
        final Path realRoot;
        final Path realTarget;
        try {
            realRoot = root.toRealPath();
            realTarget = root.resolve(target.isEmpty() ? "." : target).toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("cannot resolve " + requested + ": " + e.getMessage(), e);
        }
        if (!realTarget.startsWith(realRoot)) {
            throw new IllegalArgumentException("path resolves outside the search root: " + requested);
        }
        if (hidden(realRoot.relativize(realTarget).toString().replace('\\', '/'))) {
            throw new IllegalArgumentException("path resolves into a hidden prefix: " + requested);
        }
    }

    private boolean hidden(String rootRelative) {
        return hiddenPrefixes.stream().anyMatch(prefix -> VfsPaths.isUnderIgnoreCase(rootRelative, prefix));
    }

    /** Drops every file under a hidden prefix, whatever let rg reach it. */
    private ContentSearchResult withoutHidden(ContentSearchResult result) {
        if (hiddenPrefixes.isEmpty()) {
            return result;
        }
        final List<ContentSearchResult.FileMatches> kept = new ArrayList<>();
        for (ContentSearchResult.FileMatches file : result.getFiles()) {
            final String rel = VfsPaths.normalizeRelative(file.getPath());
            if (rel != null && !hidden(rel)) {
                kept.add(file);
            }
        }
        return kept.size() == result.getFiles().size() ? result : ContentSearchResult.of(kept);
    }

    private ContentSearchResult run(List<String> command, ContentQuery query) {
        final Process process;
        try {
            process = new ProcessBuilder(command).directory(root.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new IllegalStateException("could not start rg: " + e.getMessage(), e);
        }
        // Drain stdout on its own thread: reading it here would block until rg exits, and neither the timeout nor a
        // cancellation could stop a stuck rg.
        final FutureTask<byte[]> drain = new FutureTask<>(() -> {
            try (InputStream stdout = process.getInputStream()) {
                return stdout.readAllBytes();
            }
        });
        final Thread drainer = new Thread(drain, "rg-output");
        drainer.setDaemon(true);
        drainer.start();
        final long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (query.isCancelled()) {
                    throw new IllegalStateException("rg cancelled");
                }
                if (System.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException("rg timed out after " + timeout);
                }
            }
            final byte[] output = drain.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            final int exit = process.exitValue();
            if (exit == 1) {
                return ContentSearchResult.of(List.of());
            }
            if (exit != 0) {
                throw new IllegalStateException("rg exited with " + exit);
            }
            return parse(new String(output, StandardCharsets.UTF_8), query);
        } catch (TimeoutException e) {
            throw new IllegalStateException("rg output did not end within " + timeout, e);
        } catch (ExecutionException | IOException e) {
            throw new IllegalStateException("could not read rg output: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for rg", e);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            drain.cancel(true);
        }
    }

    private static ContentSearchResult parse(String output, ContentQuery query) throws IOException {
        final Map<String, TreeMap<Integer, String>> lines = new LinkedHashMap<>();
        final Map<String, TreeSet<Integer>> matched = new LinkedHashMap<>();
        for (String raw : output.split("\n")) {
            if (raw.isBlank()) {
                continue;
            }
            final JsonNode message = MAPPER.readTree(raw);
            final String type = message.path("type").asText();
            if (!"match".equals(type) && !"context".equals(type)) {
                continue;
            }
            final JsonNode data = message.path("data");
            final JsonNode pathText = data.path("path").path("text");
            final JsonNode lineText = data.path("lines").path("text");
            if (pathText.isMissingNode() || lineText.isMissingNode()) {
                throw new IllegalStateException("rg reported a path or line that is not valid UTF-8");
            }
            final String path = stripDotSlash(pathText.asText());
            final int lineNumber = data.path("line_number").asInt();
            lines.computeIfAbsent(path, k -> new TreeMap<>()).put(lineNumber, stripLineEnd(lineText.asText()));
            if ("match".equals(type)) {
                matched.computeIfAbsent(path, k -> new TreeSet<>()).add(lineNumber);
            }
        }
        final List<ContentSearchResult.FileMatches> files = new ArrayList<>();
        for (Map.Entry<String, TreeSet<Integer>> entry : matched.entrySet()) {
            final TreeMap<Integer, String> fileLines = lines.get(entry.getKey());
            final List<ContentSearchResult.Match> matches = new ArrayList<>();
            for (int lineNumber : entry.getValue()) {
                final List<String> before = new ArrayList<>();
                for (int n = Math.max(1, lineNumber - query.getBeforeContext()); n < lineNumber; n++) {
                    final String line = fileLines.get(n);
                    if (line != null) {
                        before.add(line);
                    }
                }
                final List<String> after = new ArrayList<>();
                for (int n = lineNumber + 1; n <= lineNumber + query.getAfterContext(); n++) {
                    final String line = fileLines.get(n);
                    if (line != null) {
                        after.add(line);
                    }
                }
                matches.add(ContentSearchResult.Match.of(lineNumber, fileLines.get(lineNumber), before, after));
            }
            files.add(ContentSearchResult.FileMatches.of(entry.getKey(), matches));
        }
        return ContentSearchResult.of(files);
    }

    private static String stripDotSlash(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p;
    }

    private static String stripLineEnd(String line) {
        if (line.endsWith("\r\n")) {
            return line.substring(0, line.length() - 2);
        }
        if (line.endsWith("\n") || line.endsWith("\r")) {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }
}
