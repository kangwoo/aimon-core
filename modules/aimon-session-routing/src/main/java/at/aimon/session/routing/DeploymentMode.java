package at.aimon.session.routing;

/**
 * Deployment topology declared at builder time.
 *
 * <p>
 * Drives the SPI default-injection policy (design §11.2):
 *
 * <ul>
 * <li>{@link #SINGLE_NODE} — missing SPIs default to in-memory implementations. Suitable for single-process
 * deployments and tests.
 * <li>{@link #DISTRIBUTED} — every SPI must be explicitly injected. The builder fails fast if any is missing or if
 * {@code nodeId} is unset, preventing a silent split-brain when an in-memory SPI is accidentally used in a
 * multi-node deployment.
 * </ul>
 */
public enum DeploymentMode {
    SINGLE_NODE, DISTRIBUTED
}
