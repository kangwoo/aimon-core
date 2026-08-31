package at.aimon.core.memory.index;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.knowledge.IndexOptions;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeSource;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.knowledge.SearchQuery;
import at.aimon.core.knowledge.SearchResult;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.PeerView;

/**
 * {@link ObservationIndex} that delegates ranking and search to a
 * {@link KnowledgeStore} (design doc §5.2 — observations indexed
 * under {@code KnowledgeScope("memory.observation", subjectKey)}).
 *
 * <p>
 * The adapter buffers observation content per-subject and re-emits it through
 * an internal in-memory {@link StagingFileSystem} into
 * {@link KnowledgeStore#reindex(KnowledgeScope, KnowledgeSource, IndexOptions)}.
 * Search calls are translated to {@link KnowledgeStore#search} and the
 * resulting document paths are mapped back to {@link ObservationId}.
 *
 * <p>
 * <b>This is the verification implementation</b>. Every {@code save}/{@code delete}
 * triggers a full scope-level reindex, which is acceptable for the in-memory
 * KnowledgeStore backend (e.g.,
 * {@link at.aimon.core.knowledge.KeywordKnowledgeStore}) and for validating the
 * delegation contract before a persistent embedding backend with single-doc
 * upsert/delete becomes available.
 *
 * <p>
 * Subject isolation is enforced by giving each {@link PeerView} its own
 * {@link KnowledgeScope} with {@code contextId = subjectKey}, so search
 * results never cross subjects even when the underlying KnowledgeStore is
 * shared.
 *
 * <p>
 * Thread-safe across different subjects; updates against the same subject are
 * serialised on a per-subject lock so the staging area and the
 * KnowledgeStore's view of it stay consistent.
 */
public final class KnowledgeStoreObservationIndex implements ObservationIndex {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStoreObservationIndex.class);

    /** Default agent-name segment for the {@link KnowledgeScope}. */
    public static final String DEFAULT_AGENT_NAME = "memory.observation";

    /** Root staging directory under which per-subject directories live. */
    private static final String STAGING_ROOT = "/observations";

    /** File extension used for staged observation content (matches {@link IndexOptions#DEFAULT_FILE_PATTERNS}). */
    private static final String STAGING_FILE_EXTENSION = ".txt";

    /**
     * Multiplier applied to {@code topK} when querying the backing
     * {@link KnowledgeStore}. A single observation may be split into several
     * chunks/documents by the store, so the raw hit list can contain multiple
     * results that map to the same {@link ObservationId}. Over-fetching and then
     * de-duplicating lets us still return {@code topK} <em>distinct</em>
     * observations.
     */
    private static final int SEARCH_OVERFETCH_FACTOR = 4;

    /** Hard cap on the over-fetched result count to bound search cost. */
    private static final int SEARCH_OVERFETCH_MAX = 200;

    private final KnowledgeStore knowledgeStore;
    private final IndexOptions indexOptions;
    private final String agentName;

    /**
     * Per-subject mutable holder. Each instance carries both the entry map and
     * the monitor used to serialise updates against that subject. Bundling the
     * lock with the data eliminates the index/delete race we'd otherwise have
     * if {@code idToSubject} and {@code bySubject} were mutated under separate
     * locks.
     */
    private static final class SubjectEntries {
        final Map<ObservationId, String> entries = new HashMap<>();
    }

    /** subjectKey -> per-subject holder. */
    private final Map<String, SubjectEntries> bySubject = new ConcurrentHashMap<>();
    /** observationId -> subjectKey. Mutated only while holding the corresponding holder's monitor. */
    private final Map<ObservationId, String> idToSubject = new ConcurrentHashMap<>();

    private final StagingFileSystem stagingVfs = new StagingFileSystem();

    /**
     * Creates an adapter using {@link IndexOptions#defaults()} and the
     * {@value #DEFAULT_AGENT_NAME} agent name.
     */
    public KnowledgeStoreObservationIndex(KnowledgeStore knowledgeStore) {
        this(knowledgeStore, IndexOptions.defaults(), DEFAULT_AGENT_NAME);
    }

    /**
     * Creates an adapter with custom indexing options and agent-name segment.
     *
     * @param knowledgeStore
     *            the backing knowledge store (must not be null)
     * @param indexOptions
     *            options passed to every {@link KnowledgeStore#reindex} call
     *            (must not be null). The default file patterns must include
     *            {@code *.txt} so the staging files are picked up.
     * @param agentName
     *            value used as {@link KnowledgeScope#getAgentName()} (must
     *            not be null or blank)
     */
    public KnowledgeStoreObservationIndex(KnowledgeStore knowledgeStore, IndexOptions indexOptions, String agentName) {
        this.knowledgeStore = Objects.requireNonNull(knowledgeStore, "knowledgeStore cannot be null");
        this.indexOptions = Objects.requireNonNull(indexOptions, "indexOptions cannot be null");
        this.agentName = Objects.requireNonNull(agentName, "agentName cannot be null");
        if (agentName.isBlank()) {
            throw new IllegalArgumentException("agentName cannot be blank");
        }
    }

    @Override
    public void index(Observation observation) {
        Objects.requireNonNull(observation, "observation cannot be null");
        String subjectKey = observation.getSubject().key();
        SubjectEntries holder = bySubject.computeIfAbsent(subjectKey, k -> new SubjectEntries());
        synchronized (holder) {
            holder.entries.put(observation.getId(), observation.getContent());
            idToSubject.put(observation.getId(), subjectKey);
            rebuildScopeBySubjectKey(subjectKey, holder);
            log.debug("Indexed observation in KnowledgeStore: id={}, subject={}, scopeSize={}", observation.getId(),
                    subjectKey, holder.entries.size());
        }
    }

    @Override
    public void delete(ObservationId id) {
        Objects.requireNonNull(id, "id cannot be null");
        // Peek without removing; we mutate idToSubject inside the holder's monitor so a concurrent
        // index() that re-introduces the same id can't leave an orphan entry behind.
        String subjectKey = idToSubject.get(id);
        if (subjectKey == null) {
            return;
        }
        SubjectEntries holder = bySubject.get(subjectKey);
        if (holder == null) {
            return;
        }
        synchronized (holder) {
            // Re-check inside the lock — a concurrent index() may have moved this id under a
            // different subject, in which case the original subjectKey is stale.
            String currentSubjectKey = idToSubject.get(id);
            if (currentSubjectKey == null || !currentSubjectKey.equals(subjectKey)) {
                return;
            }
            holder.entries.remove(id);
            idToSubject.remove(id);
            rebuildScopeBySubjectKey(subjectKey, holder);
            log.debug("Removed observation from KnowledgeStore index: id={}, subject={}, remaining={}", id, subjectKey,
                    holder.entries.size());
            // Holder is intentionally retained even when empty: removing it would create a
            // window where a concurrent index() lands on a fresh holder while a stale reference
            // is being torn down. Memory cost is bounded by unique-subject count (peer fan-out).
        }
    }

    @Override
    public List<ObservationId> search(PeerView subject, String query, int topK) {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(query, "query cannot be null");
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1, got " + topK);
        }
        if (query.isBlank()) {
            return List.of();
        }
        // Over-fetch: a single observation can be split into multiple chunks/documents, so the
        // raw hit list may contain several results mapping to the same ObservationId. We request
        // more than topK and de-duplicate (preserving rank order) so the caller still receives
        // up to topK *distinct* observations rather than topK chunks.
        int fetch = Math.min((int) Math.min((long) topK * SEARCH_OVERFETCH_FACTOR, SEARCH_OVERFETCH_MAX),
                SEARCH_OVERFETCH_MAX);
        SearchQuery sq = SearchQuery.builder().queryText(query).maxResults(fetch).build();
        List<SearchResult> hits = knowledgeStore.search(scopeFor(subject.key()), sq);
        log.debug("KnowledgeStore search: subject={}, topK={}, fetch={}, queryLen={}, hits={}", subject, topK, fetch,
                query.length(), hits.size());
        if (hits.isEmpty()) {
            return List.of();
        }
        Set<ObservationId> ids = new LinkedHashSet<>();
        for (SearchResult hit : hits) {
            ObservationId id = parseObservationId(subject, hit.getDocumentPath());
            if (id != null) {
                ids.add(id);
                if (ids.size() >= topK) {
                    break;
                }
            }
        }
        return List.copyOf(ids);
    }

    private KnowledgeScope scopeFor(String subjectKey) {
        return new KnowledgeScope(agentName, subjectKey);
    }

    private String stagingDirectory(String subjectKey) {
        return STAGING_ROOT + "/" + subjectKey;
    }

    private String stagingPath(String subjectKey, ObservationId id) {
        return stagingDirectory(subjectKey) + "/" + id.getLocalId() + STAGING_FILE_EXTENSION;
    }

    private void rebuildScopeBySubjectKey(String subjectKey, SubjectEntries holder) {
        // Caller must hold the holder's monitor; the holder reference is supplied directly so we
        // don't re-resolve it via bySubject.get(...) and re-introduce a window where a concurrent
        // mutation could swap holders between lookup and read.
        String dir = stagingDirectory(subjectKey);
        stagingVfs.clearDirectory(dir);
        for (Map.Entry<ObservationId, String> e : holder.entries.entrySet()) {
            stagingVfs.put(stagingPath(subjectKey, e.getKey()), e.getValue());
        }
        // KnowledgeStore.reindex on an empty directory clears the scope — exactly
        // what we want when a subject's last observation was deleted.
        knowledgeStore.reindex(scopeFor(subjectKey), new KnowledgeSource(stagingVfs, dir), indexOptions);
    }

    private ObservationId parseObservationId(PeerView subject, String documentPath) {
        if (documentPath == null) {
            return null;
        }
        int slash = documentPath.lastIndexOf('/');
        if (slash < 0 || slash == documentPath.length() - 1) {
            return null;
        }
        String filename = documentPath.substring(slash + 1);
        if (!filename.endsWith(STAGING_FILE_EXTENSION)) {
            return null;
        }
        String localId = filename.substring(0, filename.length() - STAGING_FILE_EXTENSION.length());
        if (localId.isBlank()) {
            return null;
        }
        return ObservationId.of(subject.getWorkspace(), localId);
    }
}
