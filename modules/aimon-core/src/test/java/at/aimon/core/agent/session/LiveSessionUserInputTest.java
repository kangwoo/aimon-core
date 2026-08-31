package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.input.ImageInput;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.command.execution.ExecutionMetadata;

/**
 * Pins what the {@link UserInput} overloads do on a session that does not implement them — which is every session
 * written before they existed, and every one written after that only ever sees text.
 *
 * <p>
 * The three {@code String} methods stay abstract, so such a session is still a compile error away from being
 * incomplete rather than a runtime surprise. The {@code UserInput} overloads default on top of them: text is
 * unwrapped and handed down, so it reaches the implementation that exists, and anything else is refused rather than
 * flattened into its {@code asText()} placeholder. The first half is what keeps
 * {@link LiveSession#retryLastTurn(SubmitOptions)} working on any session that can rewind at all; the second is what
 * stops a retry of an image turn from quietly becoming a retry of a sentence describing one.
 */
@DisplayName("LiveSession: the UserInput overloads over a text-only session")
class LiveSessionUserInputTest {

    private static final ImageInput SCREENSHOT = ImageInput.of(new byte[]{1, 2, 3}, "image/png");

    @Test
    @DisplayName("text wrapped as a UserInput reaches the String method the session implements")
    void textRoutesDownToTheStringMethod() {
        final TextOnlySession session = new TextOnlySession();

        session.submit(TextInput.of("hi"), SubmitOptions.empty());
        session.submitAsync(TextInput.of("streamed"), SubmitOptions.empty(), event -> {
        });
        session.submitAsync(TurnId.generate(), TextInput.of("addressed"), SubmitOptions.empty(), event -> {
        });
        session.offerAsync(TextInput.of("offered"), SubmitOptions.empty(), event -> {
        });

        assertThat(session.received).containsExactly("hi", "streamed", "addressed", "offered");
    }

    /**
     * The reason the previous test matters. {@code retryLastTurn} rewinds to a {@link UserInput} and submits it, so a
     * session that can rewind but only implements the {@code String} overloads would refuse to retry its own
     * perfectly ordinary text turns if the default did not route down.
     */
    @Test
    @DisplayName("a text-only session can still retry a text turn it rewound")
    void aTextOnlySessionCanRetryATextTurn() {
        final TextOnlySession session = new TextOnlySession();
        session.rewound = RewoundTurn.of(TextInput.of("summarise the incident"), SubmitOptions.empty());

        assertThat(session.retryLastTurn()).isPresent();
        assertThat(session.received).containsExactly("summarise the incident");
    }

    /**
     * And the other half. {@code asText()} on an image is {@code "[Image: image/png, 3 bytes]"}; submitting that
     * would run a turn asking about a sentence describing a picture instead of about the picture.
     */
    @Test
    @DisplayName("a non-text input is refused, not flattened")
    void aNonTextInputIsRefused() {
        final TextOnlySession session = new TextOnlySession();

        assertThatThrownBy(() -> session.submit(SCREENSHOT, SubmitOptions.empty()))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("text input only")
                .hasMessageContaining("IMAGE");
        assertThatThrownBy(() -> session.submitAsync(SCREENSHOT, SubmitOptions.empty(), event -> {
        })).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> session.offerAsync(SCREENSHOT, SubmitOptions.empty(), event -> {
        })).isInstanceOf(UnsupportedOperationException.class);

        assertThat(session.received).as("nothing may have been run in its place").isEmpty();
    }

    /** A session implementing exactly the three methods the interface still demands, and nothing else. */
    private static final class TextOnlySession implements LiveSession {
        private final List<String> received = new ArrayList<>();
        private RewoundTurn rewound;

        @Override
        public SessionId getSessionId() {
            return SessionId.of("text-only");
        }

        @Override
        public AgentExecutionResult submit(String input, SubmitOptions submitOptions) {
            received.add(input);
            return result();
        }

        @Override
        public CompletionStage<AgentExecutionResult> submitAsync(String input, SubmitOptions submitOptions,
                Consumer<AgentExecutionEvent> listener) {
            received.add(input);
            return CompletableFuture.completedFuture(result());
        }

        @Override
        public SubmitOutcome offerAsync(String input, SubmitOptions submitOptions,
                Consumer<AgentExecutionEvent> listener) {
            received.add(input);
            return SubmitOutcome.executed(CompletableFuture.completedFuture(result()));
        }

        @Override
        public Optional<RewoundTurn> rewindLastTurn() {
            final RewoundTurn point = rewound;
            rewound = null;
            return Optional.ofNullable(point);
        }

        @Override
        public void close() {
            // no-op
        }

        private AgentExecutionResult result() {
            return OrcaAgentExecutionResult.success("done", SessionSnapshot.of(getSessionId()),
                    ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH));
        }
    }
}
