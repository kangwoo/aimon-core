package at.aimon.core.llms.anthropic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigParam;

/**
 * The whole answer {@link AnthropicThinkingResolver} gives about one request: what goes on the wire, and what the
 * operator should be told about it.
 *
 * <p>
 * <strong>Why the findings are carried here rather than logged where they are noticed.</strong> The thinking
 * resolution has steps that can undo each other — a mode is translated to the dialect the model speaks, and then no
 * legal budget fits under {@code maxTokens} and the parameter is dropped entirely. A step that logs as it goes
 * describes a request that the next step may abandon, which is what three of this client's warnings did. So each step
 * <em>records</em> and the finished resolution decides which records are <em>emitted</em>:
 *
 * <ul>
 * <li>{@link Scope#REQUIRES_THINKING} — <em>the request differs from what you configured</em>. Emitted only when
 * {@link #parameter()} is present, because otherwise there is no request for it to be about.
 * <li>{@link Scope#EXPLAINS_ABSENCE} — <em>your thinking configuration reaches nothing at all</em>. Emitted only when
 * it is absent, which is the one thing left to say.
 * </ul>
 *
 * <p>
 * {@link #findingsToReport()} applies that filter, and it is the only accessor: there is no way to get the unfiltered
 * list, so the gate cannot be forgotten at a call site.
 *
 * <p>
 * <strong>Deduplication happens after this class, never inside it.</strong> {@code AnthropicLlmClient}'s
 * once-per-signature register only ever sees surviving findings, so a dropped record cannot consume its signature and
 * silence that message for the life of the process. That failure would be invisible to any test that asserts one
 * warning at a time, which is why it is written down rather than left to the shape of the code.
 */
final class AnthropicThinkingResolution {

    private final ThinkingConfigParam parameter;
    private final OutputConfig.Effort outputConfigEffort;
    private final List<Finding> findings;

    private AnthropicThinkingResolution(Builder builder) {
        this.parameter = builder.parameter;
        this.outputConfigEffort = builder.outputConfigEffort;
        this.findings = List.copyOf(builder.findings);
    }

    static Builder builder() {
        return new Builder();
    }

    /** @return the {@code thinking} parameter this request carries, or empty for none */
    Optional<ThinkingConfigParam> parameter() {
        return Optional.ofNullable(parameter);
    }

    /**
     * @return the {@code output_config.effort} that accompanies the parameter, non-empty only for the adaptive shape
     *         and only when somebody stated a rung
     */
    Optional<OutputConfig.Effort> outputConfigEffort() {
        return Optional.ofNullable(outputConfigEffort);
    }

    /**
     * The findings whose subject survived the resolution, in the order they were recorded.
     *
     * @return the findings to hand to the client's divergence register
     */
    List<Finding> findingsToReport() {
        final Scope survivor = parameter != null ? Scope.REQUIRES_THINKING : Scope.EXPLAINS_ABSENCE;
        return findings.stream().filter(finding -> finding.scope() == survivor).toList();
    }

    /** Whether a finding's subject is the request's thinking parameter or the absence of one. */
    enum Scope {

        /** Says the request differs from the configuration. Meaningless once the request carries no thinking. */
        REQUIRES_THINKING,

        /** Says the configuration reaches nothing. Meaningless once the request does carry thinking. */
        EXPLAINS_ABSENCE
    }

    /**
     * One thing the operator should be told, held until the resolution knows whether it is still true.
     *
     * <p>
     * A static factory rather than a builder, unlike its owner: a finding is created in one shot from a call site's
     * three values, and a builder over a varargs array would be ceremony guarding nothing.
     */
    static final class Finding {

        private final String signature;
        private final String message;
        private final Object[] args;
        private final Scope scope;

        private Finding(Scope scope, String signature, String message, Object... args) {
            this.scope = Objects.requireNonNull(scope, "Scope cannot be null");
            this.signature = Objects.requireNonNull(signature, "Signature cannot be null");
            this.message = Objects.requireNonNull(message, "Message cannot be null");
            this.args = args == null ? new Object[0] : args.clone();
        }

        static Finding requiresThinking(String signature, String message, Object... args) {
            return new Finding(Scope.REQUIRES_THINKING, signature, message, args);
        }

        static Finding explainsAbsence(String signature, String message, Object... args) {
            return new Finding(Scope.EXPLAINS_ABSENCE, signature, message, args);
        }

        /** @return the key that decides whether this has already been said */
        String signature() {
            return signature;
        }

        /** @return the SLF4J-formatted message */
        String message() {
            return message;
        }

        /** @return the values for the message placeholders */
        Object[] args() {
            return args.clone();
        }

        Scope scope() {
            return scope;
        }

        @Override
        public String toString() {
            return "Finding{" + scope + " " + signature + " " + Arrays.toString(args) + '}';
        }
    }

    static final class Builder {

        private final List<Finding> findings = new ArrayList<>();
        private ThinkingConfigParam parameter;
        private OutputConfig.Effort outputConfigEffort;

        private Builder() {
        }

        Builder parameter(ThinkingConfigParam parameter) {
            this.parameter = parameter;
            return this;
        }

        Builder outputConfigEffort(OutputConfig.Effort outputConfigEffort) {
            this.outputConfigEffort = outputConfigEffort;
            return this;
        }

        Builder record(Finding finding) {
            findings.add(Objects.requireNonNull(finding, "Finding cannot be null"));
            return this;
        }

        AnthropicThinkingResolution build() {
            return new AnthropicThinkingResolution(this);
        }
    }
}
