package at.aimon.core.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A fact offered to an {@link ObservationRecorder}, before the backend has given it an identity.
 *
 * <p>
 * The difference from {@link Observation} is exactly that: a draft has no {@link ObservationId} and no creation time,
 * because those are the backend's to assign. A remote backend mints ids in its own namespace, and a draft that
 * arrived carrying one would be asking it to honour an id it does not own.
 *
 * <p>
 * As on the other tier queries, the workspace is {@code subject.getWorkspace()}.
 *
 * <p>
 * <b>{@link #getContent() content} must already have passed redaction.</b> That is a contract of this type, and it is
 * enforced structurally rather than left to callers: the assembly wraps every backend in a decorator that redacts on
 * the way through, so there is no path to a recorder that skips the gate.
 *
 * <p>
 * <b>{@link #getConfidence() confidence} may be dropped.</b> Backends that do not store it say so through
 * {@link ObservationRecorder#storesConfidence()}, and callers must read that before offering a number they expect back.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class ObservationDraft {

    /** Confidence used when the caller does not supply one. */
    public static final double DEFAULT_CONFIDENCE = 0.7d;

    private final PeerView subject;
    private final PeerView observer;
    private final String sessionId;
    private final String content;
    private final ObservationType type;
    private final double confidence;
    private final List<String> sourceMessageIds;
    private final Map<String, String> metadata;

    private ObservationDraft(Builder builder) {
        this.subject = Objects.requireNonNull(builder.subject, "subject cannot be null");
        this.observer = Objects.requireNonNull(builder.observer, "observer cannot be null");
        this.sessionId = builder.sessionId;
        this.content = Objects.requireNonNull(builder.content, "content cannot be null");
        if (this.content.isBlank()) {
            throw new IllegalArgumentException("content cannot be blank");
        }
        this.type = Objects.requireNonNull(builder.type, "type cannot be null");
        if (Double.isNaN(builder.confidence) || builder.confidence < 0.0d || builder.confidence > 1.0d) {
            throw new IllegalArgumentException("confidence must be within [0, 1], got " + builder.confidence);
        }
        this.confidence = builder.confidence;
        this.sourceMessageIds = List
                .copyOf(Objects.requireNonNull(builder.sourceMessageIds, "sourceMessageIds cannot be null"));
        this.metadata = Map.copyOf(Objects.requireNonNull(builder.metadata, "metadata cannot be null"));

        if (!observer.getWorkspace().equals(subject.getWorkspace())) {
            throw new IllegalArgumentException("subject and observer must belong to the same workspace");
        }
    }

    /**
     * Starts a draft.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the peer the fact is about. Its workspace is the draft's workspace.
     *
     * @return the subject, never null
     */
    public PeerView getSubject() {
        return subject;
    }

    /**
     * Returns the peer recording the fact.
     *
     * @return the observer, never null
     */
    public PeerView getObserver() {
        return observer;
    }

    /**
     * Returns the session the fact was recorded in.
     *
     * @return the session id, or empty for a session-less execution
     */
    public Optional<String> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    /**
     * Returns the factual sentence, already redacted.
     *
     * @return the content, never null or blank
     */
    public String getContent() {
        return content;
    }

    /**
     * Returns how the fact was arrived at.
     *
     * @return the type, never null
     */
    public ObservationType getType() {
        return type;
    }

    /**
     * Returns the confidence offered for the fact.
     *
     * @return the confidence in {@code [0, 1]}; dropped by backends whose
     *         {@link ObservationRecorder#storesConfidence()} is {@code false}
     */
    public double getConfidence() {
        return confidence;
    }

    /**
     * Returns the ids of the messages the fact came from.
     *
     * @return the ids, never null; empty for a fact recorded directly
     */
    public List<String> getSourceMessageIds() {
        return sourceMessageIds;
    }

    /**
     * Returns free-form metadata to carry alongside the fact.
     *
     * @return the metadata, never null
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Returns a copy of this draft with the content replaced — the shape the redaction decorator uses.
     *
     * @param newContent
     *            the replacement content (must not be null or blank)
     * @return a new draft
     */
    public ObservationDraft withContent(String newContent) {
        return builder().subject(subject).observer(observer).sessionId(sessionId).content(newContent).type(type)
                .confidence(confidence).sourceMessageIds(sourceMessageIds).metadata(metadata).build();
    }

    /**
     * Returns a copy of this draft with the metadata replaced.
     *
     * @param newMetadata
     *            the replacement metadata (must not be null)
     * @return a new draft
     */
    public ObservationDraft withMetadata(Map<String, String> newMetadata) {
        return builder().subject(subject).observer(observer).sessionId(sessionId).content(content).type(type)
                .confidence(confidence).sourceMessageIds(sourceMessageIds).metadata(newMetadata).build();
    }

    @Override
    public String toString() {
        return "ObservationDraft{subject=" + subject.key() + ", type=" + type + ", confidence=" + confidence + "}";
    }

    /** Builder for {@link ObservationDraft}. */
    public static final class Builder {

        private PeerView subject;
        private PeerView observer;
        private String sessionId;
        private String content;
        private ObservationType type = ObservationType.DEDUCTIVE;
        private double confidence = DEFAULT_CONFIDENCE;
        private List<String> sourceMessageIds = List.of();
        private Map<String, String> metadata = Map.of();

        private Builder() {
        }

        /**
         * Sets the peer the fact is about. Required; carries the workspace.
         *
         * @param subject
         *            the subject (must not be null)
         * @return this builder
         */
        public Builder subject(PeerView subject) {
            this.subject = Objects.requireNonNull(subject, "subject cannot be null");
            return this;
        }

        /**
         * Sets the peer recording the fact. Required.
         *
         * @param observer
         *            the observer (must not be null)
         * @return this builder
         */
        public Builder observer(PeerView observer) {
            this.observer = Objects.requireNonNull(observer, "observer cannot be null");
            return this;
        }

        /**
         * Sets the session the fact was recorded in.
         *
         * <p>
         * A blank string is folded to absent. "" is not a session, and the two spellings of "none" behaving
         * differently is how a caller ends up reading '' back out of an error message.
         *
         * @param sessionId
         *            the session id, or {@code null}
         * @return this builder
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = (sessionId == null || sessionId.isBlank()) ? null : sessionId;
            return this;
        }

        /**
         * Sets the factual sentence. Required, and must already be redacted.
         *
         * @param content
         *            the content (must not be null or blank)
         * @return this builder
         */
        public Builder content(String content) {
            this.content = Objects.requireNonNull(content, "content cannot be null");
            return this;
        }

        /**
         * Sets how the fact was arrived at. Defaults to {@link ObservationType#DEDUCTIVE}.
         *
         * @param type
         *            the type (must not be null)
         * @return this builder
         */
        public Builder type(ObservationType type) {
            this.type = Objects.requireNonNull(type, "type cannot be null");
            return this;
        }

        /**
         * Sets the confidence. Defaults to {@value #DEFAULT_CONFIDENCE}.
         *
         * @param confidence
         *            the confidence in {@code [0, 1]}
         * @return this builder
         */
        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        /**
         * Sets the ids of the messages the fact came from.
         *
         * @param sourceMessageIds
         *            the ids (must not be null)
         * @return this builder
         */
        public Builder sourceMessageIds(List<String> sourceMessageIds) {
            this.sourceMessageIds = Objects.requireNonNull(sourceMessageIds, "sourceMessageIds cannot be null");
            return this;
        }

        /**
         * Sets the metadata to carry alongside the fact.
         *
         * @param metadata
         *            the metadata (must not be null)
         * @return this builder
         */
        public Builder metadata(Map<String, String> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
            return this;
        }

        /**
         * Validates and builds the draft.
         *
         * @return the immutable draft
         * @throws IllegalArgumentException
         *             if the content is blank, the confidence is outside {@code [0, 1]}, or the subject and observer
         *             disagree about the workspace
         */
        public ObservationDraft build() {
            return new ObservationDraft(this);
        }
    }
}
