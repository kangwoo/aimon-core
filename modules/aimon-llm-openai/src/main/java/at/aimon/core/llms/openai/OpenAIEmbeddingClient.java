package at.aimon.core.llms.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingModel;

import at.aimon.core.knowledge.embedding.EmbeddingClient;
import at.aimon.core.knowledge.embedding.EmbeddingException;
import at.aimon.core.knowledge.embedding.EmbeddingResult;

/**
 * OpenAI implementation of {@link EmbeddingClient}.
 *
 * <p>
 * Uses the OpenAI Embeddings API to generate dense vector representations of text. Supports all OpenAI embedding
 * models including {@code text-embedding-3-small} and {@code text-embedding-3-large}.
 *
 * <p>
 * Thread-safe.
 *
 * <pre>{@code
 * OpenAIEmbeddingConfig config = OpenAIEmbeddingConfig.builder()
 *         .apiKey(System.getenv("OPENAI_API_KEY"))
 *         .model("text-embedding-3-small")
 *         .build();
 *
 * EmbeddingClient client = new OpenAIEmbeddingClient(config);
 * EmbeddingResult result = client.embed("Kubernetes CrashLoopBackOff");
 * float[] vector = result.getVector();
 * }</pre>
 *
 * @see EmbeddingClient
 * @see OpenAIEmbeddingConfig
 */
public class OpenAIEmbeddingClient implements EmbeddingClient, at.aimon.core.base.ApplicationScoped {

    private static final Logger log = LoggerFactory.getLogger(OpenAIEmbeddingClient.class);

    private final OpenAIEmbeddingConfig config;
    private final OpenAIClient client;

    /**
     * Creates a new OpenAI embedding client.
     *
     * @param config
     *            the embedding configuration (must not be null)
     * @throws NullPointerException
     *             if config is null
     */
    public OpenAIEmbeddingClient(OpenAIEmbeddingConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.client = createClient(config);
    }

    @Override
    public EmbeddingResult embed(String text) {
        Objects.requireNonNull(text, "text must not be null");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }

        try {
            final EmbeddingCreateParams params = EmbeddingCreateParams.builder().input(text)
                    .model(EmbeddingModel.of(config.getModel())).dimensions(Long.valueOf(config.getDimensions()))
                    .build();

            final CreateEmbeddingResponse response = client.embeddings().create(params);

            if (response.data().isEmpty()) {
                throw new EmbeddingException("Empty embedding response from OpenAI");
            }

            final Embedding embedding = response.data().get(0);
            final float[] vector = extractVector(embedding);
            final int tokenCount = (int) response.usage().totalTokens();

            log.debug("Generated embedding: dimensions={}, tokens={}", vector.length, tokenCount);

            return EmbeddingResult.builder().vector(vector).tokenCount(tokenCount).build();

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Failed to generate embedding: " + e.getMessage(), e);
        }
    }

    @Override
    public List<EmbeddingResult> embedBatch(List<String> texts) {
        Objects.requireNonNull(texts, "texts must not be null");
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("texts must not be empty");
        }

        try {
            final EmbeddingCreateParams params = EmbeddingCreateParams.builder().inputOfArrayOfStrings(texts)
                    .model(EmbeddingModel.of(config.getModel())).dimensions(Long.valueOf(config.getDimensions()))
                    .build();

            final CreateEmbeddingResponse response = client.embeddings().create(params);

            final int totalTokens = (int) response.usage().totalTokens();
            final int tokensPerItem = !texts.isEmpty() ? totalTokens / texts.size() : 0;

            // Sort by index to ensure output order matches input order
            final List<Embedding> sortedData = new ArrayList<>(response.data());
            sortedData.sort((a, b) -> Long.compare(a.index(), b.index()));

            final List<EmbeddingResult> results = new ArrayList<>(sortedData.size());
            for (Embedding embedding : sortedData) {
                final float[] vector = extractVector(embedding);
                results.add(EmbeddingResult.builder().vector(vector).tokenCount(tokensPerItem).build());
            }

            log.debug("Generated {} batch embeddings, total tokens: {}", results.size(), totalTokens);
            return results;

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Failed to generate batch embeddings: " + e.getMessage(), e);
        }
    }

    @Override
    public int getDimensions() {
        return config.getDimensions();
    }

    private float[] extractVector(Embedding embedding) {
        final List<Float> embeddingValues = embedding.embedding();
        final float[] vector = new float[embeddingValues.size()];
        for (int i = 0; i < embeddingValues.size(); i++) {
            vector[i] = embeddingValues.get(i);
        }
        return vector;
    }

    private static OpenAIClient createClient(OpenAIEmbeddingConfig config) {
        final OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().apiKey(config.getApiKey())
                .timeout(config.getTimeout());

        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            builder.baseUrl(config.getBaseUrl());
        }

        return builder.build();
    }
}
