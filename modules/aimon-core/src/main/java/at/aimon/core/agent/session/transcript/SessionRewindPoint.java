package at.aimon.core.agent.session.transcript;

import java.util.Objects;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.input.UserInput;

/**
 * Where a turn began, kept so that a turn which ended {@code INTERRUPTED} can be taken back out of the history and run
 * again.
 *
 * <p>
 * An interrupted turn leaves a partial trail behind: the user message, whatever synthetic context blocks were injected
 * ahead of it, the assistant messages produced before the stop, and the tool results — including the ones filled in
 * with "skipped, the turn was cancelled". That trail is deliberately kept rather than discarded, because the user saw
 * it happen. But it cannot be the starting point for a second attempt: re-submitting the same request on top of it
 * would ask the model to redo work in a history that says it already half-did it.
 *
 * <p>
 * So the trail is removed only when someone asks to retry, and this is the record of how far back to go.
 * {@link #getMessageCount()} is the transcript's size <em>before</em> the turn added anything, and
 * {@link #getUserInput()} is what started it, kept here rather than looked up by index because the messages in between
 * are not all distinguishable by role — the injected context blocks carry the user role too.
 *
 * <p>
 * <b>The request, not its rendering.</b> What is kept is the {@link UserInput} the turn was submitted with, not the
 * {@code Message} the executor made of it. The two differ for anything that is not plain text: an image, a document
 * or a multimodal combination flattens into content blocks on its way to the model, and a text representation is all
 * that survives being read back out of one. Keeping the input means a retry submits <em>the same request</em> — it
 * goes through the same conversion the first attempt did, so nothing has to be reconstructed and no input type is
 * unreplayable.
 *
 * <p>
 * <b>And the options it was submitted with.</b> A turn is not only what was asked but how it was submitted — under
 * which {@link at.aimon.core.base.Principal}, with which system-prompt variables, with user-context injection forced
 * on or off. Those arrive as a {@link SubmitOptions} and are folded into the request, so a retry that dropped them
 * would run the same words as a different caller, against differently assembled context. The point keeps them for the
 * same reason it keeps the input: a retry is meant to be the turn again, not a turn like it.
 *
 * <p>
 * <b>Lives with the transcript, not beside it.</b> The count is an index into a specific message list, so it is stored
 * inside {@link SessionTranscript} and travels with it through {@link SessionSnapshot} to the record. Held as a
 * side field of the record instead, it would survive writes that replaced the messages it counts — compaction being
 * the obvious one — and point into a history that no longer exists.
 *
 * <p>
 * At most one is held at a time, and it describes the most recent turn only: a turn that ends any other way clears it.
 * "Retry" therefore means the last turn, never an arbitrary one, which is the whole of what it is for.
 *
 * <p>
 * Immutable and therefore thread-safe.
 */
public final class SessionRewindPoint {

    private final int messageCount;
    private final UserInput userInput;
    private final SubmitOptions submitOptions;

    private SessionRewindPoint(int messageCount, UserInput userInput, SubmitOptions submitOptions) {
        this.messageCount = messageCount;
        this.userInput = userInput;
        this.submitOptions = submitOptions;
    }

    /**
     * Creates a rewind point for a turn submitted without per-turn options.
     *
     * @param messageCount
     *            the number of messages the transcript held before the turn began (must not be negative)
     * @param userInput
     *            the input the turn was submitted with (must not be null)
     * @return a new rewind point (never null)
     * @throws IllegalArgumentException
     *             if {@code messageCount} is negative
     * @throws NullPointerException
     *             if {@code userInput} is null
     */
    public static SessionRewindPoint of(int messageCount, UserInput userInput) {
        return of(messageCount, userInput, SubmitOptions.empty());
    }

    /**
     * Creates a rewind point.
     *
     * @param messageCount
     *            the number of messages the transcript held before the turn began (must not be negative)
     * @param userInput
     *            the input the turn was submitted with (must not be null)
     * @param submitOptions
     *            the per-turn options the turn was submitted with (must not be null; use
     *            {@link SubmitOptions#empty()} when there were none)
     * @return a new rewind point (never null)
     * @throws IllegalArgumentException
     *             if {@code messageCount} is negative
     * @throws NullPointerException
     *             if {@code userInput} or {@code submitOptions} is null
     */
    public static SessionRewindPoint of(int messageCount, UserInput userInput, SubmitOptions submitOptions) {
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount cannot be negative, got: " + messageCount);
        }
        return new SessionRewindPoint(messageCount, Objects.requireNonNull(userInput, "userInput cannot be null"),
                Objects.requireNonNull(submitOptions, "submitOptions cannot be null"));
    }

    /**
     * Returns the transcript size to truncate back to.
     *
     * @return the message count before the turn began (never negative)
     */
    public int getMessageCount() {
        return messageCount;
    }

    /**
     * Returns the input the turn was submitted with, for a retry to submit again.
     *
     * @return the user input (never null)
     */
    public UserInput getUserInput() {
        return userInput;
    }

    /**
     * Returns the per-turn options the turn was submitted with, for a retry to submit under again.
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
        if (!(o instanceof SessionRewindPoint other)) {
            return false;
        }
        return messageCount == other.messageCount && Objects.equals(userInput, other.userInput)
                && Objects.equals(submitOptions, other.submitOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageCount, userInput, submitOptions);
    }

    @Override
    public String toString() {
        return "SessionRewindPoint{messageCount=" + messageCount + ", inputType=" + userInput.getType() + "}";
    }
}
