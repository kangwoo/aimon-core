package at.aimon.core.agent.session;

import java.util.Objects;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.input.UserInput;

/**
 * What an interrupted turn was, handed back by {@link LiveSession#rewindLastTurn()} so a caller can run it again.
 *
 * <p>
 * Two values rather than one because a turn is not only what was asked but how it was submitted. The
 * {@link #getUserInput() input} is the request — text, an image, a document, or a combination — and the
 * {@link #getSubmitOptions() options} are the per-turn metadata it was submitted under: which
 * {@link at.aimon.core.base.Principal}, which system-prompt variables, whether user-context injection was forced.
 * Submitting the first without the second runs the same words as a different caller, against differently assembled
 * context, which is a turn like the original rather than the original.
 *
 * <p>
 * Both come from the {@link at.aimon.core.agent.session.transcript.SessionRewindPoint} recorded when the turn began,
 * so both survive the handle that ran it — an interrupt is exactly when a process is most likely to go away.
 *
 * <p>
 * Immutable and therefore thread-safe.
 */
public final class RewoundTurn {

    private final UserInput userInput;
    private final SubmitOptions submitOptions;

    private RewoundTurn(UserInput userInput, SubmitOptions submitOptions) {
        this.userInput = userInput;
        this.submitOptions = submitOptions;
    }

    /**
     * Creates a rewound turn.
     *
     * @param userInput
     *            the input the turn was submitted with (must not be null)
     * @param submitOptions
     *            the per-turn options it was submitted under (must not be null; use {@link SubmitOptions#empty()}
     *            when there were none)
     * @return a new rewound turn (never null)
     * @throws NullPointerException
     *             if either argument is null
     */
    public static RewoundTurn of(UserInput userInput, SubmitOptions submitOptions) {
        return new RewoundTurn(Objects.requireNonNull(userInput, "userInput cannot be null"),
                Objects.requireNonNull(submitOptions, "submitOptions cannot be null"));
    }

    /**
     * Returns the input that started the turn.
     *
     * @return the user input (never null)
     */
    public UserInput getUserInput() {
        return userInput;
    }

    /**
     * Returns the per-turn options the turn was submitted under.
     *
     * @return the submit options, {@link SubmitOptions#empty()} when the turn carried none (never null)
     */
    public SubmitOptions getSubmitOptions() {
        return submitOptions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RewoundTurn other)) {
            return false;
        }
        return Objects.equals(userInput, other.userInput) && Objects.equals(submitOptions, other.submitOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userInput, submitOptions);
    }

    @Override
    public String toString() {
        return "RewoundTurn{inputType=" + userInput.getType() + "}";
    }
}
