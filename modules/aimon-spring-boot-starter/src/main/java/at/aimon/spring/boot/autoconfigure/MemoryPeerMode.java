package at.aimon.spring.boot.autoconfigure;

/**
 * Whose memory an agent reads, selected by {@code aimon.memory.peer-mode}.
 *
 * <p>
 * The two are not variations on one setting — they reach different amounts of the feature, and the difference is
 * not something a running deployment reports. {@link #FIXED} wires everything memory has; {@link #CALLER} wires
 * the injected part and no tools at all, for a reason that lives in the core SPI rather than in this starter (see
 * below). Choosing between them is therefore a decision about what memory will be able to do, which is why there
 * is no value meaning "both" and no attempt to infer one.
 *
 * <p>
 * <b>Why the tools cannot follow the caller.</b> A tool is given its observer through a
 * {@code ToolContextEnricher}, and the enrichment info it is handed carries a session id, an execution id and a
 * runtime id — no principal. So the enricher can bind exactly one observer, decided when the stack is built.
 * Registering the memory tools under {@link #CALLER} anyway would give the model three tools that answer "no
 * workspace in context" to every call, which is why the stack registers none and records a degradation instead.
 */
public enum MemoryPeerMode {

    /**
     * Every agent reads and writes the memory of one peer named by {@code aimon.memory.peer-id}. The full
     * feature: the injected memory part, {@code MemoryRecall}, {@code MemorySearch} and {@code Observe}.
     *
     * <p>
     * The default, because it is the mode that works completely. It is also the mode that must not be pointed at
     * a shared workspace by accident — one peer's memory reaching every session is a privacy question, not a
     * configuration detail, and that is what the mandatory {@code peer-id} makes someone type out.
     */
    FIXED,

    /**
     * Each execution reads the memory of whoever it is running for. The injected memory part follows the caller;
     * no memory tools are registered, and the stack records a degradation saying so.
     */
    CALLER
}
