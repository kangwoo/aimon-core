package at.aimon.core.agent.tool.search;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.search.ToolSearchQueryParser.ParsedQuery;
import at.aimon.core.agent.tool.search.ToolSearchQueryParser.QueryMode;

@DisplayName("ToolSearchQueryParser Tests")
class ToolSearchQueryParserTest {

    @Nested
    @DisplayName("Keyword Mode")
    class KeywordMode {

        @Test
        @DisplayName("Plain text is parsed as KEYWORD mode")
        void testKeyword() {
            final ParsedQuery result = ToolSearchQueryParser.parse("read file");

            assertThat(result.getMode()).isEqualTo(QueryMode.KEYWORD);
            assertThat(result.getKeywords()).isEqualTo("read file");
        }

        @Test
        @DisplayName("Empty string is parsed as KEYWORD mode with empty keywords")
        void testEmpty() {
            final ParsedQuery result = ToolSearchQueryParser.parse("");

            assertThat(result.getMode()).isEqualTo(QueryMode.KEYWORD);
            assertThat(result.getKeywords()).isEmpty();
        }

        @Test
        @DisplayName("Whitespace-only is parsed as KEYWORD mode with empty keywords")
        void testWhitespace() {
            final ParsedQuery result = ToolSearchQueryParser.parse("   ");

            assertThat(result.getMode()).isEqualTo(QueryMode.KEYWORD);
            assertThat(result.getKeywords()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Select Mode")
    class SelectMode {

        @Test
        @DisplayName("select: prefix with single tool name")
        void testSingleSelect() {
            final ParsedQuery result = ToolSearchQueryParser.parse("select:Read");

            assertThat(result.getMode()).isEqualTo(QueryMode.SELECT);
            assertThat(result.getToolNames()).containsExactly("Read");
        }

        @Test
        @DisplayName("select: prefix with multiple tool names")
        void testMultipleSelect() {
            final ParsedQuery result = ToolSearchQueryParser.parse("select:Read,Edit,Grep");

            assertThat(result.getMode()).isEqualTo(QueryMode.SELECT);
            assertThat(result.getToolNames()).containsExactly("Read", "Edit", "Grep");
        }

        @Test
        @DisplayName("select: trims tool names and ignores empty parts")
        void testTrimAndIgnoreEmpty() {
            final ParsedQuery result = ToolSearchQueryParser.parse("select: Read , , Edit ");

            assertThat(result.getMode()).isEqualTo(QueryMode.SELECT);
            assertThat(result.getToolNames()).containsExactly("Read", "Edit");
        }
    }

    @Nested
    @DisplayName("Required Mode")
    class RequiredMode {

        @Test
        @DisplayName("+ prefix with required keyword and ranking keywords")
        void testRequired() {
            final ParsedQuery result = ToolSearchQueryParser.parse("+slack send message");

            assertThat(result.getMode()).isEqualTo(QueryMode.REQUIRED);
            assertThat(result.getRequiredKeyword()).isEqualTo("slack");
            assertThat(result.getRankingKeywords()).containsExactly("send", "message");
        }

        @Test
        @DisplayName("+ prefix with only required keyword")
        void testRequiredOnly() {
            final ParsedQuery result = ToolSearchQueryParser.parse("+slack");

            assertThat(result.getMode()).isEqualTo(QueryMode.REQUIRED);
            assertThat(result.getRequiredKeyword()).isEqualTo("slack");
            assertThat(result.getRankingKeywords()).isEmpty();
        }

        @Test
        @DisplayName("+ with nothing after it is parsed as KEYWORD with empty keywords")
        void testPlusOnly() {
            final ParsedQuery result = ToolSearchQueryParser.parse("+");

            assertThat(result.getMode()).isEqualTo(QueryMode.KEYWORD);
            assertThat(result.getKeywords()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Null Safety")
    class NullSafety {

        @Test
        @DisplayName("null query throws NullPointerException")
        void testNull() {
            assertThatNullPointerException().isThrownBy(() -> ToolSearchQueryParser.parse(null));
        }
    }
}
