package at.aimon.core.llms.openai;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The abort objects of the calls currently in flight, keyed by the value of {@link OpenAICallAbort#HEADER}.
 *
 * <p>
 * One instance is shared by {@link OpenAILlmClient}, which opens an object before each cancellable streaming call and
 * releases it after, and by {@link OpenAIAbortableHttpClient}, which looks the object up on every attempt the SDK
 * makes. The header is the only thing that crosses the SDK's layers between the two, so it is the only thing they
 * need in common.
 *
 * <p>
 * A key the transport cannot find — the blocking path with {@code LlmCancellation.none()}, or a key whose call was
 * released while a late retry was still being prepared — means "nothing to attach to", and the attempt goes through
 * untouched.
 */
final class OpenAIAbortRegistry {

    private final ConcurrentMap<String, OpenAICallAbort> aborts = new ConcurrentHashMap<>();

    /** Creates and registers the abort object for one call. */
    OpenAICallAbort open() {
        final OpenAICallAbort abort = new OpenAICallAbort(UUID.randomUUID().toString());
        aborts.put(abort.key(), abort);
        return abort;
    }

    /** The registered abort object for {@code key}, or empty for {@code null} or an unknown key. */
    Optional<OpenAICallAbort> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(aborts.get(key));
    }

    /** Unregisters {@code abort} and drops its lever. Idempotent. */
    void release(OpenAICallAbort abort) {
        Objects.requireNonNull(abort, "abort");
        aborts.remove(abort.key(), abort);
        abort.release();
    }

    /** How many calls are registered: a leak check for tests. */
    int size() {
        return aborts.size();
    }
}
