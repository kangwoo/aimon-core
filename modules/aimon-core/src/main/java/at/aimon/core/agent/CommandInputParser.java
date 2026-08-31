package at.aimon.core.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Parses user input command strings into structured command information.
 *
 * <p>
 * This class is responsible for parsing command input strings in the format {@code /command-name
 * [arguments...]} and extracting the command name and arguments.
 *
 * <p>
 * Implements shell-like argument parsing with support for:
 *
 * <ul>
 * <li>Whitespace-separated arguments: {@code cmd arg1 arg2 arg3}
 * <li>Double quotes with escape sequences: {@code "hello \"world\""}
 * <li>Single quotes (literal, no escaping): {@code 'hello world'}
 * <li>Backslash escaping: {@code hello\ world}
 * <li>Empty arguments: {@code "" ''}
 * </ul>
 *
 * <p>
 * Examples:
 *
 * <pre>
 * {@code
 * // Multiple arguments
 * CommandInputParser.parse("/commit feat: Add feature")
 *   → ParsedCommand(name="commit", arguments=["feat:", "Add", "feature"])
 *
 * // Quoted arguments with spaces
 * CommandInputParser.parse("/commit \"feat: Add feature\" --amend")
 *   → ParsedCommand(name="commit", arguments=["feat: Add feature", "--amend"])
 *
 * // Mixed quotes
 * CommandInputParser.parse("/echo \"It's\" 'a \"test\"'")
 *   → ParsedCommand(name="echo", arguments=["It's", "a \"test\""])
 *
 * // Escaped characters
 * CommandInputParser.parse("/echo hello\\ world \"quote: \\\"hi\\\"\"")
 *   → ParsedCommand(name="echo", arguments=["hello world", "quote: \"hi\""])
 * }
 * </pre>
 *
 * <p>
 * This class is stateless and thread-safe.
 */
public final class CommandInputParser {

    /**
     * Private constructor to prevent instantiation. This is a utility class with only static methods.
     */
    private CommandInputParser() {
        throw new AssertionError("Cannot instantiate CommandInputParser");
    }

    /**
     * Parses a command string into name and arguments.
     *
     * <p>
     * The command string must start with a {@code /} character.
     *
     * <p>
     * Supports shell-like argument parsing with quotes and escaping.
     *
     * @param commandString
     *            The command string (must not be null and must start with '/')
     * @return The parsed command (never null)
     * @throws NullPointerException
     *             if commandString is null
     * @throws IllegalArgumentException
     *             if commandString doesn't start with '/' or has parsing errors
     */
    public static ParsedCommand parse(String commandString) {
        Objects.requireNonNull(commandString, "Command string cannot be null");

        String trimmed = commandString.trim();
        if (!trimmed.startsWith("/")) {
            throw new IllegalArgumentException("Command must start with '/': " + commandString);
        }

        // Remove leading /
        String commandPart = trimmed.substring(1);

        // Extract command name (first token, separated by space or tab)
        int spaceIndex = -1;
        for (int i = 0; i < commandPart.length(); i++) {
            char c = commandPart.charAt(i);
            if (c == ' ' || c == '\t') {
                spaceIndex = i;
                break;
            }
        }

        String commandName;
        String argsString;

        if (spaceIndex > 0) {
            commandName = commandPart.substring(0, spaceIndex);
            argsString = commandPart.substring(spaceIndex + 1);
        } else {
            commandName = commandPart;
            argsString = "";
        }

        // Parse arguments with shell-like rules
        List<String> arguments = parseArguments(argsString);

        return new ParsedCommand(commandName, argsString, arguments);
    }

    /**
     * Parses arguments string using shell-like rules.
     *
     * <p>
     * State machine implementation that handles:
     *
     * <ul>
     * <li>NORMAL: Regular characters and whitespace
     * <li>IN_DOUBLE_QUOTE: Inside double quotes (escape sequences work)
     * <li>IN_SINGLE_QUOTE: Inside single quotes (no escape sequences)
     * <li>ESCAPE: After backslash in NORMAL or IN_DOUBLE_QUOTE state
     * </ul>
     *
     * @param argsString
     *            The arguments string to parse
     * @return List of parsed arguments
     * @throws IllegalArgumentException
     *             if quotes are unmatched
     */
    private static List<String> parseArguments(String argsString) {
        if (argsString == null || argsString.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> arguments = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        ParserState state = ParserState.NORMAL;
        boolean hadQuotes = false; // Track if we had quotes (to preserve empty strings)

        for (int i = 0; i < argsString.length(); i++) {
            char c = argsString.charAt(i);

            switch (state) {
                case NORMAL :
                    if (c == ' ' || c == '\t') {
                        // Whitespace: finish current argument if non-empty OR if it was quoted
                        if (currentArg.length() > 0 || hadQuotes) {
                            arguments.add(currentArg.toString());
                            currentArg.setLength(0);
                            hadQuotes = false;
                        }
                    } else if (c == '"') {
                        state = ParserState.IN_DOUBLE_QUOTE;
                        hadQuotes = true;
                    } else if (c == '\'') {
                        state = ParserState.IN_SINGLE_QUOTE;
                        hadQuotes = true;
                    } else if (c == '\\') {
                        state = ParserState.ESCAPE;
                    } else {
                        currentArg.append(c);
                    }
                    break;

                case IN_DOUBLE_QUOTE :
                    if (c == '"') {
                        state = ParserState.NORMAL;
                    } else if (c == '\\') {
                        state = ParserState.ESCAPE_IN_DOUBLE_QUOTE;
                    } else {
                        currentArg.append(c);
                    }
                    break;

                case IN_SINGLE_QUOTE :
                    if (c == '\'') {
                        state = ParserState.NORMAL;
                    } else {
                        // Everything is literal inside single quotes
                        currentArg.append(c);
                    }
                    break;

                case ESCAPE :
                    // Backslash escapes the next character in normal context
                    currentArg.append(c);
                    state = ParserState.NORMAL;
                    break;

                case ESCAPE_IN_DOUBLE_QUOTE :
                    // Inside double quotes, backslash escapes: ", \, $, `
                    // For simplicity, we escape any character
                    currentArg.append(c);
                    state = ParserState.IN_DOUBLE_QUOTE;
                    break;
                default :
                    throw new IllegalStateException("Unknown parser state: " + state);
            }
        }

        // Check for unclosed quotes
        if (state == ParserState.IN_DOUBLE_QUOTE) {
            throw new IllegalArgumentException("Unclosed double quote in arguments: " + argsString);
        }
        if (state == ParserState.IN_SINGLE_QUOTE) {
            throw new IllegalArgumentException("Unclosed single quote in arguments: " + argsString);
        }
        if (state == ParserState.ESCAPE || state == ParserState.ESCAPE_IN_DOUBLE_QUOTE) {
            throw new IllegalArgumentException("Trailing escape character in arguments: " + argsString);
        }

        // Add final argument if any OR if it was quoted (to preserve empty strings)
        if (currentArg.length() > 0 || hadQuotes) {
            arguments.add(currentArg.toString());
        }

        return arguments;
    }

    /** Parser state for argument parsing. */
    private enum ParserState {
        NORMAL, // Normal parsing
        IN_DOUBLE_QUOTE, // Inside double quotes
        IN_SINGLE_QUOTE, // Inside single quotes
        ESCAPE, // After backslash in normal context
        ESCAPE_IN_DOUBLE_QUOTE // After backslash in double quote
    }

    /**
     * Represents a parsed command with name and arguments.
     *
     * @param name
     *            The command name (never null or empty)
     * @param rawArguments
     *            The raw argument string before parsing (never null, may be empty)
     * @param arguments
     *            The command arguments as a list (never null, may be empty)
     */
    public record ParsedCommand(String name, String rawArguments, List<String> arguments) {
        /**
         * Compact constructor that validates the parameters.
         *
         * @throws NullPointerException
         *             if name, rawArguments, or arguments is null
         * @throws IllegalArgumentException
         *             if name is empty
         */
        public ParsedCommand {
            Objects.requireNonNull(name, "Command name cannot be null");
            Objects.requireNonNull(rawArguments, "Raw arguments cannot be null");
            Objects.requireNonNull(arguments, "Arguments cannot be null");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Command name cannot be empty");
            }
            // Make defensive copy and ensure immutability
            arguments = List.copyOf(arguments);
        }
    }
}
