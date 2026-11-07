package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionEngine;

/**
 * The Python half of the renderer agrees with the runtime about which tool names become functions
 * (ptc-leftovers-core §1.5): a name the runtime binds as a global of its own, a keyword or a dunder name is rendered in
 * the {@code call_tool("name", args)} form, so the description never advertises a function the program cannot call.
 */
@DisplayName("ToolApiRenderer — Python names the runtime cannot bind go through call_tool")
class ToolApiRendererTest {

    private static final String RUNTIME = "at/aimon/core/tools/code/shell/aimon_ptc.py";

    @Test
    @DisplayName("json, asyncio, ToolError, call_tool, a keyword and a dunder name render via call_tool")
    void reservedNamesViaCallTool() {
        final List<String> names = List.of("json", "asyncio", "ToolError", "call_tool", "class", "__init__");
        final List<ToolDefinition> tools = names.stream()
                .map(name -> ToolDefinition.of(name, "Tool " + name + ".", Map.of("type", "object"))).toList();
        final String rendered = ToolApiRenderer.render(CodeExecutionEngine.PYTHON, tools, Set.of());
        for (String name : names) {
            assertThat(rendered).contains("# await call_tool(\"" + name + "\", args)")
                    .doesNotContain("async def " + name + "(");
        }
    }

    @Test
    @DisplayName("an ordinary identifier, a soft keyword and a builtin's name still render as functions")
    void ordinaryNamesAsFunctions() {
        final String rendered = ToolApiRenderer.render(CodeExecutionEngine.PYTHON,
                List.of(ToolDefinition.of("Read", "Read.", Map.of("type", "object")),
                        ToolDefinition.of("match", "Match.", Map.of("type", "object")),
                        ToolDefinition.of("print", "Print.", Map.of("type", "object"))),
                Set.of());
        assertThat(rendered).contains("async def Read(args: dict) -> str:")
                .contains("async def match(args: dict) -> str:").contains("async def print(args: dict) -> str:");
    }

    @Test
    @DisplayName("PYTHON_RESERVED is exactly the non-dunder keys of the runtime's program_globals literal")
    void reservedMatchesRuntime() throws IOException {
        final String runtime;
        try (InputStream in = ToolApiRendererTest.class.getClassLoader().getResourceAsStream(RUNTIME)) {
            assertThat(in).as(RUNTIME).isNotNull();
            runtime = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final Matcher literal = Pattern.compile("program_globals = \\{(.*?)\\n    \\}", Pattern.DOTALL)
                .matcher(runtime);
        assertThat(literal.find()).as("the program_globals literal in aimon_ptc.py").isTrue();
        final Set<String> keys = new HashSet<>();
        final Matcher key = Pattern.compile("^\\s*\"([^\"]+)\":", Pattern.MULTILINE).matcher(literal.group(1));
        while (key.find()) {
            keys.add(key.group(1));
        }
        assertThat(keys).contains("__name__", "__builtins__");
        keys.removeIf(name -> name.startsWith("__"));
        assertThat(keys).isEqualTo(ToolApiRenderer.PYTHON_RESERVED);
    }
}
