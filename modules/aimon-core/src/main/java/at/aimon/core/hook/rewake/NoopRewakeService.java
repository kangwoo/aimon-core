package at.aimon.core.hook.rewake;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backing implementation for {@link RewakeService#NOOP}. Logs a warning when a hook attempts to schedule a rewake
 * without a real service wired up, and otherwise returns empty results. Package-private — call sites should reference
 * the {@link RewakeService#NOOP} constant instead of constructing this directly.
 */
final class NoopRewakeService implements RewakeService {

    private static final Logger log = LoggerFactory.getLogger(NoopRewakeService.class);

    NoopRewakeService() {
    }

    @Override
    public void schedule(RewakeEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope cannot be null");
        log.warn("Async-rewake requested but no RewakeService is wired (envelopeId={}, hookId={}, reason={}). "
                + "Spec will be silently dropped — wire DefaultRewakeService or the Quartz-backed impl at bootstrap.",
                envelope.getEnvelopeId(), envelope.getOriginatingHookId(), envelope.getReason());
    }

    @Override
    public boolean cancel(String envelopeId) {
        Objects.requireNonNull(envelopeId, "envelopeId cannot be null");
        return false;
    }

    @Override
    public int cancelByOriginatingHookId(String originatingHookId) {
        Objects.requireNonNull(originatingHookId, "originatingHookId cannot be null");
        return 0;
    }

    @Override
    public int resolve(String eventType, String eventKey, Map<String, String> payload) {
        Objects.requireNonNull(eventType, "eventType cannot be null");
        Objects.requireNonNull(eventKey, "eventKey cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        return 0;
    }

    @Override
    public List<RewakeEnvelope> listPending() {
        return List.of();
    }
}
