package at.aimon.core.memory.dialectic;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.tagging.BoundMetadataLlmClient;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationStore;

/**
 * Single-shot {@link DialecticEngine} that prefetches observations via
 * {@link ObservationStore#semanticSearch} and concatenates them into the system
 * prompt for one LLM round-trip.
 *
 * <p>
 * This is the stage 3 implementation as described in design doc §6.2 — it does
 * not run the ReAct loop. The prefetch + single-shot path is fast and cheap
 * enough for the {@link ReasoningLevel#FAST} and {@link ReasoningLevel#BALANCED}
 * tiers; later iterations will add a tool-using ReAct engine for
 * {@link ReasoningLevel#DEEP} queries.
 *
 * <p>
 * Failures (LLM errors, blank responses) are logged and surfaced as a
 * {@link DialecticResponse} carrying an apologetic placeholder answer rather
 * than thrown; the dialectic engine sits behind a tool surface and the tool
 * itself converts results to {@code ToolResult.error()} when needed.
 */
public final class LlmDialecticEngine implements DialecticEngine {

    private static final Logger log = LoggerFactory.getLogger(LlmDialecticEngine.class);

    private static final int DEFAULT_PREFETCH_TOP_K = 8;

    private static final String FALLBACK_ANSWER = "I don't have enough information to answer that.";

    private static final LlmCallMetadata SELF_METADATA = LlmCallMetadata.builder().component("memory")
            .feature("dialectic").build();

    private static final String SYSTEM_PROMPT_HEADER = """
            You answer a question about a peer using only the observations listed below.
            Each observation is tagged with a confidence score in [0,1] and a type
            (EXPLICIT = stated by the speaker, DEDUCTIVE = inferred).

            Rules:
            - Prefer EXPLICIT observations over DEDUCTIVE ones.
            - Prefer high-confidence observations over low-confidence ones.
            - If the observations don't support an answer, say you don't know.
            - Keep the answer short and direct. Do not invent facts.
            """;

    private final LlmClient llmClient;
    private final ObservationStore observationStore;
    private final String llmModelName;
    private final int prefetchTopK;

    public LlmDialecticEngine(LlmClient llmClient, ObservationStore observationStore, String llmModelName) {
        this(llmClient, observationStore, llmModelName, DEFAULT_PREFETCH_TOP_K);
    }

    public LlmDialecticEngine(LlmClient llmClient, ObservationStore observationStore, String llmModelName,
            int prefetchTopK) {
        this.llmClient = new BoundMetadataLlmClient(Objects.requireNonNull(llmClient, "llmClient cannot be null"),
                SELF_METADATA);
        this.observationStore = Objects.requireNonNull(observationStore, "observationStore cannot be null");
        this.llmModelName = Objects.requireNonNull(llmModelName, "llmModelName cannot be null");
        if (llmModelName.isBlank()) {
            throw new IllegalArgumentException("llmModelName cannot be blank");
        }
        if (prefetchTopK < 1) {
            throw new IllegalArgumentException("prefetchTopK must be >= 1, got " + prefetchTopK);
        }
        this.prefetchTopK = prefetchTopK;
    }

    @Override
    public DialecticResponse query(DialecticQuery query) {
        Objects.requireNonNull(query, "query cannot be null");

        log.info("Dialectic query: subject={}, level={}, questionLen={}", query.getSubject().key(), query.getLevel(),
                query.getQuestion().length());
        List<Observation> prefetched = observationStore.semanticSearch(query.getSubject(), query.getQuestion(),
                prefetchTopK);
        log.debug("Prefetched {} observations for subject={}", prefetched.size(), query.getSubject().key());

        String systemPrompt = buildSystemPrompt(query, prefetched);
        LlmModel modelConfig = LlmModel.builder().name(llmModelName).maxTokens(query.getLevel().getMaxTokens()).build();

        try {
            LlmResponse response = llmClient.sendMessage(systemPrompt, List.of(Message.user(query.getQuestion())),
                    List.of(), modelConfig);
            String answer = response.getTextContent();
            if (answer == null || answer.isBlank()) {
                log.info("Dialectic query produced empty answer; using fallback: subject={}", query.getSubject().key());
                return DialecticResponse.builder().answer(FALLBACK_ANSWER).observationsConsidered(prefetched)
                        .tokenUsage(response.getTokenUsage()).build();
            }
            log.info("Dialectic query answered: subject={}, observationsUsed={}, tokens={}", query.getSubject().key(),
                    prefetched.size(), response.getTokenUsage().getTotalTokens());
            return DialecticResponse.builder().answer(answer.trim()).observationsConsidered(prefetched)
                    .tokenUsage(response.getTokenUsage()).build();
        } catch (RuntimeException e) {
            log.error("Dialectic query failed for subject={}: {}", query.getSubject().key(), e.getMessage(), e);
            return DialecticResponse.builder().answer(FALLBACK_ANSWER).observationsConsidered(prefetched).build();
        }
    }

    private static String buildSystemPrompt(DialecticQuery query, List<Observation> observations) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT_HEADER);
        sb.append('\n');
        sb.append("Subject: ").append(query.getSubject().key()).append('\n');
        sb.append("Observer: ").append(query.getObserver().key()).append('\n');
        sb.append("Observations:\n");
        if (observations.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (Observation obs : observations) {
                sb.append("- [confidence=").append(formatConfidence(obs.getConfidence())).append(", ")
                        .append(obs.getType()).append("] ").append(obs.getContent()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String formatConfidence(double confidence) {
        return String.format(Locale.ROOT, "%.2f", confidence);
    }
}
