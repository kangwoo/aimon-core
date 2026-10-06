package at.aimon.workflow.graaljs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
 * registered), plus the descriptor's own ({@link DefinitionAttributes#overlay(Map, Map)}), under two rules. A script
 * is model-authored and an attribute is what an execution environment provider reads to place the step, so neither
 * rule lets the script choose a placement the operator did not offer.
 * <ul>
 * <li>The registered definition's keys are <b>pinned</b>: a descriptor attribute whose key the registered definition
 * already sets is rejected with a {@link JsScriptException} unless its value is identical (then it is a no-op).
 * Otherwise a script could move a step out of the placement the operator chose (for example
 * {@code sandbox.slot: isolated} → {@code privileged}).
 * <li>Every other key must be one the operator <b>allowed</b> ({@code scriptAttributeKeys}), or it is rejected the
 * same way. That covers a key a registered definition does not set, and every key of a step whose {@code agentType} is
 * unregistered or absent — where nothing is pinned, so without this rule renaming the step would be enough to ask for
 * any slot. The allow-list is empty by default.
 * </ul>
 * Nothing else is taken from the registered definition.
 * <li>Requires at least one of {@code systemPrompt}/{@code agentType} — otherwise a loud {@link JsScriptException}.
 * </ul>
 */
final class InlineSubagentResolver implements SubagentResolver {

    private static final Logger log = LoggerFactory.getLogger(InlineSubagentResolver.class);

    private static final String NAME_PREFIX = "graaljs:";
    private static final int HASH_NAME_LENGTH = 12;

    private final SubagentRegistry registry;
    private final Set<String> scriptAttributeKeys;

    /**
     * @param registry
     *            the registry looked up by {@code agentType} for attributes (nullable; when null, none are looked up)
     * @param scriptAttributeKeys
     *            the flattened attribute keys a script may set where no registered definition sets them (must not be
     *            null; empty allows none)
     */
    InlineSubagentResolver(SubagentRegistry registry, Collection<String> scriptAttributeKeys) {
        this.registry = registry;
        this.scriptAttributeKeys = copyOfKeys(scriptAttributeKeys);
    }

    /** Copies the allow-list in a stable order (it is printed in the refusal), refusing a null or blank key. */
    private static Set<String> copyOfKeys(Collection<String> keys) {
        Objects.requireNonNull(keys, "scriptAttributeKeys must not be null");
        final Set<String> copy = new TreeSet<>();
        for (final String key : keys) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("scriptAttributeKeys must not contain a null or blank key");
            }
            copy.add(key);
        }
        return Collections.unmodifiableSet(copy);
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
        final Map<String, String> registered = registeredAttributes(agentType);
        rejectWhatTheScriptMayNotSet(agentType != null ? agentType : name, registered, descriptor.attributes());
        try {
            builder.attributes(DefinitionAttributes.overlay(registered, descriptor.attributes()));
        } catch (IllegalArgumentException e) {
            throw new JsScriptException("agent '" + (agentType != null ? agentType : name) + "': " + e.getMessage(), e);
        }
        return builder.build();
    }

    /**
     * Rejects a script attribute that would change a key the registered definition sets, or that sets a key the
     * operator did not allow a script to set. An identical value for a registered key is accepted (the overlay leaves
     * it unchanged), allowed or not: it asks for nothing the definition does not already give.
     *
     * @param agent
     *            how the refusal names the step: its {@code agentType}, or its synthesized name when it has none
     */
    private void rejectWhatTheScriptMayNotSet(String agent, Map<String, String> registered,
            Map<String, String> scriptAttributes) {
        for (Map.Entry<String, String> entry : scriptAttributes.entrySet()) {
            final String key = entry.getKey();
            final String registeredValue = registered.get(key);
            if (registeredValue != null) {
                if (!registeredValue.equals(entry.getValue())) {
                    throw new JsScriptException("agent '" + agent + "': attribute '" + key
                            + "' is set by the registered subagent definition ('" + registeredValue
                            + "') and cannot be overridden by the script ('" + entry.getValue()
                            + "'); registered attributes are pinned");
                }
            } else if (!scriptAttributeKeys.contains(key)) {
                throw new JsScriptException("agent '" + agent + "': attribute '" + key
                        + "' is not one a script may set (allowed: "
                        + (scriptAttributeKeys.isEmpty() ? "none" : String.join(", ", scriptAttributeKeys))
                        + "). Attributes choose where a step runs, so a script may set only the keys the operator "
                        + "allowed. Remove it, or have the operator either register a subagent of that name whose "
                        + "definition sets '" + key + "' or allow the key with scriptAttributeKeys "
                        + "(GraalJsWorkflowTool.Builder.scriptAttributeKeys)");
            }
        }
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
