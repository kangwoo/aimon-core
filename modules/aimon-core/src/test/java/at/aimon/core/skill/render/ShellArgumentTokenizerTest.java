package at.aimon.core.skill.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ShellArgumentTokenizer}. */
class ShellArgumentTokenizerTest {

    private ShellArgumentTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        tokenizer = new ShellArgumentTokenizer();
    }

    @Test
    void tokenize_NullInput_ReturnsEmptyList() {
        assertThat(tokenizer.tokenize(null)).isEmpty();
    }

    @Test
    void tokenize_EmptyInput_ReturnsEmptyList() {
        assertThat(tokenizer.tokenize("")).isEmpty();
    }

    @Test
    void tokenize_WhitespaceOnly_ReturnsEmptyList() {
        assertThat(tokenizer.tokenize("   \t  ")).isEmpty();
    }

    @Test
    void tokenize_SingleToken_ReturnsOneToken() {
        assertThat(tokenizer.tokenize("hello")).containsExactly("hello");
    }

    @Test
    void tokenize_MultipleTokens_SplitsByWhitespace() {
        assertThat(tokenizer.tokenize("a b c")).containsExactly("a", "b", "c");
    }

    @Test
    void tokenize_LeadingAndTrailingWhitespace_IsTrimmed() {
        assertThat(tokenizer.tokenize("  a  b  ")).containsExactly("a", "b");
    }

    @Test
    void tokenize_TabsAndMixedWhitespace_AreSeparators() {
        assertThat(tokenizer.tokenize("a\tb \t c")).containsExactly("a", "b", "c");
    }

    @Test
    void tokenize_SingleQuotedString_PreservesContentLiterally() {
        assertThat(tokenizer.tokenize("'hello world'")).containsExactly("hello world");
    }

    @Test
    void tokenize_SingleQuotesPreserveBackslashesLiterally() {
        assertThat(tokenizer.tokenize("'a\\b'")).containsExactly("a\\b");
    }

    @Test
    void tokenize_SingleQuotesPreserveDoubleQuotesLiterally() {
        assertThat(tokenizer.tokenize("'say \"hi\"'")).containsExactly("say \"hi\"");
    }

    @Test
    void tokenize_DoubleQuotedString_PreservesSpacesInsideToken() {
        assertThat(tokenizer.tokenize("\"hello world\"")).containsExactly("hello world");
    }

    @Test
    void tokenize_DoubleQuotedStringWithEscapedQuote_UnescapesQuote() {
        assertThat(tokenizer.tokenize("\"say \\\"hi\\\"\"")).containsExactly("say \"hi\"");
    }

    @Test
    void tokenize_DoubleQuotedStringWithEscapedBackslash_UnescapesBackslash() {
        assertThat(tokenizer.tokenize("\"a\\\\b\"")).containsExactly("a\\b");
    }

    @Test
    void tokenize_DoubleQuotedStringWithUnknownEscape_KeepsBackslashLiterally() {
        assertThat(tokenizer.tokenize("\"a\\nb\"")).containsExactly("a\\nb");
    }

    @Test
    void tokenize_UnquotedBackslash_EscapesNextCharacter() {
        assertThat(tokenizer.tokenize("a\\ b")).containsExactly("a b");
    }

    @Test
    void tokenize_UnquotedBackslashEscapesQuoteCharacter() {
        assertThat(tokenizer.tokenize("a\\'b")).containsExactly("a'b");
    }

    @Test
    void tokenize_MixedQuotingInSingleToken_IsConcatenated() {
        assertThat(tokenizer.tokenize("a'b c'd")).containsExactly("ab cd");
    }

    @Test
    void tokenize_MixedSingleAndDoubleQuotes_AreConcatenated() {
        assertThat(tokenizer.tokenize("'foo'\"bar\"")).containsExactly("foobar");
    }

    @Test
    void tokenize_UnterminatedSingleQuote_IsConsumedLeniently() {
        assertThat(tokenizer.tokenize("'unterminated")).containsExactly("unterminated");
    }

    @Test
    void tokenize_UnterminatedDoubleQuote_IsConsumedLeniently() {
        assertThat(tokenizer.tokenize("\"unterminated")).containsExactly("unterminated");
    }

    @Test
    void tokenize_TrailingBackslash_IsConsumedLeniently() {
        assertThat(tokenizer.tokenize("hello\\")).containsExactly("hello");
    }

    @Test
    void tokenize_EmptySingleQuotes_ProduceEmptyToken() {
        assertThat(tokenizer.tokenize("''")).containsExactly("");
    }

    @Test
    void tokenize_EmptyDoubleQuotes_ProduceEmptyToken() {
        assertThat(tokenizer.tokenize("\"\"")).containsExactly("");
    }

    @Test
    void tokenize_ComplexCommandLine_TokenizesCorrectly() {
        assertThat(tokenizer.tokenize("git commit -m \"initial commit\" --author='alice <a@b.c>'"))
                .containsExactly("git", "commit", "-m", "initial commit", "--author=alice <a@b.c>");
    }

    @Test
    void tokenize_KoreanUtf8Characters_ArePreserved() {
        assertThat(tokenizer.tokenize("안녕 세계")).containsExactly("안녕", "세계");
    }

    @Test
    void tokenize_KoreanInsideDoubleQuotes_IsPreserved() {
        assertThat(tokenizer.tokenize("\"안녕 세계\"")).containsExactly("안녕 세계");
    }

    @Test
    void tokenize_ResultListIsImmutable() {
        final var result = tokenizer.tokenize("a b");
        assertThat(result).containsExactly("a", "b");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> result.add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
