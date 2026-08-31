/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import at.aimon.core.llm.LlmModel;

/**
 * A content hash of an {@link Agent} definition, used to tell whether the definition changed between two points in
 * time.
 *
 * <p>
 * This exists for work that outlives the moment it was requested. A cron task scheduled today fires against whatever
 * {@code AgentRuntime} is registered when the cron actually rings, and that runtime may have been rebuilt from an
 * {@code agent.md} someone edited in between. Nothing in this framework pins the old definition &mdash; deliberately,
 * because a task quietly running last month's prompt is the worse failure. What is left is the ability to <em>say</em>
 * that it changed, which is what this type provides: record the version when the work is scheduled, compare at fire
 * time, and put the difference in the log.
 *
 * <p>
 * The hash covers what changes an agent's behaviour: name, max iterations, sampling model, tags, system prompt, and
 * definition variables. It is a SHA-256 over a canonical rendering of those, truncated to 16 hex characters &mdash;
 * short enough to sit in a log line, wide enough that an accidental collision is not a practical concern. Ordering of
 * tags and variables does not affect the result, so two loads of the same bundle agree.
 *
 * <p>
 * <b>This is a change detector, not a provenance record.</b> Equal versions mean the definition is unchanged in the
 * fields above; they do not mean the same file produced them, and they say nothing about the tools the runtime
 * resolved, which are not part of the {@link Agent} definition at all.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class AgentDefinitionVersion {

    /** Hex characters kept from the digest. */
    private static final int LENGTH = 16;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String value;

    private AgentDefinitionVersion(String value) {
        this.value = value;
    }

    /**
     * Computes the version of the given agent definition.
     *
     * @param agent
     *            The agent whose definition is hashed (must not be null)
     * @return The definition version (never null)
     * @throws NullPointerException
     *             if agent is null
     */
    public static AgentDefinitionVersion from(Agent agent) {
        Objects.requireNonNull(agent, "Agent cannot be null");
        return new AgentDefinitionVersion(digest(canonicalForm(agent)));
    }

    /**
     * Rehydrates a previously recorded version, for example from a persisted task.
     *
     * @param value
     *            The recorded version string (must not be null or blank)
     * @return The definition version (never null)
     * @throws NullPointerException
     *             if value is null
     * @throws IllegalArgumentException
     *             if value is blank
     */
    public static AgentDefinitionVersion of(String value) {
        Objects.requireNonNull(value, "Version value cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Version value cannot be blank");
        }
        return new AgentDefinitionVersion(value);
    }

    /**
     * Returns the version string, suitable for persisting or logging.
     *
     * @return The version string (never null)
     */
    public String value() {
        return value;
    }

    /**
     * Builds the canonical rendering that is hashed.
     *
     * <p>
     * One {@code key=value} per line. Maps and sets are sorted so that iteration order cannot leak into the digest, and
     * absent optional values are rendered as the empty string rather than skipped &mdash; skipping would let an absent
     * key and an empty value collide.
     */
    private static String canonicalForm(Agent agent) {
        final AgentMetadata metadata = agent.getMetadata();
        final AgentContent content = agent.getContent();
        final LlmModel model = metadata.getModel();

        final List<String> lines = new ArrayList<>();
        lines.add("name=" + metadata.getName());
        lines.add("maxIterations=" + metadata.getMaxIterations());
        lines.add("model.name=" + render(model.getName()));
        lines.add("model.temperature=" + render(model.getTemperature()));
        lines.add("model.maxTokens=" + render(model.getMaxTokens()));
        lines.add("model.topP=" + render(model.getTopP()));
        lines.add("model.presencePenalty=" + render(model.getPresencePenalty()));
        lines.add("model.frequencyPenalty=" + render(model.getFrequencyPenalty()));
        lines.add("model.requestTimeout=" + render(model.getRequestTimeout()));
        lines.add("tags=" + String.join(",", new TreeSet<>(metadata.getTags())));
        for (Map.Entry<String, Object> variable : new TreeMap<>(content.getVariables()).entrySet()) {
            lines.add("var." + variable.getKey() + "=" + variable.getValue());
        }
        lines.add("systemPrompt=" + content.getSystemPrompt());

        return String.join("\n", lines);
    }

    private static String render(Optional<?> value) {
        return value.map(String::valueOf).orElse("");
    }

    private static String digest(String canonical) {
        final MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java SE implementation is required to support SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }

        final byte[] bytes = sha256.digest(canonical.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder(LENGTH);
        for (int i = 0; hex.length() < LENGTH; i++) {
            hex.append(HEX[(bytes[i] >> 4) & 0xF]);
            hex.append(HEX[bytes[i] & 0xF]);
        }
        return hex.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return value.equals(((AgentDefinitionVersion) o).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
