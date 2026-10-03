package at.aimon.core.tools.bash;

import java.util.Objects;

/**
 * What {@link BackgroundBashManager#kill} did about a task id, together with the lookup it acted on.
 */
public final class BackgroundBashKill {

    /** What happened. */
    public enum Outcome {
        /** The command's cancellation signal was tripped; the task settles as {@link BashTaskStatus#KILLED}. */
        REQUESTED,
        /** The command had already ended. Nothing was done. */
        NOT_RUNNING,
        /** The command runs in a shell that cannot stop it. It runs until it ends or its timeout does. */
        UNSUPPORTED,
        /** The command is not this node's to stop — see {@link BackgroundBashLookup.Kind#ELSEWHERE}. */
        ELSEWHERE,
        /** No task the caller may see has this id. */
        NOT_FOUND
    }

    private final Outcome outcome;
    private final BackgroundBashLookup lookup;

    BackgroundBashKill(Outcome outcome, BackgroundBashLookup lookup) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /** @return what happened */
    public Outcome outcome() {
        return outcome;
    }

    /** @return the lookup the outcome was decided on — the task for a local one, the record for one elsewhere */
    public BackgroundBashLookup lookup() {
        return lookup;
    }
}
