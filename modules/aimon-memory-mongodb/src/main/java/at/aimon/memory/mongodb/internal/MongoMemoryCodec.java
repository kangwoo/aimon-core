/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.mongodb.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bson.Document;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.Workspace;

/**
 * Boundary codec converting the memory domain types to and from BSON {@link Document}s. Keeps the
 * MongoDB wire shape explicit and isolates the core types from the driver — changing a shape here is
 * a versioned migration (documents written today must read tomorrow).
 *
 * <p>
 * Observations are stored <em>flat</em>: the workspace is not denormalised into every observation
 * document (it is resolved from query context, like the Postgres backend's join), so
 * {@link #observationFromDocument(Document, Workspace)} takes the resolved {@link Workspace}.
 * Representations, being immutable point-in-time snapshots, embed a full workspace snapshot.
 */
public final class MongoMemoryCodec {

    // --- workspace -----------------------------------------------------------

    /** Serialises a workspace using its id as {@code _id}. */
    public Document workspaceToDocument(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        return new Document(DocumentKeys.ID, workspace.getId())
                .append(DocumentKeys.DISPLAY_NAME, workspace.getDisplayName())
                .append(DocumentKeys.CREATED_AT, Date.from(workspace.getCreatedAt()))
                .append(DocumentKeys.METADATA, mapToDocument(workspace.getMetadata()));
    }

    /** Reads a workspace whose {@code _id} is the workspace id. */
    public Workspace workspaceFromDocument(Document doc) {
        Objects.requireNonNull(doc, "doc cannot be null");
        return Workspace.builder().id(doc.getString(DocumentKeys.ID))
                .displayName(doc.getString(DocumentKeys.DISPLAY_NAME)).createdAt(instant(doc, DocumentKeys.CREATED_AT))
                .metadata(documentToMap(doc.get(DocumentKeys.METADATA))).build();
    }

    /** Reads a workspace from an embedded sub-document (representation snapshot) keyed without {@code _id}. */
    public Workspace embeddedWorkspaceFromDocument(Document doc) {
        Objects.requireNonNull(doc, "doc cannot be null");
        return Workspace.builder().id(doc.getString(DocumentKeys.WORKSPACE_ID))
                .displayName(doc.getString(DocumentKeys.DISPLAY_NAME)).createdAt(instant(doc, DocumentKeys.CREATED_AT))
                .metadata(documentToMap(doc.get(DocumentKeys.METADATA))).build();
    }

    /** Serialises a workspace as an embedded snapshot (id under {@code workspaceId}, no {@code _id}). */
    public Document embeddedWorkspaceToDocument(Workspace workspace) {
        return new Document(DocumentKeys.WORKSPACE_ID, workspace.getId())
                .append(DocumentKeys.DISPLAY_NAME, workspace.getDisplayName())
                .append(DocumentKeys.CREATED_AT, Date.from(workspace.getCreatedAt()))
                .append(DocumentKeys.METADATA, mapToDocument(workspace.getMetadata()));
    }

    // --- observation ---------------------------------------------------------

    /** Serialises an observation flat; {@code softDeletedAt} is initialised to null (live). */
    public Document observationToDocument(Observation obs) {
        Objects.requireNonNull(obs, "observation cannot be null");
        return new Document(DocumentKeys.WORKSPACE_ID, obs.getId().getWorkspaceId())
                .append(DocumentKeys.LOCAL_ID, obs.getId().getLocalId())
                .append(DocumentKeys.SUBJECT_TYPE, obs.getSubject().getPrincipal().getType().name())
                .append(DocumentKeys.SUBJECT_ID, obs.getSubject().getPrincipal().getId())
                .append(DocumentKeys.SUBJECT_DISPLAY_NAME, obs.getSubject().getPrincipal().getDisplayName())
                .append(DocumentKeys.OBSERVER_TYPE, obs.getObserver().getPrincipal().getType().name())
                .append(DocumentKeys.OBSERVER_ID, obs.getObserver().getPrincipal().getId())
                .append(DocumentKeys.OBSERVER_DISPLAY_NAME, obs.getObserver().getPrincipal().getDisplayName())
                .append(DocumentKeys.CONTENT, obs.getContent()).append(DocumentKeys.TYPE, obs.getType().name())
                .append(DocumentKeys.SOURCE_MESSAGE_IDS, new ArrayList<>(obs.getSourceMessageIds()))
                .append(DocumentKeys.CONFIDENCE, obs.getConfidence())
                .append(DocumentKeys.METADATA, mapToDocument(obs.getMetadata()))
                .append(DocumentKeys.CREATED_AT, Date.from(obs.getCreatedAt()))
                .append(DocumentKeys.SOFT_DELETED_AT, null);
    }

    /** Reads an observation, rehydrating its subject/observer {@link PeerView}s with {@code workspace}. */
    public Observation observationFromDocument(Document doc, Workspace workspace) {
        Objects.requireNonNull(doc, "doc cannot be null");
        Objects.requireNonNull(workspace, "workspace cannot be null");
        Principal subject = Principal.builder().type(Principal.Type.valueOf(doc.getString(DocumentKeys.SUBJECT_TYPE)))
                .id(doc.getString(DocumentKeys.SUBJECT_ID))
                .displayName(doc.getString(DocumentKeys.SUBJECT_DISPLAY_NAME)).build();
        Principal observer = Principal.builder().type(Principal.Type.valueOf(doc.getString(DocumentKeys.OBSERVER_TYPE)))
                .id(doc.getString(DocumentKeys.OBSERVER_ID))
                .displayName(doc.getString(DocumentKeys.OBSERVER_DISPLAY_NAME)).build();
        return Observation.builder().id(ObservationId.of(workspace, doc.getString(DocumentKeys.LOCAL_ID)))
                .subject(PeerView.of(workspace, subject)).observer(PeerView.of(workspace, observer))
                .content(doc.getString(DocumentKeys.CONTENT))
                .type(ObservationType.valueOf(doc.getString(DocumentKeys.TYPE)))
                .sourceMessageIds(stringList(doc.get(DocumentKeys.SOURCE_MESSAGE_IDS)))
                .createdAt(instant(doc, DocumentKeys.CREATED_AT))
                .confidence(((Number) doc.getOrDefault(DocumentKeys.CONFIDENCE, 0.0d)).doubleValue())
                .metadata(documentToMap(doc.get(DocumentKeys.METADATA))).build();
    }

    // --- representation ------------------------------------------------------

    /** Serialises a representation with an embedded workspace snapshot and flat subject/observer principals. */
    public Document representationToDocument(Representation rep) {
        Objects.requireNonNull(rep, "representation cannot be null");
        Workspace workspace = rep.getSubject().getWorkspace();
        Document doc = new Document(DocumentKeys.WORKSPACE_ID, workspace.getId())
                .append(DocumentKeys.WORKSPACE, embeddedWorkspaceToDocument(workspace))
                .append(DocumentKeys.SUBJECT_TYPE, rep.getSubject().getPrincipal().getType().name())
                .append(DocumentKeys.SUBJECT_ID, rep.getSubject().getPrincipal().getId())
                .append(DocumentKeys.SUBJECT_DISPLAY_NAME, rep.getSubject().getPrincipal().getDisplayName());
        rep.getObserver().ifPresent(o -> {
            doc.append(DocumentKeys.OBSERVER_TYPE, o.getPrincipal().getType().name());
            doc.append(DocumentKeys.OBSERVER_ID, o.getPrincipal().getId());
            doc.append(DocumentKeys.OBSERVER_DISPLAY_NAME, o.getPrincipal().getDisplayName());
        });
        rep.getSessionId().ifPresent(sid -> doc.append(DocumentKeys.SESSION_ID, sid));
        List<Document> observations = new ArrayList<>(rep.getObservations().size());
        for (Observation o : rep.getObservations()) {
            observations.add(observationToDocument(o));
        }
        doc.append(DocumentKeys.OBSERVATIONS, observations).append(DocumentKeys.SUMMARY, rep.getSummary())
                .append(DocumentKeys.GENERATED_AT, Date.from(rep.getGeneratedAt()))
                .append(DocumentKeys.TOKEN_COUNT, rep.getTokenCount());
        return doc;
    }

    /** Reads a representation, rebuilding it from its embedded workspace snapshot. */
    public Representation representationFromDocument(Document doc) {
        Objects.requireNonNull(doc, "doc cannot be null");
        Workspace workspace = embeddedWorkspaceFromDocument(doc.get(DocumentKeys.WORKSPACE, Document.class));
        Principal subject = Principal.builder().type(Principal.Type.valueOf(doc.getString(DocumentKeys.SUBJECT_TYPE)))
                .id(doc.getString(DocumentKeys.SUBJECT_ID))
                .displayName(doc.getString(DocumentKeys.SUBJECT_DISPLAY_NAME)).build();
        Representation.Builder builder = Representation.builder().subject(PeerView.of(workspace, subject))
                .summary(doc.getString(DocumentKeys.SUMMARY)).generatedAt(instant(doc, DocumentKeys.GENERATED_AT))
                .tokenCount(((Number) doc.getOrDefault(DocumentKeys.TOKEN_COUNT, 0)).intValue());
        if (doc.getString(DocumentKeys.OBSERVER_TYPE) != null) {
            Principal observer = Principal.builder()
                    .type(Principal.Type.valueOf(doc.getString(DocumentKeys.OBSERVER_TYPE)))
                    .id(doc.getString(DocumentKeys.OBSERVER_ID))
                    .displayName(doc.getString(DocumentKeys.OBSERVER_DISPLAY_NAME)).build();
            builder.observer(PeerView.of(workspace, observer));
        }
        if (doc.getString(DocumentKeys.SESSION_ID) != null) {
            builder.sessionId(doc.getString(DocumentKeys.SESSION_ID));
        }
        List<Observation> observations = new ArrayList<>();
        Object raw = doc.get(DocumentKeys.OBSERVATIONS);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Document obsDoc) {
                    observations.add(observationFromDocument(obsDoc, workspace));
                }
            }
        }
        return builder.observations(observations).build();
    }

    // --- helpers -------------------------------------------------------------

    private static Document mapToDocument(Map<String, String> map) {
        Document doc = new Document();
        if (map != null) {
            map.forEach(doc::append);
        }
        return doc;
    }

    private static Map<String, String> documentToMap(Object raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw instanceof Document doc) {
            for (Map.Entry<String, Object> e : doc.entrySet()) {
                map.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        }
        return map;
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static Instant instant(Document doc, String key) {
        Date date = doc.getDate(key);
        if (date == null) {
            throw new IllegalArgumentException("Missing or non-date field: " + key);
        }
        return date.toInstant();
    }
}
