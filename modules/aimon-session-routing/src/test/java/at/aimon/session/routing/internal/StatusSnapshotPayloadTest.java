package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.LiveSessionStatus;
import at.aimon.core.agent.session.LiveSessionStatus.Phase;
import at.aimon.core.agent.session.LiveSessionStatus.TurnProgress;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.llm.TokenUsage;
import at.aimon.session.routing.internal.StatusSnapshotPayload.Decoded;

/**
 * Round-trip contract for {@link StatusSnapshotPayload}. The decisive test is
 * {@link #survivesJsonCodecNumberNormalization()}: it proves the flat-map snapshot survives the int/long narrowing that
 * the real Redis/Mongo signal codecs apply, which a naive typed-object-over-the-bus design (the current cross-node
 * {@code EVENT} relay) silently fails.
 */
@DisplayName("StatusSnapshotPayload flat-map round-trip")
class StatusSnapshotPayloadTest {

    private static final SessionId CONV = SessionId.of("conv-status-1");

    @Test
    @DisplayName("a running snapshot with live turn progress round-trips field-for-field")
    void roundTripsRunningStatusDirectly() {
        final LiveSessionStatus original = runningStatus();
        final long seq = 5_000_000_000L; // > Integer.MAX_VALUE: exercises the long path
        final Instant observedAt = Instant.ofEpochMilli(1_700_000_000_000L);

        final Map<String, Object> payload = StatusSnapshotPayload.toPayload(original, seq, observedAt);
        final Optional<Decoded> decoded = StatusSnapshotPayload.fromPayload(CONV, payload);

        assertThat(decoded).isPresent();
        assertSameStatus(original, decoded.get().status());
        assertThat(decoded.get().seq()).isEqualTo(seq);
        assertThat(decoded.get().observedAt()).isEqualTo(observedAt);
    }

    @Test
    @DisplayName("snapshot survives the JSON codec's int/long number normalization (cross-bus contract)")
    void survivesJsonCodecNumberNormalization() {
        final LiveSessionStatus original = runningStatus();
        final long seq = 7L; // small: a JSON string round-trip narrows this Long back to Integer
        final Instant observedAt = Instant.ofEpochMilli(1_700_000_000_000L);

        final Map<String, Object> payload = StatusSnapshotPayload.toPayload(original, seq, observedAt);
        // Reproduce exactly what SessionSignalCodec.decode() does to numbers after a JSON string round-trip.
        @SuppressWarnings("unchecked")
        final Map<String, Object> normalized = (Map<String, Object>) normalizeLikeJsonCodec(payload);

        final Optional<Decoded> decoded = StatusSnapshotPayload.fromPayload(CONV, normalized);

        assertThat(decoded).isPresent();
        assertSameStatus(original, decoded.get().status());
        assertThat(decoded.get().seq()).isEqualTo(seq);
        assertThat(decoded.get().observedAt()).isEqualTo(observedAt);
    }

    @Test
    @DisplayName("an idle snapshot without a live turn round-trips with empty turn progress")
    void roundTripsIdleStatusWithoutTurnProgress() {
        final LiveSessionStatus original = LiveSessionStatus.builder().sessionId(CONV).phase(Phase.IDLE)
                .interruptible(false).queueDepth(0).sessionTotals(SessionTotals.empty()).build();

        final Decoded decoded = StatusSnapshotPayload
                .fromPayload(CONV, StatusSnapshotPayload.toPayload(original, 1L, Instant.ofEpochMilli(1L)))
                .orElseThrow();

        assertSameStatus(original, decoded.status());
        assertThat(decoded.status().getTurnProgress()).isEmpty();
        assertThat(decoded.status().getSessionTotals()).isEqualTo(SessionTotals.empty());
    }

    @Test
    @DisplayName("an unlimited turn budget round-trips to an unlimited budget (no spurious limits)")
    void roundTripsUnlimitedBudget() {
        final TurnProgress turn = TurnProgress.of(2, TokenUsage.of(10, 5, 15), Duration.ofMillis(250),
                ExecutionBudget.unlimited());
        final LiveSessionStatus original = LiveSessionStatus.builder().sessionId(CONV).phase(Phase.RUNNING)
                .interruptible(true).queueDepth(0).turnProgress(turn).sessionTotals(SessionTotals.empty()).build();

        final Decoded decoded = StatusSnapshotPayload
                .fromPayload(CONV, StatusSnapshotPayload.toPayload(original, 1L, Instant.ofEpochMilli(1L)))
                .orElseThrow();

        final TurnProgress rebuilt = decoded.status().getTurnProgress().orElseThrow();
        assertThat(rebuilt.getBudget().isUnlimited()).isTrue();
        assertThat(rebuilt.getBudget()).isEqualTo(ExecutionBudget.unlimited());
    }

    @Test
    @DisplayName("a closed snapshot round-trips its phase")
    void roundTripsClosedPhase() {
        final LiveSessionStatus original = LiveSessionStatus.builder().sessionId(CONV).phase(Phase.CLOSED)
                .interruptible(false).queueDepth(3).sessionTotals(SessionTotals.of(4, 9, TokenUsage.of(100, 50, 150)))
                .build();

        final Decoded decoded = StatusSnapshotPayload
                .fromPayload(CONV, StatusSnapshotPayload.toPayload(original, 1L, Instant.ofEpochMilli(1L)))
                .orElseThrow();

        assertSameStatus(original, decoded.status());
    }

    @Test
    @DisplayName("null and malformed payloads yield empty rather than throwing")
    void returnsEmptyForMalformedPayload() {
        assertThat(StatusSnapshotPayload.fromPayload(CONV, null)).isEmpty();

        final Map<String, Object> missingPhase = new LinkedHashMap<>();
        missingPhase.put(StatusSnapshotPayload.KEY_INTERRUPTIBLE, false);
        assertThat(StatusSnapshotPayload.fromPayload(CONV, missingPhase)).isEmpty();

        final Map<String, Object> badPhase = StatusSnapshotPayload.toPayload(LiveSessionStatus.builder().sessionId(CONV)
                .phase(Phase.IDLE).sessionTotals(SessionTotals.empty()).build(), 1L, Instant.ofEpochMilli(1L));
        badPhase.put(StatusSnapshotPayload.KEY_PHASE, "NOT_A_PHASE");
        assertThat(StatusSnapshotPayload.fromPayload(CONV, badPhase)).isEmpty();
    }

    private static LiveSessionStatus runningStatus() {
        final TurnProgress turn = TurnProgress.of(3, TokenUsage.of(1200, 340, 1540), Duration.ofMillis(8_500),
                ExecutionBudget.builder().maxIterations(20).maxTokens(100_000)
                        .maxWallClockDuration(Duration.ofMinutes(5)).build());
        return LiveSessionStatus.builder().sessionId(CONV).phase(Phase.RUNNING).interruptible(true).queueDepth(2)
                .turnProgress(turn).sessionTotals(SessionTotals.of(7, 21, TokenUsage.of(9000, 3000, 12000))).build();
    }

    private static void assertSameStatus(LiveSessionStatus expected, LiveSessionStatus actual) {
        assertThat(actual.getSessionId()).isEqualTo(expected.getSessionId());
        assertThat(actual.getPhase()).isEqualTo(expected.getPhase());
        assertThat(actual.isInterruptible()).isEqualTo(expected.isInterruptible());
        assertThat(actual.getQueueDepth()).isEqualTo(expected.getQueueDepth());
        assertThat(actual.getSessionTotals()).isEqualTo(expected.getSessionTotals());
        assertThat(actual.getTurnProgress().isPresent()).isEqualTo(expected.getTurnProgress().isPresent());
        if (expected.getTurnProgress().isPresent()) {
            final TurnProgress expectedTurn = expected.getTurnProgress().get();
            final TurnProgress actualTurn = actual.getTurnProgress().get();
            assertThat(actualTurn.getIterations()).isEqualTo(expectedTurn.getIterations());
            assertThat(actualTurn.getTokenUsage()).isEqualTo(expectedTurn.getTokenUsage());
            assertThat(actualTurn.getElapsed()).isEqualTo(expectedTurn.getElapsed());
            assertThat(actualTurn.getBudget()).isEqualTo(expectedTurn.getBudget());
        }
    }

    /**
     * Mirrors the number normalization a JSON string round-trip applies (as in {@code SessionSignalCodec}): an
     * in-{@code int}-range {@code long} comes back as {@code Integer}; everything else is unchanged. Used to prove
     * {@link StatusSnapshotPayload#fromPayload} tolerates the boxed types the real bus delivers, without adding a
     * Jackson dependency to this module's tests.
     */
    private static Object normalizeLikeJsonCodec(Object value) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put((String) k, normalizeLikeJsonCodec(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            final List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(normalizeLikeJsonCodec(v)));
            return out;
        }
        if (value instanceof Long longValue && longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
            return longValue.intValue();
        }
        return value;
    }
}
