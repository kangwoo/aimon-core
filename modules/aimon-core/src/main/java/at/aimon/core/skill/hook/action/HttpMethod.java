package at.aimon.core.skill.hook.action;

/**
 * HTTP method allowed for {@link HttpAction}.
 *
 * <p>
 * The set is intentionally narrow: declarative hooks should never need TRACE / CONNECT / OPTIONS in practice. Add new
 * members here when a concrete use-case appears.
 */
public enum HttpMethod {
    GET, POST, PUT, PATCH, DELETE
}
