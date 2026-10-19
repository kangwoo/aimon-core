package at.aimon.core.skill.hook.declarative;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.exception.StagingException;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.skill.hook.action.HookScriptDirectory;

/**
 * Builds the {@link RunDirectory} a hook runs from: the bytes of its directory, read and checked against a content key
 * in this JVM, which the shell places for that one run (EE-109).
 *
 * <p>
 * A staged copy in the workspace is a file the agent's shell can rewrite between a check and the moment the
 * interpreter opens it. Bytes handed to the shell with the command are not: the shell places them in a fresh directory
 * and removes it afterwards.
 *
 * <ul>
 * <li><b>{@code hooks.json}</b> ({@link #forHookDirectory}): the layer's {@code hooks/} directory is scanned and read
 * at every firing, so an operator's edit is picked up at the next one. The bytes are hashed again as they are read; a
 * directory that changes between the scan and the read is read once more and then refused.</li>
 * <li><b>Skill hooks</b> ({@link #forSkill}): the declaring skill's files are read from its source and checked against
 * the content key the registry recorded. The answer is kept in a bounded in-memory cache, so the version the registry
 * loaded is served until it reloads <em>or its entry is evicted</em> &mdash; the rule staging follows (EE-3), with the
 * copy in JVM memory instead of in the workspace, and as staging's copy is served only while it exists. Once evicted,
 * the next firing reads the source again: a skill edited since it was loaded then runs as it is now. A scanned skill
 * whose source changed since it was loaded is scanned again and served as it is now,
 * with one WARN; a skill assembled by hand is refused instead, because its directory may hold files it deliberately
 * left out.</li>
 * </ul>
 *
 * <p>
 * Every cache entry is filed under the content key its own bytes hash to, never under another. The answer for an
 * edited skill is therefore not remembered as the loaded version: each firing first asks whether the source holds the
 * loaded version again, so a skill that is reverted and reloaded runs as the registry now has it, not as it was edited.
 * The cache belongs to the {@link DefaultShellActionExecutor} that created it.
 *
 * <p>
 * Both are capped at {@link RunDirectory#MAX_TOTAL_BYTES}. A {@code hooks.json} directory over it is refused; a skill
 * over it is left to the staging fallback ({@link DefaultShellActionExecutor}). A file whose name a run directory
 * cannot place (a control character, or a file and a directory of one name) is refused in both cases, never staged:
 * staging could not place it either, and the fix is to rename it or exclude it.
 */
final class HookRunFiles {

    /** The most the skill cache holds, summed over its entries; the least recently used go first. */
    static final long MAX_CACHED_BYTES = 32L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(HookRunFiles.class);

    private HookRunFiles() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Reads a {@code hooks.json} layer's {@code hooks/} directory into a run directory for {@code AIMON_HOOK_DIR}.
     *
     * @param directory
     *            the directory the action carries (never null)
     * @return the run directory (never null)
     * @throws StagingException
     *             with a message that may be shown to the model, when the directory is over
     *             {@link RunDirectory#MAX_TOTAL_BYTES}, keeps changing while it is read, or holds a file a run
     *             directory
     *             cannot place
     * @throws RuntimeException
     *             whatever the scan throws when the directory cannot be read (its message may name host paths)
     */
    static RunDirectory forHookDirectory(HookScriptDirectory directory) {
        Objects.requireNonNull(directory, "directory cannot be null");
        for (int attempt = 0; attempt < 2; attempt++) {
            final StagedResource scanned = StagedResource.scan(directory.getSourceFileSystem(),
                    directory.getSourceDirectory(), directory.getResourceName());
            if (scanned.getTotalBytes() > RunDirectory.MAX_TOTAL_BYTES) {
                throw overLimit("The hooks directory", scanned.getTotalBytes());
            }
            final Optional<RunDirectory> read = read(scanned, SkillHookEnv.AIMON_HOOK_DIR);
            if (read.isPresent()) {
                return read.get();
            }
        }
        throw new StagingException("The hooks directory is changing on disk while it is being read, so no consistent"
                + " version of it could be run. Try again once its files are no longer being written.");
    }

    /**
     * Returns the run directory for a skill hook's {@code AIMON_SKILL_DIR}, from the cache or read from the skill's
     * source.
     *
     * @param cache
     *            the cache of the executor asking (never null)
     * @param resource
     *            the declaring skill's resource, as the registry loaded it (never null)
     * @return the run directory, or empty when the skill is over {@link RunDirectory#MAX_TOTAL_BYTES} and has to be
     *         staged instead
     * @throws StagingException
     *             with a message that may be shown to the model, when the source no longer holds what the resource
     *             names and cannot be followed (assembled by hand, or changing while it is read), or when it holds a
     *             file a run directory cannot place
     * @throws RuntimeException
     *             whatever the source throws when it cannot be read
     */
    static Optional<RunDirectory> forSkill(SkillCache cache, StagedResource resource) {
        Objects.requireNonNull(cache, "cache cannot be null");
        Objects.requireNonNull(resource, "resource cannot be null");
        final String key = cacheKey(resource);
        final Optional<RunDirectory> cached = cache.get(key);
        if (cached.isPresent()) {
            return cached;
        }
        if (resource.getTotalBytes() > RunDirectory.MAX_TOTAL_BYTES) {
            return Optional.empty();
        }
        final Optional<RunDirectory> loaded = read(resource, SkillHookEnv.AIMON_SKILL_DIR);
        if (loaded.isPresent()) {
            cache.put(key, loaded.get());
            return loaded;
        }
        // The source no longer holds what the registry loaded (EE-3).
        if (!resource.isScanned()) {
            throw new StagingException("Skill '" + resource.getName() + "' changed on disk after it was loaded, so"
                    + " its files no longer match the version this session uses. Restart the application, or reload"
                    + " the skill registry, to pick up the change.");
        }
        final StagedResource current = StagedResource.scan(resource.getSourceFileSystem(), resource.getSourceDir(),
                resource.getName());
        if (current.getTotalBytes() > RunDirectory.MAX_TOTAL_BYTES) {
            return Optional.empty();
        }
        // Filed under the key the bytes hash to, not the loaded one: a source reverted to the loaded version must find
        // that version again at the next firing, not this answer.
        final String currentKey = cacheKey(current);
        final Optional<RunDirectory> known = cache.get(currentKey);
        if (known.isPresent()) {
            log.debug("Skill '{}' still differs from the version loaded; its hooks run it as it is now",
                    resource.getName());
            return known;
        }
        final RunDirectory now = read(current, SkillHookEnv.AIMON_SKILL_DIR)
                .orElseThrow(() -> new StagingException("Skill '" + resource.getName() + "' is changing on disk while"
                        + " it is being read, so no consistent copy of it could be made. Try again once its files are"
                        + " no longer being written."));
        log.warn("Skill '{}' changed on disk since it was loaded; its hooks run it as it is now. What was parsed from"
                + " it at load stays as it is until the skill registry reads it again", resource.getName());
        cache.put(currentKey, now);
        return Optional.of(now);
    }

    private static String cacheKey(StagedResource resource) {
        return resource.getName() + '\0' + resource.getSourceDir() + '\0' + resource.getContentKey();
    }

    /**
     * Reads the resource's files and checks them against its content key.
     *
     * @return the run directory, or empty when a file could not be read, the files outgrew
     *         {@link RunDirectory#MAX_TOTAL_BYTES}, or the
     *         bytes read do not hash to the key
     */
    private static Optional<RunDirectory> read(StagedResource resource, String variable) {
        final RunDirectory.Builder builder = RunDirectory.builder().variable(variable);
        final StagedResource.ContentKeyBuilder hasher = new StagedResource.ContentKeyBuilder();
        long total = 0;
        final List<String> files = resource.getFiles();
        for (String relPath : files) {
            final byte[] bytes;
            try (InputStream in = resource.getSourceFileSystem().read(resource.sourcePath(relPath))) {
                bytes = in.readAllBytes();
            } catch (IOException | RuntimeException e) {
                // Gone since the scan or the load, or a read that failed: the caller reads again or refuses.
                log.debug("Could not read {} of '{}' for a hook: {}", relPath, resource.getName(), e.toString());
                return Optional.empty();
            }
            total += bytes.length;
            if (total > RunDirectory.MAX_TOTAL_BYTES) {
                return Optional.empty();
            }
            hasher.add(relPath, bytes);
            try {
                builder.file(relPath, bytes);
            } catch (IllegalArgumentException e) {
                // The message names the path inside the resource, with control characters escaped: no host path.
                throw new StagingException("Cannot read '" + resource.getName() + "' for the hook: " + e.getMessage()
                        + ". Rename the file, or exclude it with " + StagedResource.STAGE_IGNORE_FILE, e);
            }
        }
        if (!hasher.build().equals(resource.getContentKey())) {
            return Optional.empty();
        }
        return Optional.of(builder.build());
    }

    private static StagingException overLimit(String what, long bytes) {
        return new StagingException(what + " holds " + bytes + " bytes, over the " + RunDirectory.MAX_TOTAL_BYTES
                + "-byte limit for a hook's files; exclude large files with " + StagedResource.STAGE_IGNORE_FILE);
    }

    /**
     * An LRU map of run directories bounded by the bytes they hold, each filed under the content key its bytes hash to.
     * Thread-safe.
     */
    static final class SkillCache {

        private final long maxBytes;
        private final LinkedHashMap<String, RunDirectory> entries = new LinkedHashMap<>(16, 0.75f, true);
        private long bytes;

        SkillCache(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        synchronized Optional<RunDirectory> get(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        synchronized void put(String key, RunDirectory value) {
            final RunDirectory previous = entries.put(key, value);
            if (previous != null) {
                bytes -= previous.getTotalBytes();
            }
            bytes += value.getTotalBytes();
            final Iterator<Map.Entry<String, RunDirectory>> eldest = entries.entrySet().iterator();
            while (bytes > maxBytes && eldest.hasNext()) {
                final Map.Entry<String, RunDirectory> entry = eldest.next();
                if (entry.getKey().equals(key)) {
                    continue;
                }
                bytes -= entry.getValue().getTotalBytes();
                eldest.remove();
            }
        }

    }
}
