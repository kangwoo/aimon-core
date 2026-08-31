package at.aimon.core.llm.streaming;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;

/**
 * Provider-neutral representation of a single streaming chunk.
 *
 * <p>
 * A chunk is one of two kinds:
 * <ul>
 * <li>{@link Kind#TEXT_DELTA} — carries a non-empty {@code textDelta} string added by the assistant to the running
 * response text. Empty deltas are filtered out by the provider mapper.</li>
 * <li>{@link Kind#STREAM_END} — terminal marker emitted exactly once per sink lifecycle. May carry the cumulative
 * {@link TokenUsage} and the provider finish reason when available.</li>
 * </ul>
 *
 * <p>
 * Tool-call argument <em>deltas</em> are intentionally <b>not</b> part of this model — the incremental JSON fragments
 * are accumulated by {@link ChunkAggregator} and the final response's tool uses are parsed once at stream end. See the
 * design document ({@code docs/design/llm/streaming.md}) §4.1 and §9 for the rationale.
 *
 * <p>
 * A <em>completed</em> tool_use block, however, may be surfaced early via {@link Kind#TOOL_USE_READY} so a caller can
 * begin executing a side-effect-free tool while the rest of the response is still streaming (design §4.1,
 * streaming-tool overlap). This signal is advisory: the authoritative tool uses remain those parsed by
 * {@link ChunkAggregator#toLlmResponse()} at stream end. Providers that cannot detect per-tool completion simply never
 * emit it.
 *
 * <p>
 * Instances are immutable and thread-safe.
 */
public final class LlmStreamChunk {

    /**
     * Kind of a streaming chunk.
     */
    public enum Kind {
        /** Non-empty text delta to be appended to the running text. */
        TEXT_DELTA,
        /**
         * A single tool_use block has finished streaming and its arguments are fully parsed. Carries the completed
         * {@link ToolUse}. Advisory / overlap-only (design §4.1) — the final response's tool uses are still those
         * built by {@link ChunkAggregator#toLlmResponse()}. Not emitted by every provider.
         */
        TOOL_USE_READY,
        /** Terminal marker. Emitted exactly once per sink lifecycle. */
        STREAM_END
    }

    /**
     * Creates a text-delta chunk.
     *
     * @param index
     *            zero-based chunk ordinal; must be non-negative
     * @param textDelta
     *            the added text; must be non-null and non-empty (empty deltas must be filtered by the provider mapper)
     * @return a new {@code TEXT_DELTA} chunk
     */
    public static LlmStreamChunk textDelta(int index, String textDelta) {
        Objects.requireNonNull(textDelta, "textDelta");
        if (textDelta.isEmpty()) {
            throw new IllegalArgumentException("textDelta must not be empty; provider mapper must filter empty deltas");
        }
        return new Builder().kind(Kind.TEXT_DELTA).index(index).textDelta(textDelta).timestamp(Instant.now()).build();
    }

    /**
     * Creates a {@code TOOL_USE_READY} chunk announcing that one tool_use block has finished streaming (design §4.1).
     *
     * @param index
     *            zero-based chunk ordinal; must be non-negative. Carries the provider tool-call slot index for
     *            diagnostics only — callers correlate the tool by {@link ToolUse#getId()}, never by this ordinal.
     * @param toolUse
     *            the fully-parsed tool use; must not be {@code null}
     * @return a new {@code TOOL_USE_READY} chunk
     */
    public static LlmStreamChunk toolUseReady(int index, ToolUse toolUse) {
        Objects.requireNonNull(toolUse, "toolUse");
        return new Builder().kind(Kind.TOOL_USE_READY).index(index).toolUse(toolUse).timestamp(Instant.now()).build();
    }

    /**
     * Creates a stream-end chunk.
     *
     * @param index
     *            zero-based chunk ordinal; must be non-negative
     * @param tokenUsage
     *            optional cumulative usage reported by the provider
     * @param finishReason
     *            optional provider-reported finish reason (e.g., {@code "stop"}, {@code "length"}, {@code "tool_use"})
     * @return a new {@code STREAM_END} chunk with an {@link StopReason#UNKNOWN} neutral reason
     */
    public static LlmStreamChunk streamEnd(int index, TokenUsage tokenUsage, Optional<String> finishReason) {
        return streamEnd(index, tokenUsage, finishReason, StopReason.UNKNOWN);
    }

    /**
     * Creates a stream-end chunk carrying both the raw provider finish string (for telemetry) and the provider-neutral
     * {@link StopReason} (for control-flow decisions).
     *
     * <p>
     * Provider streaming mappers map their SDK stop/finish value to a neutral {@link StopReason} before calling this,
     * so the raw {@code finishReason} string never drives a decision in {@code aimon-core} — it is surfaced only as an
     * opaque observability field.
     *
     * @param index
     *            zero-based chunk ordinal; must be non-negative
     * @param tokenUsage
     *            optional cumulative usage reported by the provider
     * @param finishReason
     *            optional raw provider-reported finish reason string (telemetry only)
     * @param stopReason
     *            the provider-neutral stop reason (must not be null; {@link StopReason#UNKNOWN} when absent)
     * @return a new {@code STREAM_END} chunk
     */
    public static LlmStreamChunk streamEnd(int index, TokenUsage tokenUsage, Optional<String> finishReason,
            StopReason stopReason) {
        return new Builder().kind(Kind.STREAM_END).index(index).tokenUsage(tokenUsage)
                .finishReason(Objects.requireNonNull(finishReason, "finishReason")).stopReason(stopReason)
                .timestamp(Instant.now()).build();
    }

    /**
     * @return a new builder with no fields set.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final Kind kind;
    private final String textDelta;
    private final ToolUse toolUse;
    private final TokenUsage tokenUsage;
    private final Optional<String> finishReason;
    private final StopReason stopReason;
    private final int index;
    private final Instant timestamp;

    private LlmStreamChunk(Builder builder) {
        this.kind = Objects.requireNonNull(builder.kind, "kind");
        if (builder.index < 0) {
            throw new IllegalArgumentException("index must be non-negative, got: " + builder.index);
        }
        this.index = builder.index;
        this.timestamp = Objects.requireNonNullElseGet(builder.timestamp, Instant::now);
        this.finishReason = builder.finishReason == null ? Optional.empty() : builder.finishReason;
        this.stopReason = builder.stopReason == null ? StopReason.UNKNOWN : builder.stopReason;
        this.tokenUsage = builder.tokenUsage;
        this.textDelta = builder.textDelta;
        this.toolUse = builder.toolUse;

        switch (this.kind) {
            case TEXT_DELTA -> {
                if (this.textDelta == null || this.textDelta.isEmpty()) {
                    throw new IllegalArgumentException("TEXT_DELTA chunk requires non-empty textDelta");
                }
                if (this.toolUse != null) {
                    throw new IllegalArgumentException("TEXT_DELTA chunk must not carry a toolUse");
                }
            }
            case TOOL_USE_READY -> {
                if (this.toolUse == null) {
                    throw new IllegalArgumentException("TOOL_USE_READY chunk requires a toolUse");
                }
                if (this.textDelta != null) {
                    throw new IllegalArgumentException("TOOL_USE_READY chunk must not carry textDelta");
                }
            }
            case STREAM_END -> {
                if (this.textDelta != null) {
                    throw new IllegalArgumentException("STREAM_END chunk must not carry textDelta");
                }
                if (this.toolUse != null) {
                    throw new IllegalArgumentException("STREAM_END chunk must not carry a toolUse");
                }
            }
            default -> throw new IllegalStateException("Unhandled chunk kind: " + this.kind);
        }
    }

    public Kind getKind() {
        return kind;
    }

    public int getIndex() {
        return index;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * @return the text delta for {@link Kind#TEXT_DELTA} chunks; empty for the other kinds.
     */
    public Optional<String> getTextDelta() {
        return Optional.ofNullable(textDelta);
    }

    /**
     * @return the completed tool use for {@link Kind#TOOL_USE_READY} chunks; empty for the other kinds.
     */
    public Optional<ToolUse> getToolUse() {
        return Optional.ofNullable(toolUse);
    }

    /**
     * @return the cumulative token usage reported at stream end, if any.
     */
    public Optional<TokenUsage> getTokenUsage() {
        return Optional.ofNullable(tokenUsage);
    }

    /**
     * @return the provider finish reason at stream end, if any.
     */
    public Optional<String> getFinishReason() {
        return finishReason;
    }

    /**
     * @return the provider-neutral stop reason at stream end; empty when {@link StopReason#UNKNOWN} (no reason reported
     *         or a non-terminal chunk). Use this — never {@link #getFinishReason()} — for control-flow decisions.
     */
    public Optional<StopReason> getStopReason() {
        return stopReason == StopReason.UNKNOWN ? Optional.empty() : Optional.of(stopReason);
    }

    @Override
    public String toString() {
        return switch (kind) {
            case TEXT_DELTA -> "LlmStreamChunk{TEXT_DELTA, index=" + index + ", len=" + textDelta.length() + '}';
            case TOOL_USE_READY -> "LlmStreamChunk{TOOL_USE_READY, index=" + index + ", tool=" + toolUse.getName()
                    + ", id=" + toolUse.getId() + '}';
            case STREAM_END -> "LlmStreamChunk{STREAM_END, index=" + index + ", finish=" + finishReason.orElse("-")
                    + ", usage=" + (tokenUsage == null ? "-" : Integer.toString(tokenUsage.getTotalTokens())) + '}';
        };
    }

    /**
     * Builder for {@link LlmStreamChunk}.
     */
    public static final class Builder {
        private Kind kind;
        private String textDelta;
        private ToolUse toolUse;
        private TokenUsage tokenUsage;
        private Optional<String> finishReason;
        private StopReason stopReason;
        private int index = -1;
        private Instant timestamp;

        private Builder() {
        }

        public Builder kind(Kind kind) {
            this.kind = kind;
            return this;
        }

        public Builder textDelta(String textDelta) {
            this.textDelta = textDelta;
            return this;
        }

        public Builder toolUse(ToolUse toolUse) {
            this.toolUse = toolUse;
            return this;
        }

        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            return this;
        }

        public Builder finishReason(Optional<String> finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder stopReason(StopReason stopReason) {
            this.stopReason = stopReason;
            return this;
        }

        public Builder index(int index) {
            this.index = index;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public LlmStreamChunk build() {
            return new LlmStreamChunk(this);
        }
    }
}
