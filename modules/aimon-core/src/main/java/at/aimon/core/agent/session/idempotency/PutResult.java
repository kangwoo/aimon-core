package at.aimon.core.agent.session.idempotency;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of {@link IdempotencyStore#putIfAbsent(String, IdempotencyEntry, java.time.Duration)}.
 *
 * <p>
 * Either {@link Kind#INSERTED} (the entry was new) or {@link Kind#EXISTING} (an entry was already present and is
 * carried in {@link #getCurrent()}). Built via factories per the project's "prefer class over record" convention.
 */
public final class PutResult {

    /** Distinguishes new-insert from already-present. */
    public enum Kind {
        INSERTED, EXISTING
    }

    private static final PutResult INSERTED = new PutResult(Kind.INSERTED, null);

    private final Kind kind;
    private final IdempotencyEntry current;

    private PutResult(Kind kind, IdempotencyEntry current) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.current = current;
    }

    public static PutResult inserted() {
        return INSERTED;
    }

    public static PutResult existing(IdempotencyEntry current) {
        return new PutResult(Kind.EXISTING, Objects.requireNonNull(current, "current must not be null"));
    }

    public Kind getKind() {
        return kind;
    }

    public Optional<IdempotencyEntry> getCurrent() {
        return Optional.ofNullable(current);
    }
}
