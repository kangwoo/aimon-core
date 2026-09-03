package at.aimon.cli.config;

import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

import at.aimon.core.memory.MemoryIngestMode;

/**
 * CLI-side configuration for the Honcho-analogue peer memory subsystem.
 *
 * <p>
 * When present in {@code aimon.yaml}, the {@link at.aimon.cli.factory.AgentSetupFactory}
 * builds a {@link at.aimon.memory.file.FileRepresentationStore} backed by
 * {@code storagePath}, registers {@link at.aimon.core.tools.memory.MemoryRecallTool},
 * and installs a {@link at.aimon.core.tools.memory.MemoryToolContextEnricher} so
 * the tool resolves the workspace + observer for every tool call.
 *
 * <p>
 * All three fields are required when memory is enabled:
 * <ul>
 * <li>{@code workspaceId} — the multi-tenant tenant id used to bind every
 * observation/representation to a logical workspace.</li>
 * <li>{@code peerId} — the agent's own peer identity (used as both observer and
 * subject by default; future enrichers may override the subject for
 * multi-peer setups).</li>
 * <li>{@code storagePath} — filesystem path of the JSONL log that backs the
 * file-based representation store.</li>
 * </ul>
 *
 * <p>
 * Setters exist for Jackson; the value object is otherwise treated as immutable
 * during agent setup.
 */
public class MemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfig.class);

    /** Durable single-node backend: file-backed JSONL stores ({@code aimon-memory-file}). Default. */
    public static final String BACKEND_FILE = "file";

    /** Non-durable backend: in-memory stores. Lost on restart; dev/test only. */
    public static final String BACKEND_IN_MEMORY = "in-memory";

    @JsonProperty("workspaceId")
    private String workspaceId;

    @JsonProperty("peerId")
    private String peerId;

    @JsonProperty("peerName")
    private String peerName;

    @JsonProperty("storagePath")
    private String storagePath;

    /**
     * Storage backend selector. {@code file} (default) wires the file-backed JSONL stores so observations and
     * representations survive a restart (the workspace itself is reconstructed from this config each boot, so it is
     * not persisted by any backend); {@code in-memory} wires non-durable stores (dev/test). Unknown values fall back
     * to {@code file} with a startup warning. PostgreSQL/OpenSearch backends are not yet wired into the CLI.
     */
    @JsonProperty("backend")
    private String backend;

    /**
     * When conversation is fed into memory: {@code off}, {@code session-end} (default) or {@code execution-end}.
     *
     * <p>
     * The default is the CLI's existing behaviour — the whole transcript goes in once, when the REPL exits — so an
     * upgrade does not quietly multiply anyone's LLM bill. {@code execution-end} feeds each execution's own messages
     * as it finishes, which is what a memory backend built around a message stream wants and what makes memory
     * available to the session that is producing it. Unknown values fall back to {@code session-end} with a warning.
     */
    @JsonProperty("ingest")
    private String ingest;

    /**
     * Opt-in toggle for the LLM-as-judge {@link at.aimon.core.memory.reconciler.Reconciler}: when true, the
     * session-close deriver searches the existing observation set for each candidate and routes the result through the
     * reconciler before persisting. Default {@code false} preserves the legacy "save every parsed candidate" behavior
     * since reconciliation can issue an extra LLM call per candidate that has any conflict in the store.
     */
    @JsonProperty("reconcilerEnabled")
    private boolean reconcilerEnabled;

    /**
     * Optional dreamer (background consolidation) configuration. When present and {@code enabled == true}, the factory
     * spins up a dedicated Quartz scheduler that periodically runs random-walk consolidation against the workspace
     * declared in this block.
     */
    @JsonProperty("dreamer")
    private MemoryDreamerConfig dreamer;

    public MemoryConfig() {
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    public String getPeerName() {
        return peerName;
    }

    public void setPeerName(String peerName) {
        this.peerName = peerName;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    /**
     * Returns the backend selector normalized to lower-case, defaulting to {@link #BACKEND_FILE} when unset/blank.
     * The factory switches on this value; unknown values are handled there (warn + fall back to file).
     */
    public String resolvedBackend() {
        return (backend == null || backend.isBlank()) ? BACKEND_FILE : backend.trim().toLowerCase(Locale.ROOT);
    }

    public String getIngest() {
        return ingest;
    }

    public void setIngest(String ingest) {
        this.ingest = ingest;
    }

    /**
     * Returns the ingest mode, defaulting to {@link MemoryIngestMode#SESSION_END} when unset or unrecognized.
     *
     * <p>
     * An unrecognized value falls back rather than failing the boot, and says so — the same shape as the backend
     * selector. Falling back to the existing behaviour is the conservative half of that choice: a typo cannot turn
     * ingest off, and it cannot turn on a mode that costs an LLM call per execution either.
     *
     * @return the resolved mode, never null
     */
    public MemoryIngestMode resolvedIngest() {
        if (ingest == null || ingest.isBlank()) {
            return MemoryIngestMode.SESSION_END;
        }
        final String normalized = ingest.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "off" :
                return MemoryIngestMode.OFF;
            case "session-end" :
                return MemoryIngestMode.SESSION_END;
            case "execution-end" :
                return MemoryIngestMode.EXECUTION_END;
            default :
                log.warn("Peer memory: unknown ingest mode '{}', falling back to session-end"
                        + " (expected off | session-end | execution-end)", ingest);
                return MemoryIngestMode.SESSION_END;
        }
    }

    public boolean isReconcilerEnabled() {
        return reconcilerEnabled;
    }

    public void setReconcilerEnabled(boolean reconcilerEnabled) {
        this.reconcilerEnabled = reconcilerEnabled;
    }

    public MemoryDreamerConfig getDreamer() {
        return dreamer;
    }

    public void setDreamer(MemoryDreamerConfig dreamer) {
        this.dreamer = dreamer;
    }

    /**
     * Returns {@code true} when all required fields are set; the factory uses
     * this guard to decide whether to wire the memory subsystem.
     */
    public boolean isEnabled() {
        return workspaceId != null && !workspaceId.isBlank() && peerId != null && !peerId.isBlank()
                && storagePath != null && !storagePath.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final MemoryConfig that = (MemoryConfig) o;
        return reconcilerEnabled == that.reconcilerEnabled && Objects.equals(workspaceId, that.workspaceId)
                && Objects.equals(peerId, that.peerId) && Objects.equals(peerName, that.peerName)
                && Objects.equals(storagePath, that.storagePath) && Objects.equals(backend, that.backend)
                && Objects.equals(dreamer, that.dreamer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceId, peerId, peerName, storagePath, backend, reconcilerEnabled, dreamer);
    }

    @Override
    public String toString() {
        return "MemoryConfig{" + "workspaceId='" + workspaceId + '\'' + ", peerId='" + peerId + '\'' + ", peerName='"
                + peerName + '\'' + ", storagePath='" + storagePath + '\'' + ", backend='" + backend + '\''
                + ", reconcilerEnabled=" + reconcilerEnabled + ", dreamer=" + dreamer + '}';
    }
}
