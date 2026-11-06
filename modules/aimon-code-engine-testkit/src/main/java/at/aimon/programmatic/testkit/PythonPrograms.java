package at.aimon.programmatic.testkit;

/** The contract programs in Python, for an engine whose language is {@code python}. */
public final class PythonPrograms implements EnginePrograms {

    @Override
    public String printText(String text) {
        return "print('" + text + "')";
    }

    @Override
    public String logText(String text) {
        return "import sys\nprint('" + text + "', file=sys.stderr)";
    }

    @Override
    public String callAndPrint(String tool, String value) {
        return "r = await " + tool + "({'value': '" + value + "'})\nprint(r)";
    }

    @Override
    public String callAndCatch(String tool) {
        return "try:\n    await " + tool + "({})\n    print('no error')\nexcept ToolError as e:\n"
                + "    print('caught: ' + str(e))";
    }

    @Override
    public String fanOut(String tool, int count) {
        return "import asyncio\nresults = await asyncio.gather(*[" + tool + "({'i': i}) for i in range(" + count
                + ")], return_exceptions=True)\n"
                + "print('ok=' + str(sum(1 for r in results if not isinstance(r, BaseException))))";
    }

    @Override
    public String callSequentially(String tool, int count) {
        return "for i in range(" + count + "):\n    try:\n        await " + tool + "({'i': i})\n"
                + "    except ToolError:\n        pass\nprint('done')";
    }

    @Override
    public String raise(String message) {
        return "raise RuntimeError('" + message + "')";
    }

    @Override
    public String spinForever() {
        return "x = 0\nwhile True:\n    x += 1";
    }

    @Override
    public String printForever() {
        return "while True:\n    print('xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx')";
    }

    @Override
    public String callAndWait(String tool) {
        return "await " + tool + "({})\nprint('returned')";
    }

    @Override
    public String syntaxError() {
        return "this is not ( python";
    }
}
