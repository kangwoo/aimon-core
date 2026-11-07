package at.aimon.core.tools.code;

import java.util.ArrayList;
import java.util.List;

import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;

/**
 * The description {@code ExecuteCode} is offered with ({@code docs/design/tool/programmatic-tool-calling.md} §6.3): the
 * usage guidance for the chosen language, then the function declarations of the callable set.
 *
 * <p>
 * Stateless and deterministic for a given language, callable set and limits.
 */
final class ExecuteCodeDescription {

    private ExecuteCodeDescription() {
        throw new AssertionError("This class should not be instantiated");
    }

    /** "JavaScript" / "Python" — the name a prompt or a description shows for a language. */
    static String displayName(String language) {
        return CodeExecutionEngine.PYTHON.equals(language) ? "Python" : "JavaScript";
    }

    static String render(String language, ExecuteCodePlan plan, CodeExecutionLimits limits) {
        final boolean python = CodeExecutionEngine.PYTHON.equals(language);
        final String print = python ? "print(...)" : "console.log(...)";
        final String stderr = python ? "stderr" : "console.error(...)";
        final String fanOut = python ? "asyncio.gather(..., return_exceptions=True)" : "Promise.allSettled([...])";
        final String call = python ? "await Grep({\"pattern\": \"x\"})" : "await tools.Grep({ pattern: \"x\" })";
        final StringBuilder out = new StringBuilder();
        out.append("Run a ").append(displayName(language))
                .append(" program whose function calls are tool calls. Use it when one task needs many tool calls "
                        + "whose intermediate results you do not need to read — a loop over many items, filtering a "
                        + "large result, aggregating. For one or two calls in sequence, call the tools directly "
                        + "instead: a program costs more there.\n\n");
        if (python) {
            // The functions are injected, and the program already runs inside an event loop: say both, because
            // real models otherwise guess `from functions import ...` and `asyncio.run(...)` (backlog TC-27).
            out.append("- The functions below are already defined as globals: call them directly and do not import "
                    + "them. Each is a coroutine that returns a string (").append(call)
                    .append("); the format of the string is in the function's description — parse it yourself, for "
                            + "example with json.loads, when it is JSON. A failed call raises ToolError, whose message "
                            + "is the tool's error text. The program already runs inside an event loop: use top-level "
                            + "await, and never call asyncio.run(). The "
                            + "arguments of one call must stay under 64 KiB as JSON; pass a larger input as a file "
                            + "path.\n");
        } else {
            out.append("- Each function returns a Promise of a string (").append(call)
                    .append("); the format of the string is in the function's description — parse it yourself, for "
                            + "example with JSON.parse, when it is JSON. A failed call rejects "
                            + "with an error whose message is the tool's error text.\n");
        }
        out.append("- Standard output is your observation: ").append(print).append(". ").append(stderr)
                .append(" goes to the log.");
        if (!python) {
            out.append(" The program runs as the body of an async function: top-level await and return are legal, "
                    + "and a returned value other than undefined is appended after the output.");
        }
        out.append('\n');
        out.append("- The observation keeps the first ").append(limits.getMaxObservationChars())
                .append(" characters of output and marks the rest as truncated; print only what you need.\n");
        out.append("- Calls that do not depend on each other can run in parallel: use ").append(fanOut).append(".\n");
        out.append("- A tool that changes state can be called at most ").append(limits.getMaxMutatingCallsPerTool())
                .append(" times per program; a program may make at most ").append(limits.getMaxNestedCalls())
                .append(" calls. A tool that needs approval must first be called directly.\n");
        out.append("- Never execute a tool's result as code.\n\n");
        out.append(
                python ? "Functions (already defined; do not import them):\n" : "Functions (on the `tools` object):\n");
        final List<ToolDefinition> definitions = new ArrayList<>();
        for (ToolDescriptor descriptor : plan.callable()) {
            definitions.add(descriptor.getDefinition());
        }
        out.append(ToolApiRenderer.render(language, definitions, plan.directlyOffered()));
        return out.toString();
    }
}
