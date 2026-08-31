package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for command input parsing using CommandInputParser with shell-like rules. */
class CommandInputParserTest {

    @Nested
    @DisplayName("Basic parsing")
    class BasicParsing {

        @Test
        @DisplayName("No arguments")
        void testParse_NoArguments() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/help");

            assertThat(result.name()).isEqualTo("help");
            assertThat(result.rawArguments()).isEmpty();
            assertThat(result.arguments()).isEmpty();
        }

        @Test
        @DisplayName("Single argument")
        void testParse_SingleArgument() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/commit message");

            assertThat(result.name()).isEqualTo("commit");
            assertThat(result.rawArguments()).isEqualTo("message");
            assertThat(result.arguments()).containsExactly("message");
        }

        @Test
        @DisplayName("Multiple arguments separated by spaces")
        void testParse_MultipleArguments() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/deploy app production --force");

            assertThat(result.name()).isEqualTo("deploy");
            assertThat(result.rawArguments()).isEqualTo("app production --force");
            assertThat(result.arguments()).containsExactly("app", "production", "--force");
        }

        @Test
        @DisplayName("Multiple spaces between arguments")
        void testParse_MultipleSpaces() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd arg1    arg2     arg3");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.rawArguments()).isEqualTo("arg1    arg2     arg3");
            assertThat(result.arguments()).containsExactly("arg1", "arg2", "arg3");
        }
    }

    @Nested
    @DisplayName("Double quotes")
    class DoubleQuotes {

        @Test
        @DisplayName("Quoted argument with spaces")
        void testParse_QuotedArgument() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/commit \"feat: Add feature\"");

            assertThat(result.name()).isEqualTo("commit");
            assertThat(result.rawArguments()).isEqualTo("\"feat: Add feature\"");
            assertThat(result.arguments()).containsExactly("feat: Add feature");
        }

        @Test
        @DisplayName("Multiple quoted arguments")
        void testParse_MultipleQuotedArguments() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd \"arg 1\" \"arg 2\" normal");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.rawArguments()).isEqualTo("\"arg 1\" \"arg 2\" normal");
            assertThat(result.arguments()).containsExactly("arg 1", "arg 2", "normal");
        }

        @Test
        @DisplayName("Escaped quote inside double quotes")
        void testParse_EscapedQuoteInDoubleQuote() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/echo \"quote: \\\"hi\\\"\"");

            assertThat(result.name()).isEqualTo("echo");
            assertThat(result.rawArguments()).isEqualTo("\"quote: \\\"hi\\\"\"");
            assertThat(result.arguments()).containsExactly("quote: \"hi\"");
        }

        @Test
        @DisplayName("Empty double quotes")
        void testParse_EmptyDoubleQuotes() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd \"\" arg");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.rawArguments()).isEqualTo("\"\" arg");
            assertThat(result.arguments()).containsExactly("", "arg");
        }

        @Test
        @DisplayName("Unclosed double quote throws exception")
        void testParse_UnclosedDoubleQuote() {
            assertThatThrownBy(() -> CommandInputParser.parse("/cmd \"unclosed"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unclosed double quote");
        }
    }

    @Nested
    @DisplayName("Single quotes")
    class SingleQuotes {

        @Test
        @DisplayName("Quoted argument with spaces")
        void testParse_SingleQuotedArgument() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd 'hello world'");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.arguments()).containsExactly("hello world");
        }

        @Test
        @DisplayName("No escape sequences in single quotes")
        void testParse_NoEscapeInSingleQuotes() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd 'hello\\nworld'");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.arguments()).containsExactly("hello\\nworld");
        }

        @Test
        @DisplayName("Double quotes inside single quotes are literal")
        void testParse_DoubleQuoteInSingleQuote() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd 'say \"hello\"'");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.arguments()).containsExactly("say \"hello\"");
        }

        @Test
        @DisplayName("Empty single quotes")
        void testParse_EmptySingleQuotes() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd '' arg");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.arguments()).containsExactly("", "arg");
        }

        @Test
        @DisplayName("Unclosed single quote throws exception")
        void testParse_UnclosedSingleQuote() {
            assertThatThrownBy(() -> CommandInputParser.parse("/cmd 'unclosed"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unclosed single quote");
        }
    }

    @Nested
    @DisplayName("Mixed quotes")
    class MixedQuotes {

        @Test
        @DisplayName("Mix of single and double quotes")
        void testParse_MixedQuotes() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/echo \"It's\" 'a \"test\"'");

            assertThat(result.name()).isEqualTo("echo");
            assertThat(result.arguments()).containsExactly("It's", "a \"test\"");
        }

        @Test
        @DisplayName("Adjacent quoted strings")
        void testParse_AdjacentQuotes() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd \"hello\"world");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.arguments()).containsExactly("helloworld");
        }
    }

    @Nested
    @DisplayName("Backslash escaping")
    class Escaping {

        @Test
        @DisplayName("Escape space")
        void testParse_EscapeSpace() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd hello\\ world");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.arguments()).containsExactly("hello world");
        }

        @Test
        @DisplayName("Escape backslash")
        void testParse_EscapeBackslash() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd hello\\\\world");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.arguments()).containsExactly("hello\\world");
        }

        @Test
        @DisplayName("Escape in double quotes")
        void testParse_EscapeInDoubleQuotes() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd \"hello\\nworld\"");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.arguments()).containsExactly("hellonworld");
        }

        @Test
        @DisplayName("Trailing escape character throws exception")
        void testParse_TrailingEscape() {
            assertThatThrownBy(() -> CommandInputParser.parse("/cmd hello\\"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Trailing escape character");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Null input throws exception")
        void testParse_NullInput() {
            assertThatThrownBy(() -> CommandInputParser.parse(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Command string cannot be null");
        }

        @Test
        @DisplayName("Missing slash throws exception")
        void testParse_MissingSlash() {
            assertThatThrownBy(() -> CommandInputParser.parse("commit message"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Command must start with '/'");
        }

        @Test
        @DisplayName("Whitespace before slash is trimmed")
        void testParse_WhitespaceBeforeSlash() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("  /commit message  ");

            assertThat(result.name()).isEqualTo("commit");
            assertThat(result.arguments()).containsExactly("message");
        }

        @Test
        @DisplayName("Tab characters as separators")
        void testParse_TabSeparators() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd\targ1\targ2");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.arguments()).containsExactly("arg1", "arg2");
        }

        @Test
        @DisplayName("Only spaces in arguments")
        void testParse_OnlySpaces() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd    ");

            assertThat(result.name()).isEqualTo("cmd");
            assertThat(result.arguments()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Real world examples")
    class RealWorldExamples {

        @Test
        @DisplayName("Git commit with message")
        void testParse_GitCommit() {
            CommandInputParser.ParsedCommand result = CommandInputParser
                    .parse("/commit \"feat: Add user authentication\" --no-verify");

            assertThat(result.name()).isEqualTo("commit");
            assertThat(result.arguments()).containsExactly("feat: Add user authentication", "--no-verify");
        }

        @Test
        @DisplayName("Deploy command with options")
        void testParse_DeployCommand() {
            CommandInputParser.ParsedCommand result = CommandInputParser
                    .parse("/deploy --env production --region us-west-2 --force");

            assertThat(result.name()).isEqualTo("deploy");
            assertThat(result.arguments()).containsExactly("--env", "production", "--region", "us-west-2", "--force");
        }

        @Test
        @DisplayName("Echo with mixed quotes")
        void testParse_EchoMixedQuotes() {
            CommandInputParser.ParsedCommand result = CommandInputParser
                    .parse("/echo \"It's a 'beautiful' day\" today");

            assertThat(result.name()).isEqualTo("echo");
            assertThat(result.arguments()).containsExactly("It's a 'beautiful' day", "today");
        }

        @Test
        @DisplayName("File path with spaces")
        void testParse_FilePathWithSpaces() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/open \"My Documents/file.txt\"");

            assertThat(result.name()).isEqualTo("open");
            assertThat(result.arguments()).containsExactly("My Documents/file.txt");
        }
    }

    @Nested
    @DisplayName("ParsedCommand validation")
    class ParsedCommandValidation {

        @Test
        @DisplayName("Arguments list is immutable")
        void testParsedCommand_ImmutableArguments() {
            CommandInputParser.ParsedCommand result = CommandInputParser.parse("/cmd arg1 arg2");

            assertThatThrownBy(() -> result.arguments().add("arg3")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Null name throws exception")
        void testParsedCommand_NullName() {
            assertThatThrownBy(() -> new CommandInputParser.ParsedCommand(null, "", List.of()))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Command name cannot be null");
        }

        @Test
        @DisplayName("Empty name throws exception")
        void testParsedCommand_EmptyName() {
            assertThatThrownBy(() -> new CommandInputParser.ParsedCommand("", "", List.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Command name cannot be empty");
        }

        @Test
        @DisplayName("Null raw arguments throws exception")
        void testParsedCommand_NullRawArguments() {
            assertThatThrownBy(() -> new CommandInputParser.ParsedCommand("cmd", null, List.of()))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Raw arguments cannot be null");
        }

        @Test
        @DisplayName("Null arguments throws exception")
        void testParsedCommand_NullArguments() {
            assertThatThrownBy(() -> new CommandInputParser.ParsedCommand("cmd", "", null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Arguments cannot be null");
        }
    }
}
