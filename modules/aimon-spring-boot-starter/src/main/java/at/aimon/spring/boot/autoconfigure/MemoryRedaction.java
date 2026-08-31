package at.aimon.spring.boot.autoconfigure;

/**
 * What is stripped out of an observation before it is stored, selected by {@code aimon.memory.redaction}.
 *
 * <p>
 * This is the one memory setting whose wrong value is not recoverable. An agent decides for itself what is worth
 * observing, out of whatever reached the conversation; the store keeps it across sessions and hands it back to a
 * later prompt. A credential that gets in has been copied somewhere the operator did not choose and does not
 * expire, and no later change of this property removes it.
 *
 * <p>
 * Which is why {@link #DEFAULT} rather than {@link #NONE} is the default, in a starter that otherwise refuses to
 * pick for you. The choice being made silently here is "redact", and its cost when wrong is a masked substring in
 * a memory nobody reads twice.
 */
public enum MemoryRedaction {

    /**
     * The built-in {@code DefaultRedactionPolicy}: AWS access keys, Bearer/JWT tokens, RFC1918 addresses, e-mail
     * addresses and {@code key=value} secret pairs. The default.
     */
    DEFAULT,

    /**
     * {@code StrictRedactionPolicy} — the default rules plus a fuzzy pass that also catches misspelled secret
     * keywords ({@code passwrd=}, {@code apikkey=}). Costs some false positives in ordinary prose.
     */
    STRICT,

    /**
     * Store observations verbatim. Everything the model is told to observe is persisted as it stood, including
     * anything secret that reached the conversation. The stack records this as a degradation, so a deployment
     * that arrived here by inheriting a properties file finds out at startup rather than at audit.
     */
    NONE,

    /**
     * The application declares its own {@code RedactionPolicy} bean. Startup fails by name if it does not, and
     * fails by name if it declares one under any other value here — a policy bean that is never consulted looks
     * exactly like one that is.
     */
    SUPPLIED
}
