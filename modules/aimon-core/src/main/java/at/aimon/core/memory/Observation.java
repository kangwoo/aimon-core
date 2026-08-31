package at.aimon.core.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single piece of knowledge derived about a {@link PeerView subject} from one or
 * more source messages.
 *
 * <p>
 * Observations are the atomic unit of the memory layer: derivers create them,
 * the dreamer consolidates them, and dialectic queries surface them. They carry
 * a {@code confidence} score in {@code [0, 1]} computed by the deriver (not
 * self-reported by the LLM — see design doc §4.3).
 *
 * <p>
 * Embedding vectors are <em>not</em> stored on the observation itself. Vector
 * search is delegated to the knowledge module; this class carries metadata,
 * relations, and confidence only.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class Observation {

    private final ObservationId id;
    private final PeerView subject;
    private final PeerView observer;
    private final String content;
    private final ObservationType type;
    private final List<String> sourceMessageIds;
    private final Instant createdAt;
    private final double confidence;
    private final Map<String, String> metadata;

    private Observation(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Observation id cannot be null");
        this.subject = Objects.requireNonNull(builder.subject, "Observation subject cannot be null");
        this.observer = Objects.requireNonNull(builder.observer, "Observation observer cannot be null");
        this.content = Objects.requireNonNull(builder.content, "Observation content cannot be null");
        if (this.content.isBlank()) {
            throw new IllegalArgumentException("Observation content cannot be blank");
        }
        this.type = Objects.requireNonNull(builder.type, "Observation type cannot be null");
        this.sourceMessageIds = List
                .copyOf(Objects.requireNonNull(builder.sourceMessageIds, "sourceMessageIds cannot be null"));
        this.createdAt = Objects.requireNonNull(builder.createdAt, "createdAt cannot be null");
        if (Double.isNaN(builder.confidence) || builder.confidence < 0.0d || builder.confidence > 1.0d) {
            throw new IllegalArgumentException("confidence must be within [0, 1], got " + builder.confidence);
        }
        this.confidence = builder.confidence;
        this.metadata = Map.copyOf(Objects.requireNonNull(builder.metadata, "metadata cannot be null"));

        if (!subject.getWorkspace().getId().equals(id.getWorkspaceId())) {
            throw new IllegalArgumentException("Observation id workspace (" + id.getWorkspaceId()
                    + ") must match subject workspace (" + subject.getWorkspace().getId() + ")");
        }
        if (!observer.getWorkspace().getId().equals(id.getWorkspaceId())) {
            throw new IllegalArgumentException("Observation id workspace (" + id.getWorkspaceId()
                    + ") must match observer workspace (" + observer.getWorkspace().getId() + ")");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public ObservationId getId() {
        return id;
    }

    public PeerView getSubject() {
        return subject;
    }

    public PeerView getObserver() {
        return observer;
    }

    public String getContent() {
        return content;
    }

    public ObservationType getType() {
        return type;
    }

    public List<String> getSourceMessageIds() {
        return sourceMessageIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public double getConfidence() {
        return confidence;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    /** Returns a copy of this observation with a new confidence value. */
    public Observation withConfidence(double newConfidence) {
        return Observation.builder().id(id).subject(subject).observer(observer).content(content).type(type)
                .sourceMessageIds(sourceMessageIds).createdAt(createdAt).confidence(newConfidence).metadata(metadata)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Observation that = (Observation) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Observation{id=" + id + ", subject=" + subject.key() + ", type=" + type + ", confidence=" + confidence
                + "}";
    }

    /** Builder for {@link Observation}. */
    public static final class Builder {
        private ObservationId id;
        private PeerView subject;
        private PeerView observer;
        private String content;
        private ObservationType type;
        private List<String> sourceMessageIds = List.of();
        private Instant createdAt = Instant.now();
        private double confidence = 0.5d;
        private Map<String, String> metadata = Map.of();

        private Builder() {
        }

        public Builder id(ObservationId id) {
            this.id = Objects.requireNonNull(id, "id cannot be null");
            return this;
        }

        public Builder subject(PeerView subject) {
            this.subject = Objects.requireNonNull(subject, "subject cannot be null");
            return this;
        }

        public Builder observer(PeerView observer) {
            this.observer = Objects.requireNonNull(observer, "observer cannot be null");
            return this;
        }

        public Builder content(String content) {
            this.content = Objects.requireNonNull(content, "content cannot be null");
            return this;
        }

        public Builder type(ObservationType type) {
            this.type = Objects.requireNonNull(type, "type cannot be null");
            return this;
        }

        public Builder sourceMessageIds(List<String> sourceMessageIds) {
            this.sourceMessageIds = Objects.requireNonNull(sourceMessageIds, "sourceMessageIds cannot be null");
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
            return this;
        }

        public Observation build() {
            return new Observation(this);
        }
    }
}
