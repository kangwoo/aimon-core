package at.aimon.core.agent.tool.schema;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the violation sentences that a model reads when its tool call did not match the tool's schema.
 *
 * <p>
 * <b>These strings are part of the agent's contract with the model, not developer diagnostics.</b> Two properties are
 * deliberate and should survive edits:
 *
 * <ul>
 * <li><b>Every sentence ends with {@value #NOT_EXECUTED}.</b> The most common wrong inference a model draws from a
 * tool error is that the call may have half-happened, and a model that suspects that will hesitate to retry a
 * {@code Write} or an {@code Edit}. Saying so outright is what makes the retry safe to take.
 * <li><b>A misspelled parameter is answered with the name it probably meant.</b> Typos are the single most frequent
 * mistake, and handing the name back fixes the call in one round trip instead of leaving the model to guess.
 * </ul>
 *
 * <p>
 * Shared by two layers on purpose: the executor's schema gate ({@link DefaultToolInputSchemaValidator}) and the
 * {@code GenericTool} parameter binding. They catch mismatches at different moments, and a model should not have to
 * learn two dialects to tell that they are the same complaint.
 *
 * <p>
 * <b>One caveat when a caller only logs these.</b> In {@link SchemaValidationMode#WARN} the tool <em>is</em> executed,
 * which makes the closing sentence false for that log line. The caller is responsible for saying so in the surrounding
 * log text; the sentences themselves stay written for the model, which is the only audience that acts on them.
 */
public final class ViolationMessages {

    /** The closing sentence appended to every violation. See the class javadoc for why it is not optional. */
    public static final String NOT_EXECUTED = "The tool was not executed.";

    /** Names further apart than this are not offered as a "did you mean" suggestion. */
    private static final int MAX_SUGGESTION_DISTANCE = 2;

    private ViolationMessages() {
        throw new UnsupportedOperationException("Utility class must not be instantiated");
    }

    /**
     * Builds the sentence for a required parameter the caller did not supply.
     *
     * <p>
     * A parameter supplied as JSON {@code null} reaches this method as missing rather than as a type error, because
     * {@code ToolInput} drops null values (see {@code at.aimon.core.base.NullSafeMaps}). That is the more useful of
     * the two readings: "you did not give me this" is actionable, "this is not a string" for a value the model never
     * meant to send is not.
     *
     * @param name
     *            the parameter name (must not be null)
     * @param declaredType
     *            the schema's declared type for it, or null when the schema does not declare one
     * @return the violation sentence (never null)
     */
    public static String missingRequired(String name, String declaredType) {
        Objects.requireNonNull(name, "Parameter name cannot be null");
        final String type = declaredType == null || declaredType.isBlank() ? "" : " (type: " + declaredType + ")";
        return "Parameter '" + name + "' is required" + type + ". " + NOT_EXECUTED;
    }

    /**
     * Builds the sentence for a parameter whose value is not of the declared type.
     *
     * @param name
     *            the parameter name (must not be null)
     * @param expectedType
     *            the schema's declared type, already rendered — a plain {@code "number"} or, for a type union, a
     *            phrase such as {@code "string or number"} (must not be null)
     * @return the violation sentence (never null)
     */
    public static String typeMismatch(String name, String expectedType) {
        Objects.requireNonNull(name, "Parameter name cannot be null");
        Objects.requireNonNull(expectedType, "Expected type cannot be null");
        return "Parameter '" + name + "' must be " + article(expectedType) + ' ' + expectedType + ". " + NOT_EXECUTED;
    }

    /**
     * Picks {@code a} or {@code an} for a rendered type name.
     *
     * <p>
     * Only the six JSON Schema type names and unions of them reach this, and none of them starts with a vowel that
     * the letter alone gets wrong — so the first character is enough, and "an integer" / "an array" / "an object"
     * come out right without a pronunciation table.
     *
     * @param noun
     *            the rendered type name (must not be empty)
     * @return {@code "a"} or {@code "an"}
     */
    private static String article(String noun) {
        if (noun.isEmpty()) {
            return "a";
        }
        return "aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0 ? "an" : "a";
    }

    /**
     * Builds the sentence for a parameter whose value is outside the declared {@code enum}.
     *
     * <p>
     * The allowed values are listed in full rather than summarised. They are short by construction, and a model that
     * can see the list picks from it instead of guessing a second time.
     *
     * @param name
     *            the parameter name (must not be null)
     * @param allowed
     *            the declared enum values, in schema order (must not be null)
     * @param actual
     *            the value that was supplied (may be null only in the sense that a caller could pass it; callers on
     *            the tool path cannot, since null values are dropped before validation)
     * @return the violation sentence (never null)
     */
    public static String notInEnum(String name, Collection<?> allowed, Object actual) {
        Objects.requireNonNull(name, "Parameter name cannot be null");
        Objects.requireNonNull(allowed, "Allowed values cannot be null");
        final String choices = allowed.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return "Parameter '" + name + "' must be one of [" + choices + "], but was '" + actual + "'. " + NOT_EXECUTED;
    }

    /**
     * Builds the sentence for a parameter the schema does not declare.
     *
     * <p>
     * Only reachable for schemas that opt into strictness with {@code additionalProperties: false}; JSON Schema's
     * default is to allow undeclared keys, and third-party (MCP) schemas keep that default.
     *
     * @param name
     *            the undeclared parameter name (must not be null)
     * @param declaredNames
     *            every parameter the schema does declare, used to look for a near miss (must not be null)
     * @return the violation sentence, carrying a suggestion when there is exactly one plausible candidate (never null)
     */
    public static String unknownParameter(String name, Collection<String> declaredNames) {
        Objects.requireNonNull(name, "Parameter name cannot be null");
        Objects.requireNonNull(declaredNames, "Declared names cannot be null");
        final String suggestion = didYouMean(name, declaredNames).map(s -> " Did you mean '" + s + "'?").orElse("");
        return "Unknown parameter '" + name + "'." + suggestion + " " + NOT_EXECUTED;
    }

    /**
     * Finds the one declared name that the given name was probably a typo for.
     *
     * <p>
     * Deliberately silent when more than one candidate is within reach. A wrong suggestion costs more than no
     * suggestion: the model will usually take it, and then the next call fails for a new reason.
     *
     * @param name
     *            the name that was not declared (must not be null)
     * @param declaredNames
     *            the declared names to search (must not be null)
     * @return the unique near miss, or empty if there is none or more than one
     */
    public static Optional<String> didYouMean(String name, Collection<String> declaredNames) {
        Objects.requireNonNull(name, "Parameter name cannot be null");
        Objects.requireNonNull(declaredNames, "Declared names cannot be null");
        final List<String> candidates = declaredNames.stream()
                .filter(declared -> levenshtein(name, declared) <= MAX_SUGGESTION_DISTANCE).limit(2)
                .collect(Collectors.toList());
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    /**
     * Computes the Levenshtein edit distance between two names.
     *
     * <p>
     * Two rows rather than a full matrix — parameter names are short, but this runs once per unknown key per rejected
     * call and there is no reason to allocate more than the algorithm needs.
     *
     * @param left
     *            the first name (must not be null)
     * @param right
     *            the second name (must not be null)
     * @return the number of single-character edits between them
     */
    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                final int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
