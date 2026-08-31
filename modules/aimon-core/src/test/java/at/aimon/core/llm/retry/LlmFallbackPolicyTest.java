package at.aimon.core.llm.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.exception.LlmAuthException;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import at.aimon.core.llm.exception.LlmOverloadedException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.exception.LlmRateLimitedException;

@DisplayName("LlmFallbackPolicy")
class LlmFallbackPolicyTest {

    private final LlmModel primary = LlmModel.builder().name("primary").build();
    private final LlmModel secondary = LlmModel.builder().name("secondary").build();
    private final LlmModel tertiary = LlmModel.builder().name("tertiary").build();

    @Nested
    @DisplayName("none()")
    class NoneHelper {

        @Test
        @DisplayName("exposes an empty chain and no activating exceptions")
        void noneIsEmpty() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.none();
            assertThat(policy.getFallbackChain()).isEmpty();
            assertThat(policy.getActivatingExceptions()).isEmpty();
        }

        @Test
        @DisplayName("never advances regardless of exception")
        void noneNeverAdvances() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.none();
            LlmModel any = LlmModel.builder().name("x").build();
            assertThat(policy.nextModel(any, new LlmOverloadedException("503"))).isEmpty();
            assertThat(policy.nextModel(any, new LlmRateLimitedException("429"))).isEmpty();
        }

        @Test
        @DisplayName("returns the same singleton instance")
        void noneIsSingleton() {
            assertThat(LlmFallbackPolicy.none()).isSameAs(LlmFallbackPolicy.none());
        }
    }

    @Nested
    @DisplayName("nextModel(...)")
    class NextModel {

        private LlmFallbackPolicy policyWithChain() {
            return LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary, tertiary))
                    .activatingExceptions(Set.of(LlmOverloadedException.class, LlmPromptTooLongException.class))
                    .build();
        }

        @Test
        @DisplayName("advances to the immediate successor when the exception activates")
        void advancesOnActivatingException() {
            LlmFallbackPolicy policy = policyWithChain();
            assertThat(policy.nextModel(primary, new LlmOverloadedException("503"))).contains(secondary);
            assertThat(policy.nextModel(secondary, new LlmOverloadedException("503"))).contains(tertiary);
        }

        @Test
        @DisplayName("returns empty when the exception does not activate")
        void doesNotAdvanceOnNonActivatingException() {
            LlmFallbackPolicy policy = policyWithChain();
            assertThat(policy.nextModel(primary, new LlmRateLimitedException("429"))).isEmpty();
            assertThat(policy.nextModel(primary, new LlmAuthException("401"))).isEmpty();
            assertThat(policy.nextModel(primary, new LlmInvalidRequestException("400"))).isEmpty();
        }

        @Test
        @DisplayName("returns empty when current is the last entry of the chain")
        void endOfChainReturnsEmpty() {
            LlmFallbackPolicy policy = policyWithChain();
            assertThat(policy.nextModel(tertiary, new LlmOverloadedException("503"))).isEmpty();
        }

        @Test
        @DisplayName("returns empty when current is not in the chain")
        void unknownCurrentReturnsEmpty() {
            LlmFallbackPolicy policy = policyWithChain();
            LlmModel stranger = LlmModel.builder().name("stranger").build();
            assertThat(policy.nextModel(stranger, new LlmOverloadedException("503"))).isEmpty();
        }

        @Test
        @DisplayName("returns empty when the chain is empty")
        void emptyChainReturnsEmpty() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder()
                    .activatingExceptions(Set.of(LlmOverloadedException.class)).build();
            assertThat(policy.nextModel(primary, new LlmOverloadedException("503"))).isEmpty();
        }

        @Test
        @DisplayName("matches subclasses of a registered activating supertype")
        void activationUsesSubclassMatching() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary))
                    .activatingExceptions(Set.of(LlmClientException.class)).build();

            // Every LlmClientException subtype should activate when the supertype is registered.
            assertThat(policy.nextModel(primary, new LlmOverloadedException("503"))).contains(secondary);
            assertThat(policy.nextModel(primary, new LlmRateLimitedException("429"))).contains(secondary);
            assertThat(policy.nextModel(primary, new LlmAuthException("401"))).contains(secondary);
        }

        @Test
        @DisplayName("never activates on a cancelled call, even when a matching supertype is registered")
        void cancellationNeverActivates() {
            // Adversarial policy: register the whole LlmClientException hierarchy as activating.
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary))
                    .activatingExceptions(Set.of(LlmClientException.class)).build();

            LlmCallCancelledException cancelled = new LlmCallCancelledException("aborted");
            // A generic client exception activates fallback...
            assertThat(policy.isActivating(new LlmClientException("generic"))).isTrue();
            // ...but a cancellation is terminal: it neither activates nor advances the chain.
            assertThat(policy.isActivating(cancelled)).isFalse();
            assertThat(policy.nextModel(primary, cancelled)).isEmpty();
        }

        @Test
        @DisplayName("uses value equality on LlmModel to locate current in the chain")
        void usesValueEqualityForLookup() {
            LlmFallbackPolicy policy = policyWithChain();
            // A distinct-but-equal copy of `primary` should locate the same position.
            LlmModel primaryClone = LlmModel.builder().name("primary").build();
            assertThat(primaryClone).isEqualTo(primary);
            assertThat(policy.nextModel(primaryClone, new LlmOverloadedException("503"))).contains(secondary);
        }

        @Test
        @DisplayName("rejects null current")
        void rejectsNullCurrent() {
            LlmFallbackPolicy policy = policyWithChain();
            assertThatThrownBy(() -> policy.nextModel(null, new LlmOverloadedException("503")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null exception")
        void rejectsNullException() {
            LlmFallbackPolicy policy = policyWithChain();
            assertThatThrownBy(() -> policy.nextModel(primary, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("accessors")
    class Accessors {

        @Test
        @DisplayName("returns an unmodifiable chain")
        void chainIsUnmodifiable() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder().fallbackChain(List.of(primary)).build();
            List<LlmModel> chain = policy.getFallbackChain();
            assertThatThrownBy(() -> chain.add(secondary)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("returns an unmodifiable activating-exceptions set")
        void activatingExceptionsIsUnmodifiable() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder()
                    .activatingExceptions(Set.of(LlmOverloadedException.class)).build();
            Set<Class<? extends LlmClientException>> set = policy.getActivatingExceptions();
            assertThatThrownBy(() -> set.add(LlmRateLimitedException.class))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("builder copies the chain defensively")
        void builderCopiesChain() {
            List<LlmModel> source = new ArrayList<>(Arrays.asList(primary, secondary));
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder().fallbackChain(source).build();
            source.clear();
            assertThat(policy.getFallbackChain()).containsExactly(primary, secondary);
        }
    }

    @Nested
    @DisplayName("builder validation")
    class BuilderValidation {

        @Test
        @DisplayName("rejects null chain")
        void rejectsNullChain() {
            assertThatThrownBy(() -> LlmFallbackPolicy.builder().fallbackChain(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null activating set")
        void rejectsNullActivating() {
            assertThatThrownBy(() -> LlmFallbackPolicy.builder().activatingExceptions(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null entry inside fallback chain")
        void rejectsNullInChain() {
            List<LlmModel> withNull = Arrays.asList(primary, null);
            assertThatThrownBy(() -> LlmFallbackPolicy.builder().fallbackChain(withNull).build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null via addFallback")
        void rejectsNullAddFallback() {
            assertThatThrownBy(() -> LlmFallbackPolicy.builder().addFallback(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null via addActivatingException")
        void rejectsNullAddActivating() {
            assertThatThrownBy(() -> LlmFallbackPolicy.builder().addActivatingException(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("addFallback accumulates entries in order")
        void addFallbackAccumulatesInOrder() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder().addFallback(primary).addFallback(secondary)
                    .addFallback(tertiary).build();
            assertThat(policy.getFallbackChain()).containsExactly(primary, secondary, tertiary);
        }

        @Test
        @DisplayName("rejects an immediately-repeated model (would allow an unbounded fallback loop)")
        void rejectsDuplicateAdjacent() {
            assertThatThrownBy(() -> LlmFallbackPolicy.builder().fallbackChain(List.of(primary, primary)).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        }

        @Test
        @DisplayName("rejects a cyclic chain that revisits an earlier model")
        void rejectsDuplicateCyclic() {
            assertThatThrownBy(
                    () -> LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary, primary)).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        }
    }

    @Nested
    @DisplayName("consecutiveFailureThreshold")
    class ConsecutiveFailureThreshold {

        @Test
        @DisplayName("defaults to 1 on both a plain policy and none()")
        void defaultsToOne() {
            assertThat(LlmFallbackPolicy.builder().build().getConsecutiveFailureThreshold()).isEqualTo(1);
            assertThat(LlmFallbackPolicy.none().getConsecutiveFailureThreshold()).isEqualTo(1);
        }

        @Test
        @DisplayName("retains the configured value")
        void retainsConfiguredValue() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder().consecutiveFailureThreshold(4).build();
            assertThat(policy.getConsecutiveFailureThreshold()).isEqualTo(4);
        }

        @Test
        @DisplayName("rejects a threshold below 1")
        void rejectsBelowOne() {
            assertThatThrownBy(() -> LlmFallbackPolicy.builder().consecutiveFailureThreshold(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LlmFallbackPolicy.builder().consecutiveFailureThreshold(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("isActivating(...)")
    class IsActivating {

        @Test
        @DisplayName("returns true for a registered activating type")
        void trueForRegistered() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder()
                    .activatingExceptions(Set.of(LlmOverloadedException.class)).build();
            assertThat(policy.isActivating(new LlmOverloadedException("503"))).isTrue();
        }

        @Test
        @DisplayName("returns false for a non-registered type")
        void falseForUnregistered() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder()
                    .activatingExceptions(Set.of(LlmOverloadedException.class)).build();
            assertThat(policy.isActivating(new LlmRateLimitedException("429"))).isFalse();
        }

        @Test
        @DisplayName("matches subclasses of a registered supertype independent of the chain")
        void matchesSubclassesIndependentOfChain() {
            // Empty chain: isActivating still reflects the activating set (used by the gateway to count failures).
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder()
                    .activatingExceptions(Set.of(LlmClientException.class)).build();
            assertThat(policy.isActivating(new LlmAuthException("401"))).isTrue();
            assertThat(policy.isActivating(new LlmInvalidRequestException("400"))).isTrue();
        }

        @Test
        @DisplayName("none() never activates")
        void noneNeverActivates() {
            assertThat(LlmFallbackPolicy.none().isActivating(new LlmOverloadedException("503"))).isFalse();
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertThatThrownBy(() -> LlmFallbackPolicy.none().isActivating(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equal configurations are equal and share hashCode")
        void equality() {
            LlmFallbackPolicy a = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary))
                    .activatingExceptions(Set.of(LlmOverloadedException.class)).consecutiveFailureThreshold(3).build();
            LlmFallbackPolicy b = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary))
                    .activatingExceptions(Set.of(LlmOverloadedException.class)).consecutiveFailureThreshold(3).build();
            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("toString includes the consecutive-failure threshold")
        void toStringIncludesThreshold() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary))
                    .activatingExceptions(Set.of(LlmOverloadedException.class)).consecutiveFailureThreshold(3).build();
            assertThat(policy.toString()).contains("consecutiveFailureThreshold=3");
        }

        @Test
        @DisplayName("differing thresholds produce unequal policies")
        void inequalityOnThreshold() {
            LlmFallbackPolicy a = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary))
                    .consecutiveFailureThreshold(1).build();
            LlmFallbackPolicy b = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary))
                    .consecutiveFailureThreshold(2).build();
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("different chain ordering produces unequal policies")
        void inequalityOnChainOrder() {
            LlmFallbackPolicy a = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary)).build();
            LlmFallbackPolicy b = LlmFallbackPolicy.builder().fallbackChain(List.of(secondary, primary)).build();
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("nextModel returns Optional (never null)")
        void nextModelReturnIsOptional() {
            LlmFallbackPolicy policy = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary))
                    .activatingExceptions(Set.of(LlmOverloadedException.class)).build();
            Optional<LlmModel> empty = policy.nextModel(primary, new LlmAuthException("401"));
            Optional<LlmModel> advancing = policy.nextModel(primary, new LlmOverloadedException("503"));
            assertThat(empty).isNotNull().isEmpty();
            assertThat(advancing).isNotNull().contains(secondary);
        }
    }
}
