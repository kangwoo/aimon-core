package at.aimon.sample;

import java.util.List;

import at.aimon.core.llm.LlmClient;

/**
 * A model that remembers the tool definitions it was shown on its most recent call.
 *
 * <p>
 * This exists because the sample runs two different models — one per profile — and the introspection endpoint has
 * to read the recordings of whichever one the executor actually called. Depending on the concrete class would
 * mean the endpoint could only exist under one profile; depending on {@link LlmClient} would give up the
 * recording, which is the strongest assertion the sample makes. The recording is the only part the endpoint
 * needs, so it is the only part promoted to an interface.
 *
 * <p>
 * Implementations must be thread-safe: a turn may run on any request thread, and the live profile runs several
 * concurrently on purpose.
 */
public interface RecordingLlmClient extends LlmClient {

    /**
     * Returns the tool definitions handed to the model on the most recent call, each as {@code name\ndescription}.
     *
     * @return the definitions from the last call, or an empty list if the model was never called
     */
    List<String> lastToolDefinitions();
}
