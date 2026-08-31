package at.aimon.core.agent.tool.generic;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Carries the schema metadata that cannot be read off a record component's type.
 *
 * <p>
 * {@link ToolSchemaGenerator} derives four things about a parameter. Two of them come from the type system and two
 * cannot:
 *
 * <ul>
 * <li><b>Derived</b> — the JSON Schema {@code type}, and the structure of nested and repeated values
 * <li><b>Declared here</b> — the {@code description}, the {@code enum} constraint, whether the parameter is
 * {@link #required()}, and the wire {@link #name()} when it differs from the component name
 * </ul>
 *
 * <p>
 * Do not read the split as "most of the schema is automatic". <b>What steers a model is the description</b>, and that
 * is the half a compiler cannot supply. This annotation moves it next to the field it describes; it does not remove
 * the work of writing it.
 *
 * <h2>Names are declared, not converted</h2>
 *
 * <p>
 * Parameter names in this codebase are snake_case ({@code file_path}, {@code output_mode}, {@code run_in_background})
 * while Java component names are camelCase, so {@link #name()} is the common case rather than the exception. The
 * generator deliberately does <b>not</b> convert case automatically: a wire name is part of the contract the model
 * sees, and a tool author should be able to read it off the source rather than reconstruct a conversion rule. Some
 * names are not expressible as identifiers at all — {@code Grep} declares {@code -i}, {@code -A}, {@code -B}.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>
 * {@code
 * public record GrepInput(
 *         &#64;ToolParam(required = true, description = "The regular expression pattern to search for")
 *         String pattern,
 *         &#64;ToolParam(name = "output_mode", description = "Output mode",
 *                    allowed = {"content", "files_with_matches", "count"})
 *         String outputMode,
 *         &#64;ToolParam(name = "-i", description = "Case insensitive search")
 *         Boolean caseInsensitive) {
 * }
 * }
 * </pre>
 *
 * @see ToolSchemaGenerator
 * @see GenericTool
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface ToolParam {

    /**
     * The parameter name as the model sees it, when it differs from the record component name.
     *
     * <p>
     * Empty means "use the component name verbatim". No case conversion is applied in either case.
     *
     * @return the wire name, or empty to use the component name
     */
    String name() default "";

    /**
     * What this parameter is for, written for the model.
     *
     * <p>
     * Omitting it produces a schema entry with a type and no explanation, which is legal and nearly useless. Treat it
     * as mandatory in practice.
     *
     * @return the description, or empty to declare none
     */
    String description() default "";

    /**
     * Whether the call is rejected when this parameter is absent.
     *
     * <p>
     * Defaults to optional. A primitive component is required regardless of what is declared here — it has no value
     * that could stand for "not supplied".
     *
     * @return true if the parameter must be present
     */
    boolean required() default false;

    /**
     * The closed set of values this parameter accepts, becoming the schema's {@code enum} constraint.
     *
     * <p>
     * Only needed when the closed set is spelled as strings. A component typed as a Java {@code enum} gets the same
     * constraint from its constants without declaring anything here.
     *
     * @return the allowed values in the order they should be shown, or empty for an unconstrained parameter
     */
    String[] allowed() default {};
}
