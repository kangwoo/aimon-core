package at.aimon.workflow.graaljs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import at.aimon.core.subagent.Subagent;
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
 * <li>Requires at least one of {@code systemPrompt}/{@code agentType} — otherwise a loud {@link JsScriptException}.
 * </ul>
 */
final class InlineSubagentResolver implements SubagentResolver {

    private static final String NAME_PREFIX = "graaljs:";
    private static final int HASH_NAME_LENGTH = 12;

    @Override
    public Subagent resolve(String agentType, String systemPrompt, String model, List<String> tools,
            Integer maxIterations) {
        final boolean hasType = agentType != null && !agentType.isBlank();
        final boolean hasPrompt = systemPrompt != null && !systemPrompt.isBlank();
        if (!hasType && !hasPrompt) {
            throw new JsScriptException("agent descriptor requires 'agentType' or 'systemPrompt'");
        }

        final String effectivePrompt = hasPrompt ? systemPrompt : "You are the \"" + agentType + "\" subagent.";
        final String name = hasType ? NAME_PREFIX + agentType : NAME_PREFIX + shortHash(effectivePrompt);

        final Subagent.Builder builder = Subagent.builder().name(name).systemPrompt(effectivePrompt);
        if (model != null && !model.isBlank()) {
            builder.model(model);
        }
        if (tools != null && !tools.isEmpty()) {
            builder.tools(tools);
        }
        if (maxIterations != null) {
            builder.maxIterations(maxIterations);
        }
        return builder.build();
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
