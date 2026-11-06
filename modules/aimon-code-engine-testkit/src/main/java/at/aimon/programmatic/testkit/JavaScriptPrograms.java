package at.aimon.programmatic.testkit;

/** The contract programs in JavaScript, for an engine whose language is {@code javascript}. */
public final class JavaScriptPrograms implements EnginePrograms {

    @Override
    public String printText(String text) {
        return "console.log('" + text + "');";
    }

    @Override
    public String logText(String text) {
        return "console.error('" + text + "');";
    }

    @Override
    public String callAndPrint(String tool, String value) {
        return "const r = await tools." + tool + "({ value: '" + value + "' });\nconsole.log(r);";
    }

    @Override
    public String callAndCatch(String tool) {
        return "try { await tools." + tool + "({}); console.log('no error'); }\n"
                + "catch (e) { console.log('caught: ' + e.message); }";
    }

    @Override
    public String fanOut(String tool, int count) {
        return "const calls = [];\nfor (let i = 0; i < " + count + "; i++) { calls.push(tools." + tool + "({ i })); }\n"
                + "const settled = await Promise.allSettled(calls);\n"
                + "console.log('ok=' + settled.filter(s => s.status === 'fulfilled').length);";
    }

    @Override
    public String callSequentially(String tool, int count) {
        return "for (let i = 0; i < " + count + "; i++) { try { await tools." + tool
                + "({ i }); } catch (e) { } }\nconsole.log('done');";
    }

    @Override
    public String raise(String message) {
        return "throw new Error('" + message + "');";
    }

    @Override
    public String spinForever() {
        return "let x = 0; while (true) { x++; }";
    }

    @Override
    public String printForever() {
        return "while (true) { console.log('xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'); }";
    }

    @Override
    public String callAndWait(String tool) {
        return "await tools." + tool + "({});\nconsole.log('returned');";
    }

    @Override
    public String syntaxError() {
        return "this is not ( javascript";
    }
}
