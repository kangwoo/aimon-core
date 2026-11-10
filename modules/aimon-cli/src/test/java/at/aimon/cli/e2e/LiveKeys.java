package at.aimon.cli.e2e;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves the provider API keys the real-model {@code ExecuteCode} E2E runs with, from the environment only.
 *
 * <p>
 * OpenAI: {@code OPENAI_KEY} wins when non-blank; otherwise {@code OPENAI_KEY_FILE} names a file whose contents,
 * trimmed, are the key. A leading {@code ~/} is expanded with {@code user.home}, in case the shell did not expand it.
 * The file is read in place — never copied, never written. A set {@code OPENAI_KEY_FILE} that is missing, unreadable or
 * empty is a <b>failure</b>, not a skip, so a typo cannot pass as a green run in which nothing ran. Anthropic: the
 * same, with {@code ANTHROPIC_KEY} (falling back to {@code ANTHROPIC_API_KEY}) and {@code ANTHROPIC_KEY_FILE}.
 *
 * <p>
 * The key is returned to go straight into a client builder. Nothing here prints it, and no message names anything but
 * the variable and the path.
 *
 * <p>
 * Called only from test methods of classes gated by {@code @EnabledIfSystemProperty(named = "aimon.llmTest")}, so the
 * plain {@code test} task never reaches it — a stale key-file variable cannot break {@code checkAll}.
 */
final class LiveKeys {

    static final String OPENAI_KEY = "OPENAI_KEY";
    static final String OPENAI_KEY_FILE = "OPENAI_KEY_FILE";
    static final String ANTHROPIC_KEY = "ANTHROPIC_KEY";
    static final String ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY";
    static final String ANTHROPIC_KEY_FILE = "ANTHROPIC_KEY_FILE";

    private final Function<String, String> environment;
    private final String userHome;

    LiveKeys(Function<String, String> environment, String userHome) {
        this.environment = Objects.requireNonNull(environment, "environment cannot be null");
        this.userHome = Objects.requireNonNull(userHome, "userHome cannot be null");
    }

    /** The resolver over the real process environment. */
    static LiveKeys fromEnvironment() {
        return new LiveKeys(System::getenv, System.getProperty("user.home"));
    }

    /**
     * Returns the OpenAI key, skipping the calling test when neither variable is set.
     *
     * @return the key (never blank)
     * @throws IllegalStateException
     *             if {@code OPENAI_KEY_FILE} is set but cannot be read or is empty
     */
    String openAi() {
        final Optional<String> key = openAiKey();
        assumeTrue(key.isPresent(), "neither " + OPENAI_KEY + " nor " + OPENAI_KEY_FILE + " is set");
        return key.get();
    }

    /**
     * Returns the Anthropic key, skipping the calling test when neither variable is set.
     *
     * @return the key (never blank)
     * @throws IllegalStateException
     *             if {@code ANTHROPIC_KEY_FILE} is set but cannot be read or is empty
     */
    String anthropic() {
        final Optional<String> key = anthropicKey();
        assumeTrue(key.isPresent(),
                "none of " + ANTHROPIC_KEY + ", " + ANTHROPIC_API_KEY + " or " + ANTHROPIC_KEY_FILE + " is set");
        return key.get();
    }

    /** The OpenAI key when one is configured; empty when neither variable is set. */
    Optional<String> openAiKey() {
        final String direct = environment.apply(OPENAI_KEY);
        if (direct != null && !direct.isBlank()) {
            return Optional.of(direct.strip());
        }
        return keyFile(OPENAI_KEY_FILE);
    }

    /** The Anthropic key when one is configured; empty when no variable is set. The direct variables win. */
    Optional<String> anthropicKey() {
        for (String name : new String[]{ANTHROPIC_KEY, ANTHROPIC_API_KEY}) {
            final String value = environment.apply(name);
            if (value != null && !value.isBlank()) {
                return Optional.of(value.strip());
            }
        }
        return keyFile(ANTHROPIC_KEY_FILE);
    }

    /**
     * Reads the key from the file {@code variable} names, in place; empty when the variable is unset or blank. A set
     * variable whose file is missing, unreadable or empty fails, naming the variable and the path only.
     */
    private Optional<String> keyFile(String variable) {
        final String file = environment.apply(variable);
        if (file == null || file.isBlank()) {
            return Optional.empty();
        }
        final Path path = expand(file.strip());
        final String contents;
        try {
            contents = Files.readString(path, StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(variable + " is set to " + path + ", which cannot be read ("
                    + e.getClass().getSimpleName() + "); fix the path or unset the variable");
        }
        if (contents.isEmpty()) {
            throw new IllegalStateException(variable + " is set to " + path + ", which is empty");
        }
        return Optional.of(contents);
    }

    private Path expand(String value) {
        return value.startsWith("~/") ? Path.of(userHome, value.substring(2)) : Path.of(value);
    }
}
