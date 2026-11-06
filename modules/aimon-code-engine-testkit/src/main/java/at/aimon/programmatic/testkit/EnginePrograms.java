package at.aimon.programmatic.testkit;

/**
 * The same programs written in one engine's language, for {@link AbstractCodeExecutionEngineContractTest}. Each method
 * returns the source of one program; the contract states what the program must observe, so two languages are held to
 * the same meaning by writing each program once per language.
 */
public interface EnginePrograms {

    /**
     * Writes {@code text} to standard output.
     *
     * @param text
     *            plain text without quotes or backslashes
     * @return the program
     */
    String printText(String text);

    /**
     * Writes {@code text} to standard error.
     *
     * @param text
     *            plain text without quotes or backslashes
     * @return the program
     */
    String logText(String text);

    /**
     * Calls {@code tool} with {@code {"value": value}} and prints the string result.
     *
     * @param tool
     *            a tool name that is an identifier
     * @param value
     *            plain text without quotes or backslashes
     * @return the program
     */
    String callAndPrint(String tool, String value);

    /**
     * Calls {@code tool} with {@code {}}; when the call fails, prints {@code "caught: "} followed by the error's
     * message.
     *
     * @param tool
     *            a tool name that is an identifier
     * @return the program
     */
    String callAndCatch(String tool);

    /**
     * Calls {@code tool} {@code count} times concurrently with {@code {"i": <index>}}, waits for all of them, and
     * prints how many succeeded as {@code "ok=<n>"}.
     *
     * @param tool
     *            a tool name that is an identifier
     * @param count
     *            how many calls
     * @return the program
     */
    String fanOut(String tool, int count);

    /**
     * Calls {@code tool} with {@code {"i": <index>}} {@code count} times, one after another, ignoring failures.
     *
     * @param tool
     *            a tool name that is an identifier
     * @param count
     *            how many calls
     * @return the program
     */
    String callSequentially(String tool, int count);

    /**
     * Raises an error whose message is {@code message}.
     *
     * @param message
     *            plain text without quotes or backslashes
     * @return the program
     */
    String raise(String message);

    /**
     * Loops forever without calling anything.
     *
     * @return the program
     */
    String spinForever();

    /**
     * Writes to standard output in a loop that never ends by itself.
     *
     * @return the program
     */
    String printForever();

    /**
     * Calls {@code tool} and waits for its result (which the test never delivers before it cancels).
     *
     * @param tool
     *            a tool name that is an identifier
     * @return the program
     */
    String callAndWait(String tool);

    /**
     * Does not parse.
     *
     * @return the program
     */
    String syntaxError();
}
