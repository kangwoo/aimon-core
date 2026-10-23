package at.aimon.core.llms.openai;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.responses.Response;

import at.aimon.core.llm.ServiceTier;

/**
 * Reads the model and service tier OpenAI reports having applied, and maps the tier to the provider-neutral vocabulary.
 *
 * <p>
 * The mapping is explicit: {@code default} → {@link ServiceTier#STANDARD}, {@code priority} → {@link ServiceTier#FAST},
 * {@code flex} and {@code batch} to themselves. {@code auto} is a request-side instruction rather than an applied tier,
 * so it reads as "not reported". A gateway in front of the API that already answers in the neutral vocabulary
 * ({@code standard}, {@code fast}) maps to itself. Any other value ({@code scale}, a tier added later) is preserved as
 * reported — never guessed into one of the four.
 *
 * <p>
 * Like {@link OpenAiResponseUsages}, it never throws: everything is read through the raw {@code JsonField} accessors,
 * and an absent or malformed field is simply not reported. Accounting degrades, identity throws.
 */
final class OpenAiServedBy {

    private OpenAiServedBy() {
    }

    static String model(Response response) {
        return quietly(() -> response._model().asKnown()
                .flatMap(model -> model.string().or(() -> model.chat().flatMap(chat -> chat._value().asString()))
                        .or(() -> model.only().flatMap(only -> only._value().asString())))
                .orElse(null));
    }

    static String model(ChatCompletion completion) {
        return quietly(() -> completion._model().asString().orElse(null));
    }

    static String model(ChatCompletionChunk chunk) {
        return quietly(() -> chunk._model().asString().orElse(null));
    }

    static ServiceTier serviceTier(Response response) {
        return quietly(() -> fromWire(response._serviceTier().asKnown().flatMap(tier -> tier._value().asString())));
    }

    static ServiceTier serviceTier(ChatCompletion completion) {
        return quietly(() -> fromWire(completion._serviceTier().asKnown().flatMap(tier -> tier._value().asString())));
    }

    static ServiceTier serviceTier(ChatCompletionChunk chunk) {
        return quietly(() -> fromWire(chunk._serviceTier().asKnown().flatMap(tier -> tier._value().asString())));
    }

    /**
     * Runs a read, answering null instead of throwing — a response object missing a field (or a test double that
     * stubs none) must not fail a call that otherwise succeeded.
     */
    private static <T> T quietly(Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Maps a reported tier to the neutral vocabulary.
     *
     * @return the tier, or null when none was reported or the value was {@code auto}
     */
    static ServiceTier fromWire(Optional<String> wire) {
        return wire.map(value -> value.trim().toLowerCase(Locale.ROOT)).flatMap(value -> switch (value) {
            case "default" -> Optional.of(ServiceTier.STANDARD);
            case "priority" -> Optional.of(ServiceTier.FAST);
            case "auto" -> Optional.<ServiceTier>empty();
            default -> ServiceTier.of(value);
        }).orElse(null);
    }
}
