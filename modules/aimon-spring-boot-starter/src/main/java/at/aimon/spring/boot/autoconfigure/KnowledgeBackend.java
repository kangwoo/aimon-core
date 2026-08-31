package at.aimon.spring.boot.autoconfigure;

/**
 * Which store backs knowledge search, selected by {@code aimon.knowledge.backend}.
 *
 * <p>
 * A selector rather than {@code aimon.knowledge.enabled}, for the reason the session store is one: a boolean
 * would have to mean both "build me a store" and "use the one I declared", and those have opposite failure
 * modes. Getting the first wrong indexes nothing; getting the second wrong indexes into a store the application
 * is also writing to. Naming which is meant lets a bean that contradicts the value be refused by name.
 *
 * <p>
 * <b>OpenSearch has no value here on purpose.</b> {@code aimon-knowledge-opensearch} exists and works, but its
 * client factory installs a trust-all {@code X509TrustManager} together with a no-op hostname verifier whenever
 * TLS verification is switched off, and a starter constant would make that one property away from a deployment
 * that believes it configured TLS. An application that wants it declares the store itself under
 * {@link #SUPPLIED} — one {@code @Bean}, and the security decision stays where someone reads it.
 */
public enum KnowledgeBackend {

    /**
     * No knowledge store, and no knowledge tools. The default: an agent that was never given documents has
     * nothing to search, and a store present but empty answers every query with silence that reads like a bug.
     */
    NONE,

    /**
     * The built-in in-memory keyword store, chunked by {@code aimon.knowledge.chunk-size} and
     * {@code chunk-overlap}. It holds its index on the heap and starts empty on every boot, which makes it a
     * development and single-node choice — nothing here replicates, and nothing survives a restart.
     */
    KEYWORD,

    /**
     * The application declares its own {@code KnowledgeStore} bean and this starter wires the tools to it. The
     * store stays the application's: Spring created it, so Spring closes it, and the stack borrows it for the
     * duration.
     */
    SUPPLIED
}
