package at.aimon.core.tools.code;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionEngine;

/**
 * Renders the function declarations of the callable set into {@code ExecuteCode}'s description
 * ({@code docs/design/tool/programmatic-tool-calling.md} §6.3), from each tool's input schema and description.
 *
 * <p>
 * Deterministic: tools are sorted by name and properties keep the schema's order, so the description — and the prompt
 * cache built on it — only changes when the callable set does. A tool that is also offered directly gets a one-line
 * signature that points at the direct tool's schema, so its schema is not paid for twice; a code-only tool gets its
 * arguments spelled out. A name that is not an identifier ({@code mcp__server__tool-name}) is called by index; in
 * Python, so is a name the runtime cannot bind as a function ({@link #PYTHON_RESERVED}, a keyword, a dunder name).
 *
 * <p>
 * Stateless.
 */
public final class ToolApiRenderer {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern PY_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int MAX_DESCRIPTION_CHARS = 400;

    /**
     * The names the Python runtime binds as globals of its own, so a tool of one of these names is not bound as a
     * function and is rendered in the {@code call_tool("name", args)} form instead. The other half is the
     * {@code program_globals} literal in {@code aimon_ptc.py} — its keys that do not start with {@code __};
     * {@code ToolApiRendererTest} holds the two together. {@code __name__} and {@code __builtins__} are covered by a
     * rule instead: every name that starts with {@code __} is rendered through {@code call_tool}.
     *
     * <p>
     * A tool named after a Python builtin ({@code print}, {@code len}, {@code open}) is still bound — the runtime's
     * rule is "not a keyword and not one of its own globals" — and so shadows the builtin inside the program. That is
     * the runtime's long-standing behaviour, not changed here.
     */
    public static final Set<String> PYTHON_RESERVED = Set.of("ToolError", "call_tool", "asyncio", "json");

    /** Python's hard keywords ({@code keyword.kwlist}), which cannot name a function. */
    private static final Set<String> PYTHON_KEYWORDS = Set.of("False", "None", "True", "and", "as", "assert", "async",
            "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
            "while", "with", "yield");

    private ToolApiRenderer() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Renders the declarations of {@code tools} for {@code language}.
     *
     * @param language
     *            {@link CodeExecutionEngine#JAVASCRIPT} or {@link CodeExecutionEngine#PYTHON} (must not be null)
     * @param tools
     *            the callable set's definitions (must not be null)
     * @param directlyOffered
     *            the names of the tools that are also in the direct offer (must not be null)
     * @return the declarations, one block per tool (never null)
     */
    public static String render(String language, List<ToolDefinition> tools, Set<String> directlyOffered) {
        Objects.requireNonNull(language, "language cannot be null");
        Objects.requireNonNull(tools, "tools cannot be null");
        Objects.requireNonNull(directlyOffered, "directlyOffered cannot be null");
        final List<ToolDefinition> sorted = new ArrayList<>(tools);
        sorted.sort(Comparator.comparing(ToolDefinition::getName));
        final StringBuilder out = new StringBuilder();
        for (ToolDefinition tool : sorted) {
            final boolean direct = directlyOffered.contains(tool.getName());
            if (CodeExecutionEngine.PYTHON.equals(language)) {
                renderPython(out, tool, direct);
            } else {
                renderJavaScript(out, tool, direct);
            }
        }
        return out.toString();
    }

    private static void renderJavaScript(StringBuilder out, ToolDefinition tool, boolean direct) {
        final String name = tool.getName();
        final String callee = IDENTIFIER.matcher(name).matches() ? "tools." + name : "tools[\"" + name + "\"]";
        out.append("/** ").append(shortDescription(tool));
        if (direct) {
            out.append(" Arguments are those of the direct tool `").append(name).append("`.");
        }
        out.append(" */\n").append(callee).append("(args: ");
        out.append(direct ? "object" : jsObjectType(tool.getInputSchema()));
        out.append("): Promise<string>\n");
    }

    /**
     * Whether the description renders {@code name} as a Python function the program can call directly: an ASCII
     * identifier that is not a keyword, not one of the runtime's own globals and not a dunder name. This follows the
     * runtime's {@code name.isidentifier() and not keyword.iskeyword(name) and name not in program_globals}, with one
     * difference that fails safe: {@code str.isidentifier()} also accepts non-ASCII identifiers, which this rule does
     * not. Such a tool is rendered in the {@code call_tool("name", args)} form while the runtime still binds it as a
     * function — the rendered form works either way.
     */
    static boolean pythonBindable(String name) {
        return PY_IDENTIFIER.matcher(name).matches() && !PYTHON_KEYWORDS.contains(name)
                && !PYTHON_RESERVED.contains(name) && !name.startsWith("__");
    }

    private static void renderPython(StringBuilder out, ToolDefinition tool, boolean direct) {
        final String name = tool.getName();
        if (!pythonBindable(name)) {
            out.append("# await call_tool(\"").append(name).append("\", args) -> str — ").append(shortDescription(tool))
                    .append('\n');
            return;
        }
        out.append("async def ").append(name).append("(args: dict) -> str:\n");
        out.append("    \"\"\"").append(shortDescription(tool));
        if (direct) {
            out.append(" Arguments are those of the direct tool `").append(name).append("`.\"\"\"\n");
            return;
        }
        final Map<String, Object> properties = properties(tool.getInputSchema());
        if (!properties.isEmpty()) {
            out.append("\n\n    args:\n");
            final List<String> required = required(tool.getInputSchema());
            for (Map.Entry<String, Object> property : properties.entrySet()) {
                final Map<String, Object> schema = asMap(property.getValue());
                out.append("      ").append(property.getKey()).append(" (").append(pyType(schema));
                if (!required.contains(property.getKey())) {
                    out.append(", optional");
                }
                out.append(")");
                final Object description = schema.get("description");
                if (description instanceof String text && !text.isBlank()) {
                    out.append(": ").append(oneLine(text));
                }
                out.append('\n');
            }
            out.append("    ");
        }
        out.append("\"\"\"\n");
    }

    private static String jsObjectType(Map<String, Object> schema) {
        final Map<String, Object> properties = properties(schema);
        if (properties.isEmpty()) {
            return "{}";
        }
        final List<String> required = required(schema);
        final StringBuilder type = new StringBuilder("{ ");
        boolean first = true;
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            if (!first) {
                type.append("; ");
            }
            first = false;
            final String key = property.getKey();
            type.append(IDENTIFIER.matcher(key).matches() ? key : "\"" + key + "\"");
            if (!required.contains(key)) {
                type.append('?');
            }
            type.append(": ").append(jsType(asMap(property.getValue())));
        }
        return type.append(" }").toString();
    }

    private static String jsType(Map<String, Object> schema) {
        final Object enumValues = schema.get("enum");
        if (enumValues instanceof List<?> values && !values.isEmpty()) {
            final List<String> literals = new ArrayList<>();
            for (Object value : values) {
                literals.add(value instanceof String text ? "\"" + text + "\"" : String.valueOf(value));
            }
            return String.join(" | ", literals);
        }
        final Object type = schema.get("type");
        if (!(type instanceof String name)) {
            return "unknown";
        }
        return switch (name) {
            case "string" -> "string";
            case "integer", "number" -> "number";
            case "boolean" -> "boolean";
            case "array" -> {
                final Map<String, Object> items = asMap(schema.get("items"));
                final String itemType = items.isEmpty() ? "unknown" : jsType(items);
                yield itemType.contains(" ") ? "(" + itemType + ")[]" : itemType + "[]";
            }
            case "object" -> properties(schema).isEmpty() ? "object" : jsObjectType(schema);
            default -> "unknown";
        };
    }

    private static String pyType(Map<String, Object> schema) {
        final Object type = schema.get("type");
        if (!(type instanceof String name)) {
            return "any";
        }
        return switch (name) {
            case "string" -> "str";
            case "integer" -> "int";
            case "number" -> "float";
            case "boolean" -> "bool";
            case "array" -> "list";
            case "object" -> "dict";
            default -> "any";
        };
    }

    private static String shortDescription(ToolDefinition tool) {
        final String description = tool.getDescription() == null ? "" : oneLine(tool.getDescription());
        return description.length() <= MAX_DESCRIPTION_CHARS
                ? description
                : description.substring(0, MAX_DESCRIPTION_CHARS) + "…";
    }

    private static String oneLine(String text) {
        return text.replace("*/", "* /").replaceAll("\\s+", " ").trim();
    }

    private static Map<String, Object> properties(Map<String, Object> schema) {
        return schema == null ? Map.of() : asMap(schema.get("properties"));
    }

    private static List<String> required(Map<String, Object> schema) {
        final Object required = schema == null ? null : schema.get("required");
        if (!(required instanceof List<?> list)) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        for (Object element : list) {
            names.add(String.valueOf(element));
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
