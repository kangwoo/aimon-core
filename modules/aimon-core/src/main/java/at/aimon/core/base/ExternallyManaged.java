package at.aimon.core.base;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the annotated reference is <b>not owned</b> by the enclosing class.
 *
 * <p>
 * The enclosing class MUST NOT close, dispose, or otherwise manage the lifecycle of an externally managed dependency.
 * Ownership and lifecycle management belong to the component that created and injected the reference.
 *
 * <p>
 * This annotation is for documentation purposes only and carries no runtime overhead.
 *
 * <p>
 * Example:
 *
 * <pre>
 * {
 *     &#64;code
 *     public class RoutineExecutor {
 *         &#64;ExternallyManaged
 *         private final AgentRuntimeRegistry agentRuntimeRegistry;
 *
 *         public RoutineExecutor(&#64;ExternallyManaged AgentRuntimeRegistry agentRuntimeRegistry) {
 *             this.agentRuntimeRegistry = agentRuntimeRegistry;
 *         }
 *         // MUST NOT call agentRuntimeRegistry.close() in shutdown()
 *     }
 * }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.PARAMETER})
public @interface ExternallyManaged {
}
