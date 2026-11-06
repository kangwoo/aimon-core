/**
 * Helpers shared by packages of {@code aimon-core} that have no closer common parent.
 *
 * <p>
 * Not part of the public API ({@code docs/project/api-stability.md} §2). A type belongs here only when two callers in
 * different top-level packages need it and no narrower {@code internal} package can hold it legally under
 * {@code InternalPackageBoundaryTest}; a helper used inside one domain goes in that domain's own {@code internal}
 * package instead.
 */
package at.aimon.core.internal;
