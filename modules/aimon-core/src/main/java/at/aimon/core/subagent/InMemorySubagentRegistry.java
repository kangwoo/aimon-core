package at.aimon.core.subagent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link MutableSubagentRegistry} for code-defined subagents.
 *
 * <p>
 * Holds {@link Subagent} instances registered programmatically (typically via {@link Subagent#builder()}) rather than
 * parsed from {@code agents/*.md} files. It is composed with the file-based registries through
 * {@link CompositeSubagentRegistry}; the calling/execution layers ({@code TaskTool}, the ReAct executor, skill-fork,
 * the {@code /agents} command) consume the read-only {@link SubagentRegistry} view and therefore treat code-defined
 * subagents identically to markdown ones.
 *
 * <p>
 * <b>Naming note:</b> for subagents the {@code Default*} name ({@link DefaultSubagentRegistry}) is already taken by the
 * file-based implementation, so this in-memory implementation is named {@code InMemory*} — the inverse of
 * {@code DefaultToolRegistry}/{@code DefaultAgentRegistry} (which are in-memory). Precedent for an in-memory domain
 * registry living outside an {@code .impl} package: {@code at.aimon.core.llm.InMemoryModelContextWindowRegistry}.
 *
 * <p>
 * <b>Reload is a deliberate no-op.</b> Code-defined subagents have no external source to re-read.
 * {@link CompositeSubagentRegistry} propagates {@link #reloadAll()} / {@link #reloadSubagent(String)} unconditionally
 * to
 * every layer, so these methods MUST retain the in-memory state (no throw, no clear) — otherwise a file hot-reload
 * would
 * silently wipe the code-defined subagents.
 *
 * <p>
 * Thread-safe: backed by a {@link ConcurrentHashMap}.
 *
 * @see MutableSubagentRegistry
 * @see CompositeSubagentRegistry
 */
public final class InMemorySubagentRegistry implements MutableSubagentRegistry {

    private final Map<String, Subagent> subagents = new ConcurrentHashMap<>();

    @Override
    public void register(Subagent subagent) {
        Objects.requireNonNull(subagent, "Subagent cannot be null");
        subagents.put(subagent.getName(), subagent);
    }

    @Override
    public Optional<Subagent> unregister(String name) {
        Objects.requireNonNull(name, "Name cannot be null");
        return Optional.ofNullable(subagents.remove(name));
    }

    @Override
    public Optional<Subagent> getSubagent(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");
        return Optional.ofNullable(subagents.get(subagentName));
    }

    @Override
    public List<Subagent> getAllSubagents() {
        return List.copyOf(subagents.values());
    }

    /**
     * No-op: code-defined subagents have no external source to reload. See the class Javadoc — this must never throw or
     * clear, otherwise a composite-propagated file reload would wipe the code-defined subagents.
     */
    @Override
    public void reloadSubagent(String subagentName) {
        // no-op: no external source to reload
    }

    /**
     * No-op: code-defined subagents have no external source to reload. See the class Javadoc — this must never throw or
     * clear, otherwise a composite-propagated file reload would wipe the code-defined subagents.
     */
    @Override
    public void reloadAll() {
        // no-op: no external source to reload
    }
}
