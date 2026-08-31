package at.aimon.core.memory.dialectic;

import java.util.List;
import java.util.Objects;

import at.aimon.core.llm.TokenUsage;
import at.aimon.core.memory.Observation;

/**
 * Answer returned by a {@link DialecticEngine}.
 *
 * <p>
 * Carries the natural-language {@code answer}, the list of observations the
 * engine considered while answering (sorted from most to least relevant), and
 * the cumulative {@link TokenUsage} consumed by the underlying LLM call(s).
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class DialecticResponse {

    private final String answer;
    private final List<Observation> observationsConsidered;
    private final TokenUsage tokenUsage;

    private DialecticResponse(Builder builder) {
        this.answer = Objects.requireNonNull(builder.answer, "answer cannot be null");
        this.observationsConsidered = List.copyOf(
                Objects.requireNonNull(builder.observationsConsidered, "observationsConsidered cannot be null"));
        this.tokenUsage = Objects.requireNonNull(builder.tokenUsage, "tokenUsage cannot be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Empty response with the given answer text. Useful for fallback paths. */
    public static DialecticResponse text(String answer) {
        return builder().answer(answer).observationsConsidered(List.of()).tokenUsage(TokenUsage.empty()).build();
    }

    public String getAnswer() {
        return answer;
    }

    public List<Observation> getObservationsConsidered() {
        return observationsConsidered;
    }

    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    @Override
    public String toString() {
        return "DialecticResponse{answer.len=" + answer.length() + ", observations=" + observationsConsidered.size()
                + ", tokens=" + tokenUsage.getTotalTokens() + "}";
    }

    /** Builder for {@link DialecticResponse}. */
    public static final class Builder {
        private String answer;
        private List<Observation> observationsConsidered = List.of();
        private TokenUsage tokenUsage = TokenUsage.empty();

        private Builder() {
        }

        public Builder answer(String answer) {
            this.answer = Objects.requireNonNull(answer, "answer cannot be null");
            return this;
        }

        public Builder observationsConsidered(List<Observation> observationsConsidered) {
            this.observationsConsidered = Objects.requireNonNull(observationsConsidered,
                    "observationsConsidered cannot be null");
            return this;
        }

        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = Objects.requireNonNull(tokenUsage, "tokenUsage cannot be null");
            return this;
        }

        public DialecticResponse build() {
            return new DialecticResponse(this);
        }
    }
}
