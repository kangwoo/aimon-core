package at.aimon.core.hook.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.compact.InvokedSkillRecord;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;

/**
 * Context for {@link PostCompactHook}.
 *
 * <p>
 * Provides access to the post-compaction transcript buffer and the metadata of the just-completed compaction.
 * Hooks may use this context to attach restorative messages (e.g. recently-read files) to the conversation.
 *
 * <p>
 * Immutable value object. Use builder to create instances.
 */
public final class PostCompactContext implements HookContext {

    public static Builder builder() {
        return new Builder();
    }

    private final InvokerType invokerType;
    private final String invokerName;
    private final HookRegistry hookRegistry;
    private final Environment environment;
    private final CompactionTrigger trigger;
    private final CompactionMetadata compactionMetadata;
    private final String compactSummary;
    private final TranscriptBuffer transcriptBuffer;
    private final List<String> recentReadFilePaths;
    private final List<InvokedSkillRecord> invokedSkills;
    private final Instant timestamp;
    private final Map<String, Object> executionAttributes;

    private PostCompactContext(Builder builder) {
        invokerType = Objects.requireNonNull(builder.invokerType, "Invoker type cannot be null");
        invokerName = Objects.requireNonNull(builder.invokerName, "Invoker name cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        trigger = Objects.requireNonNull(builder.trigger, "Trigger cannot be null");
        compactionMetadata = Objects.requireNonNull(builder.compactionMetadata, "Compaction metadata cannot be null");
        compactSummary = Objects.requireNonNull(builder.compactSummary, "Compact summary cannot be null");
        transcriptBuffer = Objects.requireNonNull(builder.transcriptBuffer, "Transcript buffer cannot be null");
        recentReadFilePaths = builder.recentReadFilePaths != null
                ? List.copyOf(builder.recentReadFilePaths)
                : List.of();
        invokedSkills = builder.invokedSkills != null ? List.copyOf(builder.invokedSkills) : List.of();
        timestamp = Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null");
        executionAttributes = builder.executionAttributes != null ? Map.copyOf(builder.executionAttributes) : Map.of();
    }

    @Override
    public InvokerType getInvokerType() {
        return invokerType;
    }

    @Override
    public String getInvokerName() {
        return invokerName;
    }

    @Override
    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }

    @Override
    public Environment getEnvironment() {
        return environment;
    }

    public CompactionTrigger getTrigger() {
        return trigger;
    }

    public CompactionMetadata getCompactionMetadata() {
        return compactionMetadata;
    }

    public String getCompactSummary() {
        return compactSummary;
    }

    public TranscriptBuffer getTranscriptBuffer() {
        return transcriptBuffer;
    }

    /**
     * File paths read via the {@code Read} tool during the conversation segment that was just compacted, in
     * insertion order with duplicates removed (oldest first, most recent last). Empty when no Read tool uses were
     * present in the pre-compaction messages.
     *
     * <p>
     * Snapshot computed before the transcript buffer was replaced — surviving consumers (e.g.
     * {@code RecentFilesRestoreHook}) can use it to re-attach context lost in the L3 summary.
     */
    public List<String> getRecentReadFilePaths() {
        return recentReadFilePaths;
    }

    /**
     * Skill invocations that occurred during the conversation segment that was just compacted, in occurrence order with
     * duplicates collapsed to their most recent position. Empty when the pre-compaction range contained no
     * {@code Skill} tool uses.
     *
     * <p>
     * Snapshot computed before the transcript buffer was replaced — surviving consumers (e.g.
     * {@code InvokedSkillsRestoreHook}) can use it to re-attach the list of activated skills lost in the L3 summary so
     * the agent does not forget which skills it has already exercised.
     */
    public List<InvokedSkillRecord> getInvokedSkills() {
        return invokedSkills;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public Map<String, Object> getExecutionAttributes() {
        return executionAttributes;
    }

    @Override
    public String toString() {
        return "PostCompactContext{trigger=" + trigger + ", metadata=" + compactionMetadata + '}';
    }

    /** Builder for {@link PostCompactContext}. */
    public static final class Builder {
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private CompactionTrigger trigger;
        private CompactionMetadata compactionMetadata;
        private String compactSummary;
        private TranscriptBuffer transcriptBuffer;
        private List<String> recentReadFilePaths;
        private List<InvokedSkillRecord> invokedSkills;
        private Instant timestamp;
        private Map<String, Object> executionAttributes;

        private Builder() {
        }

        public Builder invokerType(InvokerType invokerType) {
            this.invokerType = invokerType;
            return this;
        }

        public Builder invokerName(String invokerName) {
            this.invokerName = invokerName;
            return this;
        }

        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        public Builder trigger(CompactionTrigger trigger) {
            this.trigger = trigger;
            return this;
        }

        public Builder compactionMetadata(CompactionMetadata compactionMetadata) {
            this.compactionMetadata = compactionMetadata;
            return this;
        }

        public Builder compactSummary(String compactSummary) {
            this.compactSummary = compactSummary;
            return this;
        }

        public Builder transcriptBuffer(TranscriptBuffer transcriptBuffer) {
            this.transcriptBuffer = transcriptBuffer;
            return this;
        }

        public Builder recentReadFilePaths(List<String> recentReadFilePaths) {
            this.recentReadFilePaths = recentReadFilePaths;
            return this;
        }

        public Builder invokedSkills(List<InvokedSkillRecord> invokedSkills) {
            this.invokedSkills = invokedSkills;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        public PostCompactContext build() {
            if (timestamp == null) {
                timestamp = Instant.now();
            }
            return new PostCompactContext(this);
        }
    }
}
