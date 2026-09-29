package at.aimon.workflow.graaljs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.base.DefinitionAttributes;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * Default {@link SubagentResolver}: turns a descriptor into an inline {@link Subagent} with a deterministic,
 * cross-JVM-stable name.
 *
 * <ul>
 * <li>name = {@code "graaljs:" + agentType} when an {@code agentType} is given, else
 * {@code "graaljs:sha256(systemPrompt)[:12]"} — stable across JVMs so persistent resume caches replay correctly,
 * avoiding {@code Subagent.hashCode}'s identity caveat.
 * <li>systemPrompt = the explicit prompt, else a synthesized {@code "You are the \"<agentType>\" subagent."}.
 * <li>attributes = those of the subagent registered under {@code agentType} (when there is a registry and one is
 * registered), overlaid key by key by the descriptor's own ({@link DefinitionAttributes#overlay(Map, Map)}). Nothing
 * else is taken from the registered definition.
 * <li>Requires at least one of {@code systemPrompt}/{@code agentType} — otherwise a loud {@link JsScriptException}.
 * </ul>
 */
final class InlineSubagentResolver implements SubagentResolver {

    private static final Logger log = LoggerFactory.getLogger(InlineSubagentResolver.class);

    private static final String NAME_PREFIX = "graaljs:";
    private static final int HASH_NAME_LENGTH = 12;

    private final SubagentRegistry registry;

    /**
     * @param registry
     *            the registry looked up by {@code agentType} for attributes (nullable; when null, none are looked up)
     */
    InlineSubagentResolver(SubagentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Subagent resolve(SubagentDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
        final String agentType = descriptor.agentType().orElse(null);
        final String systemPrompt = descriptor.systemPrompt().orElse(null);
        if (agentType == null && systemPrompt == null) {
            throw new JsScriptException("agent descriptor requires 'agentType' or 'systemPrompt'");
        }

        final String effectivePrompt = systemPrompt != null
                ? systemPrompt
                : "You are the \"" + agentType + "\" subagent.";
        final String name = agentType != null ? NAME_PREFIX + agentType : NAME_PREFIX + shortHash(effectivePrompt);

        final Subagent.Builder builder = Subagent.builder().name(name).systemPrompt(effectivePrompt);
        descriptor.model().ifPresent(builder::model);
        if (!descriptor.tools().isEmpty()) {
            builder.tools(descriptor.tools());
        }
        descriptor.maxIterations().ifPresent(builder::maxIterations);
        try {
            builder.attributes(DefinitionAttributes.overlay(registeredAttributes(agentType), descriptor.attributes()));
        } catch (IllegalArgumentException e) {
            throw new JsScriptException("agent '" + (agentType != null ? agentType : name) + "': " + e.getMessage(), e);
        }
        return builder.build();
    }

    /** The attributes of the subagent registered under {@code agentType}, or none. */
    private Map<String, String> registeredAttributes(String agentType) {
        if (agentType == null || registry == null) {
            return Map.of();
        }
        final Optional<Subagent> registered;
        try {
            registered = registry.getSubagent(agentType);
        } catch (RuntimeException e) {
            throw new JsScriptException("agent '" + agentType + "': subagent registry lookup failed: " + e.getMessage(),
                    e);
        }
        if (registered.isEmpty()) {
            log.debug("agent '{}': no registered subagent of that name; no registered attributes to copy", agentType);
            return Map.of();
        }
        return registered.get().getMetadata().getAttributes();
    }

    private static String shortHash(String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, HASH_NAME_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JVM algorithm; this is unreachable.
            throw new JsScriptException("SHA-256 unavailable for subagent name synthesis", e);
        }
    }
}
