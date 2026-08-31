package at.aimon.cli.config;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CLI-side configuration for the Honcho-analogue dreamer (background consolidation) subsystem.
 *
 * <p>
 * When present and {@code enabled == true}, {@link at.aimon.cli.factory.AgentSetupFactory} builds a dedicated Quartz
 * {@link org.quartz.Scheduler} (RAMJobStore + SimpleThreadPool), wires
 * {@link at.aimon.core.memory.dreamer.DefaultDreamerEngine} backed by
 * {@link at.aimon.core.memory.dreamer.RandomWalkDreamer} + a {@link at.aimon.core.memory.dreamer.SurprisalScorer}
 * implementation, and registers a cron-driven {@link at.aimon.scheduling.quartz.dreamer.DreamerJob} for the workspace
 * declared in the parent {@link MemoryConfig}.
 *
 * <p>
 * The scorer implementation is selected by {@code scorer.type}:
 * <ul>
 * <li>{@code llm} (default) — {@link at.aimon.core.memory.dreamer.LlmJudgeSurprisalScorer}, reuses the global LLM
 * client, no extra credentials required.</li>
 * <li>{@code embedding} — {@link at.aimon.core.memory.dreamer.EmbeddingSurprisalScorer} backed by
 * {@link at.aimon.core.llms.openai.OpenAIEmbeddingClient}; requires {@code scorer.embedding.apiKey}.</li>
 * </ul>
 *
 * <p>
 * Tuning fields ({@code surprisalThreshold}, {@code walkSeedCount}, {@code neighborTopK}) are optional — sensible
 * defaults from the design doc apply when omitted.
 *
 * <p>
 * <b>Single node only.</b> That dedicated scheduler uses {@code RAMJobStore}, which is per-JVM by construction, so
 * enabling the dreamer on N CLI processes pointed at the same workspace runs consolidation N times over the same
 * memories rather than once. The constraint is the job store, not the scheduler count — clustering would require a
 * shared JDBC store, and nothing about folding the dreamer onto another in-JVM scheduler would provide it.
 *
 * <p>
 * Setters exist for Jackson; the value object is otherwise treated as immutable during agent setup.
 */
public class MemoryDreamerConfig {

    /**
     * Default cron — every 30 minutes, in the framework's five-field dialect (minute hour day-of-month month
     * day-of-week). The Quartz backend translates it; do not write Quartz's six-field form here.
     */
    public static final String DEFAULT_CRON = "*/30 * * * *";

    /** Default surprisal cutoff: pairs with surprisal &lt; threshold are merged. */
    public static final double DEFAULT_SURPRISAL_THRESHOLD = 0.25d;

    /** Default seed count per dreamer cycle (most recent observations sampled per subject). */
    public static final int DEFAULT_WALK_SEED_COUNT = 8;

    /** Default neighbor fan-out (semanticSearch top-K per seed). */
    public static final int DEFAULT_NEIGHBOR_TOP_K = 8;

    /** Default scorer when {@code scorer.type} is omitted — LLM judge needs no extra credentials. */
    public static final ScorerType DEFAULT_SCORER_TYPE = ScorerType.LLM;

    /** Selectable surprisal scorer implementations. */
    public enum ScorerType {
        /** Embedding cosine similarity (requires {@code scorer.embedding.apiKey}). */
        EMBEDDING,
        /** LLM judge (reuses the global LLM client, no extra credentials). */
        LLM;

        /**
         * Parses a string into a {@link ScorerType}, accepting either case. Returns {@link #LLM} when {@code raw} is
         * null/blank to honour the default.
         *
         * @throws IllegalArgumentException
         *             when {@code raw} is non-blank but does not match any known type.
         */
        public static ScorerType fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return LLM;
            }
            for (ScorerType t : values()) {
                if (t.name().equalsIgnoreCase(raw.trim())) {
                    return t;
                }
            }
            throw new IllegalArgumentException("unknown scorer type: '" + raw + "'; valid values: embedding, llm");
        }
    }

    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("cron")
    private String cron = DEFAULT_CRON;

    @JsonProperty("scorer")
    private ScorerConfig scorer;

    @JsonProperty("surprisalThreshold")
    private Double surprisalThreshold;

    @JsonProperty("walkSeedCount")
    private Integer walkSeedCount;

    @JsonProperty("neighborTopK")
    private Integer neighborTopK;

    public MemoryDreamerConfig() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public ScorerConfig getScorer() {
        return scorer;
    }

    public void setScorer(ScorerConfig scorer) {
        this.scorer = scorer;
    }

    public Double getSurprisalThreshold() {
        return surprisalThreshold;
    }

    public void setSurprisalThreshold(Double surprisalThreshold) {
        this.surprisalThreshold = surprisalThreshold;
    }

    public Integer getWalkSeedCount() {
        return walkSeedCount;
    }

    public void setWalkSeedCount(Integer walkSeedCount) {
        this.walkSeedCount = walkSeedCount;
    }

    public Integer getNeighborTopK() {
        return neighborTopK;
    }

    public void setNeighborTopK(Integer neighborTopK) {
        this.neighborTopK = neighborTopK;
    }

    /**
     * Returns the resolved cron expression, falling back to {@link #DEFAULT_CRON} when blank.
     */
    public String resolvedCron() {
        return (cron == null || cron.isBlank()) ? DEFAULT_CRON : cron;
    }

    public double resolvedSurprisalThreshold() {
        return surprisalThreshold != null ? surprisalThreshold : DEFAULT_SURPRISAL_THRESHOLD;
    }

    public int resolvedWalkSeedCount() {
        return walkSeedCount != null ? walkSeedCount : DEFAULT_WALK_SEED_COUNT;
    }

    public int resolvedNeighborTopK() {
        return neighborTopK != null ? neighborTopK : DEFAULT_NEIGHBOR_TOP_K;
    }

    /**
     * Returns the resolved scorer type, falling back to {@link #DEFAULT_SCORER_TYPE} when {@code scorer} or
     * {@code scorer.type} is missing/blank.
     */
    public ScorerType resolvedScorerType() {
        if (scorer == null) {
            return DEFAULT_SCORER_TYPE;
        }
        return ScorerType.fromString(scorer.getType());
    }

    /**
     * Returns {@code true} when the dreamer should actually be wired:
     * <ul>
     * <li>{@code enabled} flag is set, AND
     * <li>for {@link ScorerType#LLM}: always ready (uses the global LLM client),
     * <li>for {@link ScorerType#EMBEDDING}: {@code scorer.embedding.apiKey} is present.
     * </ul>
     */
    public boolean isReady() {
        return notReadyReason() == null;
    }

    /**
     * Returns a human-readable reason explaining why the dreamer is not ready to wire, or {@code null} when
     * {@link #isReady()} would return {@code true}. Surface this in startup diagnostics so users see a
     * scorer-specific hint rather than a generic "disabled" line.
     */
    public String notReadyReason() {
        if (!enabled) {
            return "memory.dreamer.enabled is false";
        }
        final ScorerType type = resolvedScorerType();
        return switch (type) {
            case LLM -> null;
            case EMBEDDING -> {
                if (scorer == null || scorer.getEmbedding() == null) {
                    yield "scorer.embedding block is required when scorer.type=embedding";
                }
                final String apiKey = scorer.getEmbedding().getApiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    yield "scorer.embedding.apiKey is required when scorer.type=embedding";
                }
                yield null;
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final MemoryDreamerConfig that = (MemoryDreamerConfig) o;
        return enabled == that.enabled && Objects.equals(cron, that.cron) && Objects.equals(scorer, that.scorer)
                && Objects.equals(surprisalThreshold, that.surprisalThreshold)
                && Objects.equals(walkSeedCount, that.walkSeedCount) && Objects.equals(neighborTopK, that.neighborTopK);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, cron, scorer, surprisalThreshold, walkSeedCount, neighborTopK);
    }

    @Override
    public String toString() {
        return "MemoryDreamerConfig{enabled=" + enabled + ", cron='" + cron + "', scorer=" + scorer
                + ", surprisalThreshold=" + surprisalThreshold + ", walkSeedCount=" + walkSeedCount + ", neighborTopK="
                + neighborTopK + '}';
    }

    /**
     * Nested scorer configuration: chooses the {@link at.aimon.core.memory.dreamer.SurprisalScorer} implementation and
     * carries its parameters.
     */
    public static final class ScorerConfig {

        @JsonProperty("type")
        private String type;

        @JsonProperty("embedding")
        private EmbeddingScorerConfig embedding;

        @JsonProperty("llm")
        private LlmScorerConfig llm;

        public ScorerConfig() {
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public EmbeddingScorerConfig getEmbedding() {
            return embedding;
        }

        public void setEmbedding(EmbeddingScorerConfig embedding) {
            this.embedding = embedding;
        }

        public LlmScorerConfig getLlm() {
            return llm;
        }

        public void setLlm(LlmScorerConfig llm) {
            this.llm = llm;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final ScorerConfig that = (ScorerConfig) o;
            return Objects.equals(type, that.type) && Objects.equals(embedding, that.embedding)
                    && Objects.equals(llm, that.llm);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, embedding, llm);
        }

        @Override
        public String toString() {
            return "ScorerConfig{type='" + type + "', embedding=" + embedding + ", llm=" + llm + '}';
        }
    }

    /** Configuration for the embedding-based scorer (currently OpenAI-compatible only). */
    public static final class EmbeddingScorerConfig {

        @JsonProperty("apiKey")
        private String apiKey;

        @JsonProperty("baseUrl")
        private String baseUrl;

        @JsonProperty("model")
        private String model;

        @JsonProperty("dimensions")
        private Integer dimensions;

        public EmbeddingScorerConfig() {
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Integer getDimensions() {
            return dimensions;
        }

        public void setDimensions(Integer dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final EmbeddingScorerConfig that = (EmbeddingScorerConfig) o;
            return Objects.equals(apiKey, that.apiKey) && Objects.equals(baseUrl, that.baseUrl)
                    && Objects.equals(model, that.model) && Objects.equals(dimensions, that.dimensions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(apiKey, baseUrl, model, dimensions);
        }

        @Override
        public String toString() {
            return "EmbeddingScorerConfig{model='" + model + "', baseUrl='" + baseUrl + "', dimensions=" + dimensions
                    + '}';
        }
    }

    /**
     * Configuration for the LLM-judge scorer. {@code model} is an optional override; when {@code null} or blank, the
     * factory falls back to the global LLM model name configured for the agent.
     */
    public static final class LlmScorerConfig {

        @JsonProperty("model")
        private String model;

        public LlmScorerConfig() {
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final LlmScorerConfig that = (LlmScorerConfig) o;
            return Objects.equals(model, that.model);
        }

        @Override
        public int hashCode() {
            return Objects.hash(model);
        }

        @Override
        public String toString() {
            return "LlmScorerConfig{model='" + model + "'}";
        }
    }
}
